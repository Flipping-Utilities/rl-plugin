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

package com.flippingutilities.controller;

import com.flippingutilities.db.JsonToSqliteMigrator;
import com.flippingutilities.db.SqliteDataStore;
import com.flippingutilities.db.TradePersister;
import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.AccountWideData;
import com.flippingutilities.model.BackupCheckpoints;
import com.flippingutilities.model.OfferEvent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;

/**
 * Responsible for loading data from disk, handling any operations to access/change data
 * during the plugin's life, and storing data to disk.
 *
 * MODIFIED: Now uses SQLite as the primary storage backend instead of JSON files.
 * On first run, existing JSON data is automatically migrated to SQLite.
 * Individual trades are saved instantly (no more waiting for a full save cycle).
 */
@Slf4j
public class DataHandler {
    FlippingPlugin plugin;
    private AccountWideData accountWideData;
    private BackupCheckpoints backupCheckpoints;
    private Map<String, AccountData> accountSpecificData = new HashMap<>();
    private boolean accountWideDataChanged = false;
    private Set<String> accountsWithUnsavedChanges = new HashSet<>();
    public String thisClientLastStored;

    // The SQLite database — replaces JSON file storage
    @Getter
    private SqliteDataStore sqliteStore;
    private boolean usingSqlite = false;

    public DataHandler(FlippingPlugin plugin) {
        this.plugin = plugin;
    }

    public AccountWideData viewAccountWideData() {
        return accountWideData;
    }

    public AccountWideData getAccountWideData() {
        accountWideDataChanged = true;
        return accountWideData;
    }

    public void addAccount(String displayName) {
        log.info("adding {} to data handler", displayName);
        AccountData accountData = new AccountData();
        accountData.prepareForUse(plugin);
        accountSpecificData.put(displayName, accountData);
    }

    public void deleteAccount(String displayName) {
        log.info("deleting account: {}", displayName);
        accountSpecificData.remove(displayName);
        if (usingSqlite) {
            try {
                sqliteStore.deleteAccount(displayName);
            } catch (Exception e) {
                log.warn("Failed to delete account from SQLite: {}", e.getMessage());
            }
        } else {
            TradePersister.deleteFile(displayName + ".json");
        }
    }

    public Collection<AccountData> getAllAccountData() {
        accountsWithUnsavedChanges.addAll(accountSpecificData.keySet());
        return accountSpecificData.values();
    }

    public Collection<AccountData> viewAllAccountData() {
        return accountSpecificData.values();
    }

    public AccountData getAccountData(String displayName) {
        accountsWithUnsavedChanges.add(displayName);
        return accountSpecificData.get(displayName);
    }

    public AccountData viewAccountData(String displayName) {
        return accountSpecificData.get(displayName);
    }

    public Set<String> getCurrentAccounts() {
        return accountSpecificData.keySet();
    }

    public void markDataAsHavingChanged(String displayName) {
        if (displayName.equals(FlippingPlugin.ACCOUNT_WIDE)) {
            accountWideDataChanged = true;
        }
        else {
            accountsWithUnsavedChanges.add(displayName);
        }
    }

    /**
     * Saves all pending changes to disk.
     * With SQLite, individual offers are already saved instantly (via saveOfferEventIncrementally),
     * but this handles metadata like session time and account-wide settings.
     */
    public void storeData() {
        log.debug("storing data");
        if (usingSqlite) {
            storeDataSqlite();
        } else {
            storeDataJson();
        }
    }

    private void storeDataSqlite() {
        if (accountsWithUnsavedChanges.size() > 0) {
            log.debug("SQLite: saving {} accounts with changes", accountsWithUnsavedChanges.size());
            accountsWithUnsavedChanges.forEach(this::storeAccountDataSqlite);
            accountsWithUnsavedChanges.clear();
        }
        if (accountWideDataChanged) {
            log.debug("SQLite: saving accountwide data");
            try {
                sqliteStore.saveAccountWideData(accountWideData);
            } catch (Exception e) {
                log.warn("Failed to save accountwide data to SQLite: {}", e.getMessage());
            }
            accountWideDataChanged = false;
        }
    }

    private void storeDataJson() {
        if (accountsWithUnsavedChanges.size() > 0) {
            log.debug("accounts with unsaved changes are {}. Saving them.", accountsWithUnsavedChanges);
            accountsWithUnsavedChanges.forEach(this::storeAccountDataJson);
            accountsWithUnsavedChanges.clear();
        }
        if (accountWideDataChanged) {
            log.debug("accountwide data changed, saving it.");
            storeJsonFile("accountwide", accountWideData);
            accountWideDataChanged = false;
        }
    }

    /**
     * THE KEY NEW METHOD: Saves a single offer event to SQLite immediately.
     *
     * Called every time a GE offer comes in. The trade is permanently stored
     * in milliseconds — even if RuneLite crashes right after, the data is safe.
     * This is what prevents the data loss that plagued the old JSON system.
     */
    public void saveOfferEventIncrementally(String displayName, int itemId, String itemName,
                                             int geLimit, String flippedBy, OfferEvent offer) {
        if (!usingSqlite) {
            // Not using SQLite yet, will be saved during normal storeData() cycle
            accountsWithUnsavedChanges.add(displayName);
            return;
        }
        try {
            sqliteStore.saveOfferEvent(displayName, itemId, itemName, geLimit, flippedBy, offer);
        } catch (Exception e) {
            log.warn("Failed to incrementally save offer to SQLite, will retry during full save: {}", e.getMessage());
            accountsWithUnsavedChanges.add(displayName);
        }
    }

    /**
     * Main data loading method. Called once at startup.
     * Handles three scenarios:
     * 1. SQLite database already exists → load from it
     * 2. JSON files exist but no database → migrate JSON to SQLite, then load
     * 3. Nothing exists → create fresh empty database
     */
    public void loadData() {
        log.debug("Loading data on startup");
        try {
            TradePersister.setupFlippingFolder();
        }
        catch (Exception e) {
            log.warn("Couldn't set up flipping folder, setting defaults", e);
            accountWideData = new AccountWideData();
            accountWideData.setDefaults();
            accountSpecificData = new HashMap<>();
            accountWideDataChanged = true;
            plugin.getRecipeHandler().setLocalRecipes(accountWideData.getLocalRecipes());
            return;
        }

        // Try to initialize SQLite
        sqliteStore = new SqliteDataStore(TradePersister.PARENT_DIRECTORY, plugin.gson);
        boolean dbExisted = sqliteStore.databaseExists();

        try {
            sqliteStore.initialize();

            if (!dbExisted) {
                // Database is brand new — check if we need to migrate from JSON
                Map<String, AccountData> jsonAccounts = plugin.tradePersister.loadAllAccounts();
                if (!jsonAccounts.isEmpty()) {
                    log.info("Found existing JSON data, migrating to SQLite...");
                    JsonToSqliteMigrator migrator = new JsonToSqliteMigrator(plugin.tradePersister, sqliteStore);
                    if (!migrator.migrate()) {
                        log.warn("Migration failed, falling back to JSON storage");
                        sqliteStore.close();
                        fallbackToJson();
                        return;
                    }
                }
            }

            // Load from SQLite
            usingSqlite = true;
            accountWideData = fetchAccountWideDataSqlite();
            plugin.getRecipeHandler().setLocalRecipes(accountWideData.getLocalRecipes());
            accountSpecificData = fetchAndPrepareAllAccountDataSqlite();
            log.info("Loaded data from SQLite: {} accounts", accountSpecificData.size());

        } catch (Exception e) {
            log.error("Failed to initialize SQLite, falling back to JSON: {}", e.getMessage(), e);
            if (sqliteStore != null) sqliteStore.close();
            fallbackToJson();
        }
    }

    /**
     * Falls back to the original JSON storage if SQLite fails.
     */
    private void fallbackToJson() {
        usingSqlite = false;
        backupCheckpoints = plugin.tradePersister.fetchBackupCheckpoints();
        accountWideData = fetchAccountWideDataJson();
        plugin.getRecipeHandler().setLocalRecipes(accountWideData.getLocalRecipes());
        accountSpecificData = fetchAndPrepareAllAccountDataJson();
        backupAllAccountData();
    }

    /**
     * Shuts down the SQLite connection cleanly.
     */
    public void shutdown() {
        if (sqliteStore != null && usingSqlite) {
            sqliteStore.close();
        }
    }

    public boolean isUsingSqlite() {
        return usingSqlite;
    }

    // ==================== SQLite data loading ====================

    private AccountWideData fetchAccountWideDataSqlite() {
        try {
            AccountWideData data = sqliteStore.loadAccountWideData();
            boolean didSetDefaults = data.setDefaults();
            accountWideDataChanged = didSetDefaults;
            return data;
        } catch (Exception e) {
            log.warn("Couldn't load accountwide data from SQLite, using defaults", e);
            AccountWideData data = new AccountWideData();
            data.setDefaults();
            accountWideDataChanged = true;
            return data;
        }
    }

    private Map<String, AccountData> fetchAndPrepareAllAccountDataSqlite() {
        try {
            Map<String, AccountData> accounts = sqliteStore.loadAllAccounts();
            prepareAllAccountData(accounts);
            return accounts;
        } catch (Exception e) {
            log.warn("Failed to load accounts from SQLite: {}", e.getMessage(), e);
            return new HashMap<>();
        }
    }

    // ==================== JSON fallback data loading ====================

    private AccountWideData fetchAccountWideDataJson() {
        try {
            log.debug("Fetching accountwide data from JSON");
            AccountWideData data = plugin.tradePersister.loadAccountWideData();
            boolean didSetDefaults = data.setDefaults();
            accountWideDataChanged = didSetDefaults;
            return data;
        } catch (Exception e) {
            log.warn("Couldn't load accountwide data from JSON, setting defaults", e);
            AccountWideData data = new AccountWideData();
            data.setDefaults();
            accountWideDataChanged = true;
            return data;
        }
    }

    private Map<String, AccountData> fetchAndPrepareAllAccountDataJson() {
        try {
            Map<String, AccountData> accounts = plugin.tradePersister.loadAllAccounts();
            prepareAllAccountData(accounts);
            return accounts;
        } catch (Exception e) {
            log.warn("Error loading all account data from JSON, returning empty", e);
            return new HashMap<>();
        }
    }

    // ==================== Shared preparation logic ====================

    private void prepareAllAccountData(Map<String, AccountData> allAccountData) {
        for (String displayName : allAccountData.keySet()) {
            AccountData accountData = allAccountData.get(displayName);
            try {
                accountData.startNewSession();
                accountData.prepareForUse(plugin);
            }
            catch (Exception e) {
                log.warn("Couldn't prepare account data for {} due to {}, setting default", displayName, e);
                AccountData newAccountData = new AccountData();
                newAccountData.startNewSession();
                newAccountData.prepareForUse(plugin);
                allAccountData.put(displayName, newAccountData);
            }
        }
    }

    // ==================== Saving ====================

    private void storeAccountDataSqlite(String displayName) {
        try {
            AccountData data = accountSpecificData.get(displayName);
            if (data == null) {
                log.debug("Data for {} is null, saving empty AccountData", displayName);
                data = new AccountData();
            }
            thisClientLastStored = displayName;
            data.setLastStoredAt(Instant.now());
            sqliteStore.saveAccount(displayName, data);
        } catch (Exception e) {
            log.warn("Failed to save account {} to SQLite: {}", displayName, e.getMessage());
        }
    }

    private void storeAccountDataJson(String displayName) {
        try {
            AccountData data = accountSpecificData.get(displayName);
            if (data == null) {
                log.debug("for an unknown reason the data associated with {} has been set to null. Storing" +
                        "an empty AccountData object instead.", displayName);
                data = new AccountData();
            }
            thisClientLastStored = displayName;
            data.setLastStoredAt(Instant.now());
            plugin.tradePersister.writeToFile(displayName, data);
        } catch (Exception e) {
            log.warn("couldn't store trades, error = " + e);
        }
    }

    private void storeJsonFile(String fileName, Object data) {
        try {
            plugin.tradePersister.writeToFile(fileName, data);
        } catch (Exception e) {
            log.warn("couldn't store data to {} bc of {}", fileName, e);
        }
    }

    // Used by other components to reload accountWideData
    public void loadAccountWideData() {
        if (usingSqlite) {
            accountWideData = fetchAccountWideDataSqlite();
        } else {
            accountWideData = fetchAccountWideDataJson();
        }
        plugin.getRecipeHandler().setLocalRecipes(accountWideData.getLocalRecipes());
    }

    // Used by other components to reload a single account
    public void loadAccountData(String displayName) {
        log.info("loading data for {}", displayName);
        // For SQLite, we already have the data loaded. For JSON, re-read from file.
        if (!usingSqlite) {
            try {
                AccountData accountData = plugin.tradePersister.loadAccount(displayName);
                accountData.prepareForUse(plugin);
                accountSpecificData.put(displayName, accountData);
            } catch (Exception e) {
                log.warn("couldn't load trades for {}: {}", displayName, e.getMessage());
                accountSpecificData.put(displayName, new AccountData());
            }
        }
    }

    private void backupAllAccountData() {
        if (usingSqlite) return; // SQLite doesn't need JSON-style backups

        log.debug("backing up account data");
        boolean backupCheckpointsChanged = false;
        for (String displayName : accountSpecificData.keySet()) {
            AccountData accountData = accountSpecificData.get(displayName);
            if (!accountData.getTrades().isEmpty() && backupCheckpoints.shouldBackup(displayName, accountData.getLastStoredAt())) {
                try {
                    plugin.tradePersister.writeToFile(displayName + ".backup", accountData);
                    backupCheckpoints.getAccountToBackupTime().put(displayName, accountData.getLastStoredAt());
                    backupCheckpointsChanged = true;
                } catch (Exception e) {
                    log.warn("Couldn't backup account data for {} due to {}", displayName, e);
                }
            }
        }
        if (backupCheckpointsChanged) {
            storeJsonFile("backupCheckpoints.special", backupCheckpoints);
        }
    }
}
