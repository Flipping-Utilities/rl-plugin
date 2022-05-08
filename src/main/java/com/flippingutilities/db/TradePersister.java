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

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.AccountWideData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.ui.uiutilities.TimeFormatters;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class is responsible for handling all the IO related tasks for persisting trades. This class should contain
 * any logic that pertains to reading/writing to disk. This includes logic related to whether it should reload things
 * again, etc.
 */
@Slf4j
public class TradePersister
{
	Gson gson;

	public TradePersister(Gson gson) {
		this.gson = gson;
	}

	//this is in {user's home directory}/.runelite/flipping
	public static final File PARENT_DIRECTORY = new File(RuneLite.RUNELITE_DIR, "flipping");
	public static final File OLD_FILE = new File(PARENT_DIRECTORY, "trades.json");

	/**
	 * Creates flipping directory if it doesn't exist and partitions trades.json into individual files
	 * for each account, if it exists.
	 *
	 * @throws IOException handled in FlippingPlugin
	 */
	public static void setupFlippingFolder() throws IOException
	{
		if (!PARENT_DIRECTORY.exists())
		{
			log.info("flipping directory doesn't exist yet so it's being created");
			if (!PARENT_DIRECTORY.mkdir())
			{
				throw new IOException("unable to create parent directory!");
			}
		}
		else
		{
			log.info("flipping directory already exists so it's not being created");
			if (OLD_FILE.exists())
			{
				OLD_FILE.delete();

			}
		}
	}

	/**
	 * loads each account's data from the parent directory located at {user's home directory}/.runelite/flipping/
	 * Each account's data is stored in separate file in that directory and is named {displayName}.json
	 *
	 * @return a map of display name to that account's data
	 * @throws IOException handled in FlippingPlugin
	 */
	public Map<String, AccountData> loadAllAccounts()
	{
		Map<String, AccountData> accountsData = new HashMap<>();
		for (File f : PARENT_DIRECTORY.listFiles())
		{
			if (f.getName().equals("accountwide.json") || !f.getName().contains(".json")) {
				log.info("not loading data from file: {}", f.getName());
				continue;
			}
			String displayName = f.getName().split("\\.")[0];
			log.info("loading data for {}", displayName);
			try {
				AccountData accountData = loadFromFile(f);
				if (accountData == null)
				{
					log.warn("data for {} is null for some reason, setting it to a empty AccountData object", displayName);
					accountData = new AccountData();
				}

				accountsData.put(displayName, accountData);
			}
			catch (Exception e) {
				log.warn("Couldn't load data for {}, setting it to empty AccountData object", displayName, e);
				accountsData.put(displayName, new AccountData());
			}
		}

		return accountsData;
	}



	public AccountData loadAccount(String displayName) throws IOException
	{
		log.info("loading data for {}", displayName);
		File accountFile = new File(PARENT_DIRECTORY, displayName + ".json");
		AccountData accountData = loadFromFile(accountFile);
		if (accountData == null)
		{
			log.info("data for {} is null for some reason, setting it to a empty AccountData object", displayName);
			accountData = new AccountData();
		}
		return accountData;
	}

	private AccountData loadFromFile(File f) throws IOException
	{
		String accountDataJson = new String(Files.readAllBytes(f.toPath()));
		Type type = new TypeToken<AccountData>()
		{
		}.getType();
		return gson.fromJson(accountDataJson, AccountData.class);
	}

	public AccountWideData loadAccountWideData() throws IOException {
		File accountFile = new File(PARENT_DIRECTORY, "accountwide.json");
		if (accountFile.exists()){
			String accountWideDataJson = new String(Files.readAllBytes(accountFile.toPath()));
			Type type = new TypeToken<AccountWideData>(){}.getType();
			return gson.fromJson(accountWideDataJson, type);
		}
		else {
			return new AccountWideData();
		}
	}

	/**
	 * stores trades for an account in {user's home directory}/.runelite/flipping/{account's display name}.json
	 *
	 * @param displayName display name of the account the data is associated with
	 * @param data        the trades and last offers of that account
	 * @throws IOException
	 */
	public void storeTrades(String displayName, Object data) throws IOException
	{
		log.info("storing trades for {}", displayName);
		File accountFile = new File(PARENT_DIRECTORY, displayName + ".json");
		final String json = gson.toJson(data);
		Files.write(accountFile.toPath(), json.getBytes());
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
			if (accountFile.delete())
			{
				log.info("{} deleted", fileName);
			}
			else
			{
				log.info("unable to delete {}", fileName);
			}
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
