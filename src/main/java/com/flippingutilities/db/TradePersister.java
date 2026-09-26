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
import com.flippingutilities.ui.uiutilities.TimeFormatters;
import com.google.gson.Gson;
import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.Expose;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.BufferedWriter;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class is responsible for handling all the IO related tasks for persisting trades. This class should contain
 * any logic that pertains to reading/writing to disk. This includes logic related to whether it should reload things
 * again, etc.
 */
@Slf4j
public class TradePersister
{
	/**
	 * Reads every Instant encoding the plugin has ever written:
	 * - epoch-millis numbers (current format),
	 * - ISO-8601 strings,
	 * - nested {"seconds":X,"nanos":Y} objects (historical format from builds whose Gson
	 *   had no Instant adapter and serialized the field reflectively).
	 * Older files that contain the object form failed to parse with the adapter-bearing
	 * Gson; the lenient loader then fell back to the (equally old) backup, returned an
	 * EMPTY account, and the next save overwrote years of data with the empty state.
	 * Writes the current number format.
	 */
	private static final TypeAdapter<Instant> LEGACY_INSTANT =
		new TypeAdapter<Instant>() {
			@Override
			public void write(JsonWriter out, Instant value) throws IOException {
				if (value == null) { out.nullValue(); return; }
				out.value(value.toEpochMilli());
			}

			@Override
			public Instant read(JsonReader in) throws IOException {
				JsonToken token = in.peek();
				if (token == JsonToken.NULL) { in.nextNull(); return null; }
				if (token == JsonToken.NUMBER) { return Instant.ofEpochMilli(in.nextLong()); }
				if (token == JsonToken.STRING) {
					String s = in.nextString();
					if (s == null || s.trim().isEmpty()) { return null; }
					try {
						return Instant.parse(s);
					} catch (Exception ignored) {
						try {
							return Instant.ofEpochMilli(Long.parseLong(s.trim()));
						} catch (NumberFormatException nfe) {
							throw new JsonSyntaxException("Unparseable Instant: " + s, nfe);
						}
					}
				}
				if (token == JsonToken.BEGIN_OBJECT) {
					long seconds = 0;
					int nanos = 0;
					in.beginObject();
					while (in.hasNext()) {
						String name = in.nextName();
						if (name.equals("seconds") || name.equals("epochSecond")) {
							seconds = in.nextLong();
						} else if (name.equals("nanos") || name.equals("nano")) {
							nanos = (int) in.nextLong();
						} else {
							in.skipValue();
						}
					}
					in.endObject();
					return Instant.ofEpochSecond(seconds, nanos);
				}
				in.skipValue();
				return null;
			}
		};

	/** Gson for deserialization (reads all fields and all historical Instant encodings) */
	Gson gson;

	/** Gson for serialization (excludes fields with @Expose(serialize=false)) */
	private final Gson writeGson;
	private final File accountDirectory;
	private final Set<String> accountsWithLoadFailures = ConcurrentHashMap.newKeySet();
	private volatile boolean accountDirectoryUnreadable;

	/** A failed read or preparation must not turn a fallback empty account into saved data. */
	public void protectAccount(String displayName) {
		accountsWithLoadFailures.add(displayName);
	}

	/** Call only after a successfully prepared model has replaced the cached fallback. */
	public void accountPrepared(String displayName) {
		accountsWithLoadFailures.remove(displayName);
	}

	public boolean isAccountProtected(String displayName) {
		return accountDirectoryUnreadable || accountsWithLoadFailures.contains(displayName);
	}

	public TradePersister(Gson gson) {
		this(gson, PARENT_DIRECTORY);
	}

	TradePersister(Gson gson, File accountDirectory) {
		this.gson = gson.newBuilder().registerTypeAdapter(Instant.class, LEGACY_INSTANT).create();
		this.accountDirectory = accountDirectory;
		// Create a Gson for writing that excludes fields marked with @Expose(serialize=false)
		this.writeGson = this.gson.newBuilder()
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

	//this is in {user's home directory}/.runelite/flipping
	public static final File PARENT_DIRECTORY = new File(RuneLite.RUNELITE_DIR, "flipping");
	public static final File OLD_FILE = new File(PARENT_DIRECTORY, "trades.json");

	/**
	 * Creates the flipping directory if it does not exist. Legacy source files are preserved.
	 *
	 * @throws IOException handled in FlippingPlugin
	 */
	public static void setupFlippingFolder() throws IOException
	{
		if (!PARENT_DIRECTORY.exists())
		{
			log.debug("flipping directory doesn't exist yet so it's being created");
			if (!PARENT_DIRECTORY.mkdir())
			{
				throw new IOException("unable to create parent directory!");
			}
		}
		else
		{
			log.debug("flipping directory already exists so it's not being created");
		}
	}

	/** Loads healthy accounts independently; failed accounts remain protected from later writes. */
	public Map<String, AccountData> loadAllAccounts() {
		Map<String, AccountData> accounts = new HashMap<>();
		for (File file : accountFiles()) {
			if (!isAccountSnapshot(file.getName())) continue;
			String displayName = file.getName().substring(0, file.getName().length() - ".json".length());
			try {
				accounts.put(displayName, loadAccount(displayName));
			} catch (IllegalStateException failure) {
				log.error("Cannot load {}; its JSON saves and backups are disabled until valid data is loaded", displayName, failure);
			}
		}
		return accounts;
	}

	private File[] accountFiles() {
		File[] files = accountDirectory.listFiles();
		accountDirectoryUnreadable = files == null;
		if (files == null) {
			throw new IllegalStateException("Cannot list account snapshots in " + accountDirectory);
		}
		return files;
	}

	private static boolean isAccountSnapshot(String name) {
		return name.endsWith(".json") && !name.equalsIgnoreCase("accountwide.json")
			&& !name.equalsIgnoreCase("trades.json")
			&& !name.endsWith(".backup.json") && !name.endsWith(".special.json");
	}

	/** Migration must never turn an unreadable snapshot into an authoritative empty account. */
	public Map<String, AccountData> loadAllAccountsForMigration() {
		Map<String, AccountData> accounts = new HashMap<>();
		for (File file : accountFiles()) {
			if (!isAccountSnapshot(file.getName())) continue;
			String displayName = file.getName().substring(0, file.getName().length() - ".json".length());
			if (isAccountProtected(displayName)) {
				throw new IllegalStateException("Cannot migrate protected account " + displayName
					+ "; restore and load valid data first");
			}
			accounts.put(displayName, loadExistingAccount(displayName, file));
		}
		return accounts;
	}

	private AccountData readAccountWithBackup(String displayName, File primary) {
		try {
			return readAccountSnapshot(primary);
		} catch (IOException | RuntimeException | OutOfMemoryError primaryFailure) {
			try {
				AccountData backup = readAccountSnapshot(new File(accountDirectory, displayName + ".backup.json"));
				log.warn("Loaded {} from backup because its primary snapshot could not be read", displayName, primaryFailure);
				return backup;
			} catch (IOException | RuntimeException | OutOfMemoryError backupFailure) {
				IllegalStateException failure = new IllegalStateException(
					"Cannot read account snapshot or backup for " + displayName, primaryFailure);
				failure.addSuppressed(backupFailure);
				throw failure;
			}
		}
	}

	private AccountData readAccountSnapshot(File file) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8);
			JsonReader json = new JsonReader(reader)) {
			AccountData data = gson.fromJson(json, AccountData.class);
			if (data == null || json.peek() != JsonToken.END_DOCUMENT) {
				throw new IOException("Account snapshot is empty or incomplete: " + file);
			}
			return data;
		}
	}

	/** Loads an existing primary or backup; only a genuinely new account may be empty. */
	public AccountData loadAccount(String displayName) {
		File primary = new File(accountDirectory, displayName + ".json");
		File backup = new File(accountDirectory, displayName + ".backup.json");
		if (accountDirectory.isDirectory() && Files.notExists(primary.toPath())
			&& Files.notExists(backup.toPath()) && !isAccountProtected(displayName)) {
			return new AccountData();
		}
		return loadExistingAccount(displayName, primary);
	}

	private AccountData loadExistingAccount(String displayName, File primary) {
		try {
			return readAccountWithBackup(displayName, primary);
		} catch (IllegalStateException failure) {
			protectAccount(displayName);
			throw failure;
		}
	}

	public AccountWideData loadAccountWideData() throws IOException {
		File accountFile = new File(accountDirectory, "accountwide.json");
		if (accountDirectory.isDirectory() && Files.notExists(accountFile.toPath()) && !isAccountProtected("accountwide")) {
			return new AccountWideData();
		}
		try (BufferedReader reader = Files.newBufferedReader(accountFile.toPath(), StandardCharsets.UTF_8);
			JsonReader json = new JsonReader(reader)) {
			AccountWideData data = gson.fromJson(json, AccountWideData.class);
			if (data == null || json.peek() != JsonToken.END_DOCUMENT) {
				throw new IOException("Account-wide snapshot is empty or incomplete: " + accountFile);
			}
			return data;
		} catch (IOException | RuntimeException | OutOfMemoryError failure) {
			protectAccount("accountwide");
			throw new IOException("Cannot load account-wide data; saves disabled until valid data is loaded", failure);
		}
	}

	public BackupCheckpoints fetchBackupCheckpoints() {
		try {
			log.debug("Fetching backup checkpoints");
			File backupCheckpointsFile = new File(accountDirectory, "backupcheckpoints.special.json");
			if (backupCheckpointsFile.exists()){
				String backupCheckpointsJson = new String(Files.readAllBytes(backupCheckpointsFile.toPath()));
				Type type = new TypeToken<BackupCheckpoints>(){}.getType();
				return gson.fromJson(backupCheckpointsJson, type);
			}
			else {
				return new BackupCheckpoints();
			}
		}
		catch (Exception e) {
			return new BackupCheckpoints();
		}
	}

	/**
	 * stores trades for an account in {user's home directory}/.runelite/flipping/{account's display name}.json
	 *
	 * @param displayName display name of the account the data is associated with
	 * @param data        the trades and last offers of that account
	 * @throws IOException
	 */
	public void writeToFile(String displayName, Object data) throws IOException {
		String accountName = displayName.endsWith(".backup")
			? displayName.substring(0, displayName.length() - ".backup".length()) : displayName;
		if (isAccountProtected(accountName)) {
			throw new IOException("Refusing to overwrite unreadable data for " + accountName
				+ "; restore and load a valid snapshot before saving");
		}
		log.debug("Writing to file for {}", displayName);
		File accountFile = new File(accountDirectory, displayName + ".json");
		File tempFile = new File(accountDirectory, displayName + ".json.tmp");
		
		try (BufferedWriter bufferedWriter = Files.newBufferedWriter(tempFile.toPath(), StandardCharsets.UTF_8);
			JsonWriter jsonWriter = new JsonWriter(bufferedWriter)) {
			writeGson.toJson(data, data.getClass(), jsonWriter);
		} catch (IOException e) {
			try { Files.deleteIfExists(tempFile.toPath()); } catch (IOException ignored) {}
			throw e;
		}
		
		try {
				Files.move(tempFile.toPath(), accountFile.toPath(),
						StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException ame) {
				Files.move(tempFile.toPath(), accountFile.toPath(),
						StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
				try { Files.deleteIfExists(tempFile.toPath()); } catch (IOException ignored) {}
				throw e;
		}
	}

	public static long lastModified(String fileName)
	{
		return new File(PARENT_DIRECTORY, fileName).lastModified();
	}

	public static void deleteFile(String fileName)
	{
		File accountFile = new File(PARENT_DIRECTORY, fileName);
		if (accountFile.exists())
		{
			if (accountFile.delete()) {
				log.debug("{} deleted", fileName);
			} else {
				log.debug("unable to delete {}", fileName);
			}
		}
	}

	/**
	 * Creates a pre-migration backup file for an account before migration.
	 * This should only be called when migration is actually needed.
	 */
	public void createPreMigrationBackup(String displayName) throws IOException {
		File accountFile = new File(accountDirectory, displayName + ".json");
		File backupFile = new File(accountDirectory, displayName + ".json.pre-migration");
		if (!backupFile.exists() && accountFile.exists()) {
			Files.copy(accountFile.toPath(), backupFile.toPath());
			log.info("Created pre-migration backup: {}", backupFile.getName());
		}
	}


	public static void exportToCsv(File file, List<FlippingItem> trades, String startOfIntervalName) throws IOException {
		FileWriter out = new FileWriter(file);
		CSVPrinter csvWriter = new CSVPrinter(out,
				CSVFormat.DEFAULT.
						withHeader("name", "date", "quantity", "price", "state").
						withCommentMarker('#').
						withHeaderComments("Displaying trades for selected time interval: " + startOfIntervalName));

		for (FlippingItem item : trades) {
			for (OfferEvent offer : item.getHistory().getCompressedOfferEvents()) {
				csvWriter.printRecord(
						item.getItemName(),
						TimeFormatters.formatInstantToDate(offer.getTime()),
						offer.getCurrentQuantityInTrade(),
						offer.getPrice(),
						offer.getState()
				);
			}
			csvWriter.printComment(String.format("Total profit: %d", FlippingItem.getProfit(item.getHistory().getCompressedOfferEvents())));
			csvWriter.println();
		}
		csvWriter.close();
	}
}
