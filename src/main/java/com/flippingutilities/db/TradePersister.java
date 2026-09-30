/*
 * Copyright (c) 2020, Belieal <https://github.com/Belieal>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.flippingutilities.db;

import com.flippingutilities.model.*;
import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.ui.uiutilities.TimeFormatters;
import com.google.common.io.BaseEncoding;
import com.google.gson.Gson;
import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.annotations.Expose;
import com.google.gson.stream.JsonWriter;
import com.google.gson.reflect.TypeToken;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;
import org.apache.commons.text.StringEscapeUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * This class is responsible for handling all the IO related tasks for persisting trades. This class should contain
 * any logic that pertains to reading/writing to disk. This includes logic related to whether it should reload things
 * again, etc.
 */
@Slf4j
public class TradePersister
{
	private static final BaseEncoding ACCOUNT_NAME_ENCODING = BaseEncoding.base16().lowerCase();
    private static final String[] ACCOUNT_FILE_SUFFIXES = {
        ".identity.special.json", ".backup.json.tmp", ".json.pre-migration", ".backup.json", ".json.tmp", ".json"
    };
    private Map<String, AccountData> accountIndex = new HashMap<>();

	/** Gson for deserialization (reads all fields) */
	Gson gson;
	
	/** Gson for serialization (excludes fields with @Expose(serialize=false)) */
	private final Gson writeGson;

	@Getter
	private final Filepath directory;

	public TradePersister(Gson gson, Filepath directory) {
		this.gson = gson;
		this.directory = directory;
		// Create a Gson for writing that excludes fields marked with @Expose(serialize=false)
		this.writeGson = gson.newBuilder()
			.setExclusionStrategies(new ExclusionStrategy() {
				@Override
				public boolean shouldSkipField(FieldAttributes f) {
					Expose expose = f.getAnnotation(Expose.class);
					return expose != null && !expose.serialize();
				}

				@Override
				public boolean shouldSkipClass(Class<?> clazz) {
					return false;
				}
			})
			.create();
	}

    /** File locks are released by the OS if a client exits during a migration. */
    private StorageLock lockStorage() throws IOException {
        FileChannel channel = directory.joinSegment("accounts.lock")
            .openFileChannel(StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new StorageBusyException();
            return new StorageLock(channel, lock);
        } catch (OverlappingFileLockException e) {
            channel.close();
            throw new StorageBusyException();
        } catch (IOException e) {
            channel.close();
            throw e;
        }
    }

    public static class StorageBusyException extends IOException {
        public StorageBusyException() { super("Another RuneLite client is updating account files; retry later"); }
    }

    private static class StorageLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private StorageLock(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
        @Override public void close() throws IOException {
            try { lock.release(); } finally { channel.close(); }
        }
    }

    /** Legacy names stay literal until an authenticated login supplies their account ID. */
    public void setupFlippingFolder() throws IOException {
        directory.createDirectories();
        directory.joinSegment("trades.json").deleteIfExists();
    }

    public synchronized void setAccountIndex(Map<String, AccountData> accounts) {
        accountIndex = new HashMap<>(accounts);
    }

    public synchronized boolean accountFileExists(AccountData data) throws IOException {
        return data.getStorageFileName() != null && existingFile(data.getStorageFileName()) != null;
    }

    private static boolean isAccountFile(String name) {
        return name.endsWith(".json") && !name.equals("accountwide.json")
            && !name.endsWith(".backup.json") && !name.endsWith(".special.json");
    }

    /** Legacy filenames must never be decoded: @ followed by hex can be a literal old name. */
    public static String accountNameFromFileName(String fileName) {
        if (!fileName.endsWith(".json")) {
            throw new IllegalArgumentException("Not an account JSON filename: " + fileName);
        }
        return fileName.substring(0, fileName.length() - 5);
    }

    private List<Filepath> files() throws IOException {
        try (Stream<Filepath> stream = directory.walk(1)) {
            List<Filepath> result = new ArrayList<>();
            stream.filter(Filepath::isFile).forEach(result::add);
            return result;
        }
    }

    // Walking also gives us Filepath handles for legacy filenames rejected by joinSegment (e.g. Con).
    private Filepath existingFile(String name) throws IOException {
        for (Filepath file : files()) {
            if (file.getFileName().equals(name)) return file;
        }
        return null;
    }

    private static void validateAccountId(String id) {
        if (id == null) throw new IllegalArgumentException("Missing account ID");
        long hash = Long.parseUnsignedLong(id);
        if (hash == -1L || !Long.toUnsignedString(hash).equals(id)) {
            throw new IllegalArgumentException("Invalid account ID: " + id);
        }
    }

    private static Filepath accountFile(Filepath directory, String name, String suffix) {
        if (name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("Account names cannot contain path separators");
        }
        try {
            return directory.joinSegment(name + suffix);
        } catch (IllegalArgumentException ignored) {
            return directory.joinSegment("legacy-@" + ACCOUNT_NAME_ENCODING.encode(name.getBytes(StandardCharsets.UTF_8)) + suffix);
        }
    }

    private static String identityStem(Filepath directory, String id, String name) {
        validateAccountId(id);
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("Missing display name");
        String stem = id + "_" + name;
        try {
            directory.joinSegment(stem + ".json");
            return stem;
        } catch (IllegalArgumentException ignored) {
            // Preserve unusual historical labels without placing separators or controls in a path.
            return id + "_@" + ACCOUNT_NAME_ENCODING.encode(name.getBytes(StandardCharsets.UTF_8));
        }
    }

    private Filepath sibling(Filepath primary, String suffix) throws IOException {
        String stem = accountNameFromFileName(primary.getFileName());
        Filepath existing = existingFile(stem + suffix);
        return existing != null ? existing : accountFile(directory, stem, suffix);
    }

    /** Read just identity fields, skipping trade arrays without allocating another account history. */
    private String[] identity(Filepath file) throws IOException {
        try (BufferedReader reader = file.openBufferedReader();
             com.google.gson.stream.JsonReader json = new com.google.gson.stream.JsonReader(reader)) {
            String id = null;
            String name = null;
            json.beginObject();
            while (json.hasNext()) {
                String field = json.nextName();
                if ((field.equals("accountId") || field.equals("displayName"))
                    && json.peek() != com.google.gson.stream.JsonToken.NULL) {
                    if (field.equals("accountId")) id = json.nextString();
                    else name = json.nextString();
                    if (id != null && name != null) break;
                } else json.skipValue();
            }
            return new String[]{id, name};
        } catch (RuntimeException e) {
            throw new IOException("Cannot read account identity from " + file.getFileName(), e);
        }
    }

    private String[] primaryIdentity(Filepath primary) throws IOException {
        Filepath marker = existingFile(accountNameFromFileName(primary.getFileName()) + ".identity.special.json");
        // This tiny record retains the ID/current name even when recovery uses an older, byte-preserved backup.
        if (marker != null) return identity(marker);
        return identity(primary);
    }

    private Filepath findById(String id) throws IOException {
        Filepath found = null;
        for (Filepath file : files()) {
            String filename = file.getFileName();
            boolean candidate = filename.startsWith(id + "_");
            if (!candidate) {
                candidate = accountIndex.values().stream().anyMatch(a -> id.equals(a.getAccountId())
                    && filename.equals(a.getStorageFileName()));
            }
            if (!candidate || !isAccountFile(filename)) continue;
            String[] identity;
            try { identity = primaryIdentity(file); }
            catch (IOException e) {
                // An indexed corrupt primary still needs to be recoverable from its backup.
                if (accountIndex.values().stream().anyMatch(a -> id.equals(a.getAccountId())
                    && filename.equals(a.getStorageFileName()))) identity = new String[]{id, null};
                else throw e;
            }
            if (!id.equals(identity[0])) continue;
            if (found != null) throw new IOException("Multiple primary files for account ID " + id);
            found = file;
        }
        return found;
    }

    public synchronized Map<String, AccountData> loadAllAccounts() throws IOException {
        List<AccountData> loaded = new ArrayList<>();
        for (Filepath file : files()) {
            if (isAccountFile(file.getFileName())) loaded.add(readAccount(file));
        }
        Map<String, AccountData> result = new LinkedHashMap<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (AccountData data : loaded) {
            if (data.getAccountId() != null && !ids.add(data.getAccountId())) {
                throw new IOException("Multiple primary files for account ID " + data.getAccountId());
            }
            String name = data.getDisplayName();
            long count = loaded.stream().filter(a -> name.equals(a.getDisplayName())).count();
            String key = count > 1 || name.equals(FlippingPlugin.ACCOUNT_WIDE)
                ? name + " [" + (data.getAccountId() == null ? "legacy" : data.getAccountId()) + "]" : name;
            if (result.putIfAbsent(key, data) != null) {
                throw new IOException("Ambiguous account files for " + key + "; no files were overwritten");
            }
            data.renameAccount(key);
        }
        setAccountIndex(result);
        return result;
    }

    private AccountData readAccount(Filepath primary) {
        AccountData data = null;
        try { data = loadFromFile(primary); }
        catch (Exception | OutOfMemoryError e) { log.warn("Unable to read {}; trying backup", primary.getFileName(), e); }
        if (data == null) {
            try { data = loadFromFile(sibling(primary, ".backup.json")); }
            catch (Exception | OutOfMemoryError e) { log.warn("Unable to recover {}", primary.getFileName(), e); }
        }
        if (data == null) {
            data = new AccountData();
            data.setPersistenceReadFailed(true);
        }
        try {
            String[] savedIdentity = primaryIdentity(primary);
            if (savedIdentity[0] != null) {
                data.setAccountId(savedIdentity[0]);
                data.setDisplayName(savedIdentity[1]);
            }
        } catch (IOException e) {
            log.debug("No separate identity available for {}", primary.getFileName());
        }
        if (data.getDisplayName() == null) data.setDisplayName(accountNameFromFileName(primary.getFileName()));
        if (data.getAccountId() != null) validateAccountId(data.getAccountId());
        data.setStorageFileName(primary.getFileName());
        return data;
    }

    /** A removed polling event must not fabricate an empty account or resurrect an old RSN. */
    public synchronized AccountData loadAccountFile(String filename) throws IOException {
        if (!isAccountFile(filename)) return null;
        Filepath file = existingFile(filename);
        if (file == null) return null;
        AccountData data = readAccount(file);
        if (data.isPersistenceReadFailed()) throw new IOException("Cannot reload unreadable account " + filename);
        return data;
    }

    public synchronized AccountData loadAccountById(String id) throws IOException {
        validateAccountId(id);
        Filepath file = findById(id);
        return file == null ? null : loadAccountFile(file.getFileName());
    }

    public synchronized AccountData loadAccount(String key) {
        try {
            AccountData indexed = accountIndex.get(key);
            Filepath file = indexed == null ? existingFile(key + ".json") : primaryFor(indexed, key, false);
            if (file == null) file = accountFile(directory, key, ".json");
            AccountData data = readAccount(file);
            accountIndex.put(key, data);
            return data;
        } catch (IOException e) {
            log.warn("Cannot load account {}", key, e);
            AccountData failed = new AccountData();
            failed.setDisplayName(key);
            failed.setPersistenceReadFailed(true);
            return failed;
        }
    }

    private Filepath primaryFor(AccountData data, String key, boolean writing) throws IOException {
        if (data.isPersistenceReadFailed() && writing) throw new IOException("Refusing to replace an unreadable account: " + key);
        if (data.getAccountId() != null) {
            validateAccountId(data.getAccountId());
            Filepath current = findById(data.getAccountId());
            if (current != null) {
                // Another client may have renamed this ID since we loaded it. Its name is canonical.
                try {
                    String currentName = primaryIdentity(current)[1];
                    if (currentName != null) data.setDisplayName(currentName);
                } catch (IOException e) {
                    if (!writing) return current;
                    throw e;
                }
                data.setStorageFileName(current.getFileName());
                return current;
            }
            if (data.getStorageFileName() != null && writing) {
                throw new IOException("Account file disappeared; reload before saving " + key);
            }
            return directory.joinSegment(identityStem(directory, data.getAccountId(), data.getDisplayName()) + ".json");
        }
        if (data.getStorageFileName() != null) {
            Filepath current = existingFile(data.getStorageFileName());
            if (current != null) {
                String diskId = primaryIdentity(current)[0];
                if (diskId != null) throw new IOException("Legacy account was migrated by another client; reload before saving");
                return current;
            }
            if (writing) throw new IOException("Legacy account was moved; reload before saving " + key);
        }
        String name = data.getDisplayName() == null ? key : data.getDisplayName();
        Filepath existing = existingFile(name + ".json");
        return existing != null ? existing : accountFile(directory, name, ".json");
    }

    /**
     * Bind only a character observed at login. Persist the identity before renaming so an interrupted
     * migration remains identifiable. Preflight every destination; moves never replace other files.
     */
    public synchronized void bindAccount(String id, String name, AccountData data) throws IOException {
        try (StorageLock ignored = lockStorage()) {
            validateAccountId(id);
            if (data.isPersistenceReadFailed()) throw new IOException("Cannot migrate unreadable history for " + name);
            if (data.getAccountId() != null && !id.equals(data.getAccountId())) throw new IOException("Account ID does not match history");
            Filepath source = findById(id);
            if (source == null && data.getStorageFileName() != null) source = existingFile(data.getStorageFileName());
            if (source == null && data.getAccountId() == null) source = existingFile(name + ".json");
            if (source != null && data.getStorageFileName() == null) {
                throw new IOException("History appeared on disk; reload before binding " + name);
            }
            if (source != null) {
                String diskId = primaryIdentity(source)[0];
                if (diskId != null && !diskId.equals(id)) throw new IOException("History belongs to another account ID");
            }
            Filepath target = directory.joinSegment(identityStem(directory, id, name) + ".json");
            Map<Filepath, Filepath> moves = new LinkedHashMap<>();
            if (source != null && !source.getFileName().equals(target.getFileName())) {
                String oldStem = accountNameFromFileName(source.getFileName());
                String newStem = accountNameFromFileName(target.getFileName());
                for (String suffix : ACCOUNT_FILE_SUFFIXES) {
                    Filepath oldFile = existingFile(oldStem + suffix);
                    Filepath newFile = directory.joinSegment(newStem + suffix);
                    Filepath exactTarget = existingFile(newFile.getFileName());
                    if (exactTarget != null || (newFile.exists() && (oldFile == null
                        || !oldFile.getFileName().equalsIgnoreCase(newFile.getFileName())))) {
                        throw new IOException("Cannot rename account: destination already exists: " + newFile.getFileName());
                    }
                    if (oldFile != null) moves.put(oldFile, newFile);
                }
            } else if (source == null) {
                for (String suffix : ACCOUNT_FILE_SUFFIXES) {
                    if (sibling(target, suffix).exists()) {
                        throw new IOException("Cannot create account: destination already exists: " + sibling(target, suffix).getFileName());
                    }
                }
            }
            data.setAccountId(id);
            data.setDisplayName(name);
            data.renameAccount(name);
            Filepath primary = source == null ? target : source;
            Map<String, String> marker = new LinkedHashMap<>();
            marker.put("accountId", id);
            marker.put("displayName", name);
            Filepath identityFile = sibling(primary, ".identity.special.json");
            writeAtomically(identityFile, marker, ".tmp");
            if (!primary.getFileName().equals(target.getFileName()) && !moves.containsKey(identityFile)) {
                moves.put(identityFile, sibling(target, ".identity.special.json"));
            }
            // Preserve an interrupted save as part of the family; use a separate temporary for identity writes.
            writeAtomically(primary, data, ".identity-write.tmp");
            data.setStorageFileName(primary.getFileName());
            List<Map.Entry<Filepath, Filepath>> completed = new ArrayList<>();
            try {
                for (Map.Entry<Filepath, Filepath> move : moves.entrySet()) {
                    moveWithoutReplacing(move.getKey(), move.getValue());
                    completed.add(move);
                }
            } catch (IOException e) {
                Collections.reverse(completed);
                for (Map.Entry<Filepath, Filepath> move : completed) {
                    try { moveWithoutReplacing(move.getValue(), move.getKey()); }
                    catch (IOException rollback) { e.addSuppressed(rollback); }
                }
                throw e;
            }
            data.setStorageFileName(target.getFileName());
            accountIndex.put(name, data);
        }
    }

    private void moveWithoutReplacing(Filepath source, Filepath target) throws IOException {
        if (!source.getFileName().equals(target.getFileName())
            && source.getFileName().equalsIgnoreCase(target.getFileName()) && target.exists()) {
            // Case-insensitive filesystems need an intermediate name to actually update filename casing.
            Filepath intermediate = directory.joinSegment(target.getFileName() + ".rename.tmp");
            source.moveTo(intermediate);
            try { intermediate.moveTo(target); }
            catch (IOException e) {
                try { intermediate.moveTo(source); } catch (IOException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        } else source.moveTo(target);
    }

	private AccountData loadFromFile(Filepath file) throws IOException
	{
		try (BufferedReader bufferedReader = file.openBufferedReader();
			com.google.gson.stream.JsonReader jsonReader = new com.google.gson.stream.JsonReader(bufferedReader))
		{
			return gson.fromJson(jsonReader, AccountData.class);
		}
	}


	public AccountWideData loadAccountWideData() throws IOException {
		Filepath accountFile = directory.joinSegment("accountwide.json");
		if (accountFile.exists()){
			Type type = new TypeToken<AccountWideData>(){}.getType();
			try (BufferedReader reader = accountFile.openBufferedReader()) {
				return gson.fromJson(reader, type);
			}
		}
		else {
			return new AccountWideData();
		}
	}

	public BackupCheckpoints fetchBackupCheckpoints() {
		try {
			log.debug("Fetching backup checkpoints");
			Filepath backupCheckpointsFile = directory.joinSegment("backupcheckpoints.special.json");
			if (!backupCheckpointsFile.exists()) {
				backupCheckpointsFile = directory.joinSegment("backupCheckpoints.special.json");
			}
			if (backupCheckpointsFile.exists()){
				Type type = new TypeToken<BackupCheckpoints>(){}.getType();
				try (BufferedReader reader = backupCheckpointsFile.openBufferedReader()) {
					return gson.fromJson(reader, type);
				}
			}
			else {
				return new BackupCheckpoints();
			}
		}
		catch (Exception e) {
			return new BackupCheckpoints();
		}
	}

    public synchronized void writeToFile(String key, Object data) throws IOException {
        try (StorageLock ignored = lockStorage()) {
            writeToFile(key, data, ".json");
        }
    }

    public synchronized void writeBackup(String key, Object data) throws IOException {
        try (StorageLock ignored = lockStorage()) {
            writeToFile(key, data, ".backup.json");
        }
    }

    private void writeToFile(String key, Object data, String suffix) throws IOException {
        Filepath file;
        if (data instanceof AccountData) {
            AccountData account = (AccountData) data;
            if (account.getDisplayName() == null) account.setDisplayName(key);
            Filepath primary = primaryFor(account, key, true);
            file = suffix.equals(".json") ? primary : sibling(primary, suffix);
            writeAtomically(file, account, ".tmp");
            account.setStorageFileName(primary.getFileName());
            accountIndex.put(key, account);
        } else {
            file = directory.joinSegment(key + suffix);
            writeAtomically(file, data, ".tmp");
        }
    }

    private void writeAtomically(Filepath file, Object data, String tempSuffix) throws IOException {
        Filepath temp = accountFile(directory, file.getFileName(), tempSuffix);
        try (BufferedWriter writer = temp.openBufferedWriter(); JsonWriter json = new JsonWriter(writer)) {
            writeGson.toJson(data, data.getClass(), json);
        } catch (IOException e) {
            try { temp.deleteIfExists(); } catch (IOException ignored) { }
            throw e;
        }
        try {
            try { temp.moveTo(file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { temp.moveTo(file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) {
            try { temp.deleteIfExists(); } catch (IOException ignored) { }
            throw e;
        }
    }

    private Filepath indexedPrimary(String key) throws IOException {
        AccountData data = accountIndex.get(key);
        if (data != null) return primaryFor(data, key, false);
        Filepath existing = existingFile(key + ".json");
        return existing != null ? existing : accountFile(directory, key, ".json");
    }

    public synchronized void deleteAccount(String key) {
        try (StorageLock ignored = lockStorage()) {
            Filepath primary = indexedPrimary(key);
            // Remove every variant so backups cannot later resurrect a deleted ID or block a rename.
            for (String suffix : ACCOUNT_FILE_SUFFIXES) sibling(primary, suffix).deleteIfExists();
            accountIndex.remove(key);
        } catch (IOException e) { log.warn("Unable to delete {}", key, e); }
    }

    public synchronized void createPreMigrationBackup(String key) throws IOException {
        try (StorageLock ignored = lockStorage()) {
            Filepath primary = indexedPrimary(key);
            Filepath backup = sibling(primary, ".json.pre-migration");
            if (!backup.exists() && primary.exists()) primary.copyTo(backup);
        }
    }

    public synchronized void deletePreMigrationBackup(String key) {
        try (StorageLock ignored = lockStorage()) { sibling(indexedPrimary(key), ".json.pre-migration").deleteIfExists(); }
        catch (IOException e) { log.warn("Failed to delete pre-migration backup for {}", key, e); }
    }

    public static Filepath exportToCsv(Filepath directory, String accountId, String displayName,
                                     List<FlippingItem> trades, String interval) throws IOException {
        if (accountId == null) return exportToCsv(directory, displayName, trades, interval);
        Filepath file = directory.joinSegment(identityStem(directory, accountId, displayName) + ".csv");
        exportToCsv(file, trades, interval);
        return file;
    }

	public static Filepath exportToCsv(Filepath directory, String accountName, List<FlippingItem> trades, String startOfIntervalName) throws IOException {
		Filepath file = accountFile(directory, accountName, ".csv");
		exportToCsv(file, trades, startOfIntervalName);
		return file;
	}

	public static void exportToCsv(Filepath file, List<FlippingItem> trades, String startOfIntervalName) throws IOException {
		try (BufferedWriter out = file.openBufferedWriter()) {
			String comment = "Displaying trades for selected time interval: " + startOfIntervalName;
			out.write("# " + comment.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n# ") + "\r\n");
			writeCsvRecord(out, "name", "date", "quantity", "price", "state");

			for (FlippingItem item : trades) {
				for (OfferEvent offer : item.getHistory().getCompressedOfferEvents()) {
					writeCsvRecord(out,
							item.getItemName(),
							TimeFormatters.formatInstantToDate(offer.getTime()),
							offer.getCurrentQuantityInTrade(),
							offer.getPrice(),
							offer.getState()
					);
				}
				out.write(String.format("# Total profit: %d\r\n\r\n",
					FlippingItem.getProfit(item.getHistory().getCompressedOfferEvents())));
			}
		}
	}

	private static void writeCsvRecord(BufferedWriter out, Object... fields) throws IOException {
		for (int i = 0; i < fields.length; i++) {
			if (i > 0) {
				out.write(',');
			}
			if (fields[i] == null) {
				continue;
			}
			String value = fields[i].toString();
			String escaped = StringEscapeUtils.escapeCsv(value);
			// Retain Commons CSV's additional quoting for empty first fields, whitespace,
			// and first fields starting with a non-ASCII-alphanumeric character.
			boolean quote = value.isEmpty() && i == 0;
			if (!value.isEmpty()) {
				char first = value.charAt(0);
				boolean startsWithLetterOrDigit = (first >= '0' && first <= '9')
					|| (first >= 'A' && first <= 'Z') || (first >= 'a' && first <= 'z');
				quote = first <= '#' || value.charAt(value.length() - 1) <= ' '
					|| (i == 0 && !startsWithLetterOrDigit);
			}
			if (quote && escaped.equals(value)) {
				escaped = '"' + escaped + '"';
			}
			out.write(escaped);
		}
		out.write("\r\n");
	}
}
