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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Iterator;
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
		".backup.json.tmp", ".json.pre-migration", ".backup.json", ".json.tmp", ".json"
	};

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

	/**
	 * Creates the plugin data directory, migrates unsupported filenames, and removes the obsolete combined file.
	 *
	 * @throws IOException handled in FlippingPlugin
	 */
	public void setupFlippingFolder() throws IOException
	{
		directory.createDirectories();
		Map<Filepath, Filepath> moves = new LinkedHashMap<>();
		try (Stream<Filepath> files = directory.walk(1)) {
			Iterator<Filepath> iterator = files.iterator();
			while (iterator.hasNext()) {
				Filepath file = iterator.next();
				if (!file.isFile()) {
					continue;
				}
				for (String suffix : ACCOUNT_FILE_SUFFIXES) {
					String name = file.getFileName();
					if (!name.endsWith(suffix)) {
						continue;
					}
					String stem = name.substring(0, name.length() - suffix.length());
					String accountName = accountNameFromFileName(stem + ".json");
					Filepath target = accountFile(directory, accountName, suffix);
					if (!file.equals(target)) {
						if (target.exists() || moves.containsValue(target)) {
							throw new IOException("Cannot migrate " + name + ": " + target.getFileName()
								+ " already exists. Existing files have not been overwritten.");
						}
						moves.put(file, target);
					}
					break;
				}
			}
		}
		for (Map.Entry<Filepath, Filepath> move : moves.entrySet()) {
			// No replacement: an existing destination must never be overwritten during migration.
			move.getKey().moveTo(move.getValue());
		}
		directory.joinSegment("trades.json").deleteIfExists();
	}

	private static Filepath accountFile(Filepath directory, String accountName, String suffix) {
		if (accountName.contains("/") || accountName.contains("\\")) {
			throw new IllegalArgumentException("Account names cannot contain path separators");
		}
		if (!accountName.startsWith("@")) {
			try {
				return directory.joinSegment(accountName + suffix);
			} catch (IllegalArgumentException ignored) {
				// Legitimate names such as Con are reserved filenames on Windows.
			}
		}
		return directory.joinSegment("@" + ACCOUNT_NAME_ENCODING.encode(accountName.getBytes(StandardCharsets.UTF_8)) + suffix);
	}

	/** Returns the display name for a committed account filename, including portable encoded names. */
	public static String accountNameFromFileName(String fileName) {
		if (!fileName.endsWith(".json")) {
			throw new IllegalArgumentException("Not an account JSON filename: " + fileName);
		}
		String name = fileName.substring(0, fileName.length() - ".json".length());
		if (name.startsWith("@") && ACCOUNT_NAME_ENCODING.canDecode(name.substring(1))) {
			return new String(ACCOUNT_NAME_ENCODING.decode(name.substring(1)), StandardCharsets.UTF_8);
		}
		return name;
	}

	/**
	 * Loads each account's data from the plugin data directory.
	 * Each account's data is stored in separate file in that directory and is named {displayName}.json
	 *
	 * Each account is loaded through loadAccount so backup recovery remains available.
	 *
	 * @return a map of display name to that account's data
	 * @throws IOException handled in FlippingPlugin
	 */
	public Map<String, AccountData> loadAllAccounts() throws IOException
	{
		Map<String, AccountData> accountsData = new HashMap<>();
		try (Stream<Filepath> files = directory.walk(1)) {
			Iterator<Filepath> iterator = files.iterator();
			while (iterator.hasNext()) {
				Filepath file = iterator.next();
				String name = file.getFileName();
				if (!file.isFile() || name.equals("accountwide.json") || !name.endsWith(".json")
					|| name.endsWith(".backup.json") || name.endsWith(".special.json")) {
					continue;
				}
				String displayName = accountNameFromFileName(name);
				accountsData.put(displayName, loadAccount(displayName));
			}
		}

		return accountsData;
	}

	//anything that wants to load an account's data MUST go through this method as it handles various cases such as
	//loading from backups
	public AccountData loadAccount(String displayName)
	{
		log.debug("loading data for {}", displayName);
		try {
			Filepath accountFile = accountFile(directory, displayName, ".json");
			AccountData accountData = loadFromFile(accountFile);
			if (accountData == null)
			{
				log.warn("data for {} is null for some reason. Will try loading from backup", displayName);
				accountData = loadAccountFromBackup(displayName);
			}
			return accountData;
		}
    catch (OutOfMemoryError e) {
        log.error("OutOfMemoryError while loading data for {}. File may be too large. Returning empty AccountData.", displayName);
        return new AccountData();
    }
    catch (Exception e) {
        log.warn("Got exception {} while loading data for {}. Will try loading from backup", e, displayName);
        return loadAccountFromBackup(displayName);
    }
	}

	private AccountData loadAccountFromBackup(String displayName) {
		log.debug("loading data for {} from backup", displayName);
		try {
			Filepath accountFile = accountFile(directory, displayName, ".backup.json");
			if (!accountFile.exists()) {
				log.debug("backup for {} does not exist, returning empty AccountData", displayName);
				return new AccountData();
			}
			AccountData accountData = loadFromFile(accountFile);
			if (accountData == null) {
				log.debug("data loaded from backup for {} is null for some reason, returning an empty AccountData object", displayName);
				accountData = new AccountData();
			}
			return accountData;
		}
		catch (Exception e) {
			log.debug("Couldn't load data for {} from backup due to {}", displayName, e);
			return new AccountData();
		}
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

	/**
	 * Stores trades in the plugin data directory as {account's display name}.json.
	 *
	 * @param displayName display name of the account the data is associated with
	 * @param data        the trades and last offers of that account
	 * @throws IOException
	 */
	public void writeToFile(String displayName, Object data) throws IOException {
		writeToFile(displayName, data, ".json");
	}

	public void writeBackup(String displayName, Object data) throws IOException {
		writeToFile(displayName, data, ".backup.json");
	}

	private void writeToFile(String displayName, Object data, String suffix) throws IOException {
		log.debug("Writing to file for {}", displayName);
		Filepath accountFile = accountFile(directory, displayName, suffix);
		Filepath tempFile = accountFile(directory, displayName, suffix + ".tmp");
		
		try (BufferedWriter bufferedWriter = tempFile.openBufferedWriter();
			JsonWriter jsonWriter = new JsonWriter(bufferedWriter)) {
			writeGson.toJson(data, data.getClass(), jsonWriter);
		} catch (IOException e) {
			try { tempFile.deleteIfExists(); } catch (IOException ignored) {}
			throw e;
		}
		
		try {
			try {
				tempFile.moveTo(accountFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException ame) {
				tempFile.moveTo(accountFile, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
				try { tempFile.deleteIfExists(); } catch (IOException ignored) {}
				throw e;
		}
	}

	public void deleteAccount(String displayName)
	{
		try {
			accountFile(directory, displayName, ".json").deleteIfExists();
			log.debug("{} deleted", displayName);
		} catch (IOException e) {
			log.debug("unable to delete {}", displayName, e);
		}
	}

	/**
	 * Creates a pre-migration backup file for an account before migration.
	 * This should only be called when migration is actually needed.
	 */
	public void createPreMigrationBackup(String displayName) throws IOException {
		Filepath accountFile = accountFile(directory, displayName, ".json");
		Filepath backupFile = accountFile(directory, displayName, ".json.pre-migration");
		if (!backupFile.exists() && accountFile.exists()) {
			accountFile.copyTo(backupFile);
			log.info("Created pre-migration backup: {}", backupFile.getFileName());
		}
	}

	/**
	 * Deletes the pre-migration backup file for an account after successful migration.
	 * This should be called after the account data has been successfully saved in the new format.
	 */
	public void deletePreMigrationBackup(String displayName) {
		String backupFileName = displayName + ".json.pre-migration";
		try {
			accountFile(directory, displayName, ".json.pre-migration").deleteIfExists();
			log.info("Deleted pre-migration backup: {}", backupFileName);
		} catch (IOException e) {
			log.warn("Failed to delete pre-migration backup: {}", backupFileName, e);
		}
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
