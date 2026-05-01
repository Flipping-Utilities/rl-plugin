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

import com.flippingutilities.db.TradePersister;
import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.AccountWideData;
import com.flippingutilities.model.BackupCheckpoints;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
/**
 * Responsible for loading data from disk, handling any operations to access/change data during the plugin's life, and storing
 * data to disk.
 */
@Slf4j
public class DataHandler {
    // SQLite storage backend (optional)
    private com.flippingutilities.db.SqliteStorage sqliteStorage;
    FlippingPlugin plugin;
    private AccountWideData accountWideData;
    private BackupCheckpoints backupCheckpoints;
    private Map<String, AccountData> accountSpecificData = new HashMap<>();
    private boolean accountWideDataChanged = false;
    private Set<String> accountsWithUnsavedChanges = new HashSet<>();
    public String thisClientLastStored;

    public DataHandler(FlippingPlugin plugin) {
        this.plugin = plugin;
    }

    public void setSqliteStorage(com.flippingutilities.db.SqliteStorage storage) {
        this.sqliteStorage = storage;
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
        TradePersister.deleteFile(displayName + ".json");
    }

    public Collection<AccountData> getAllAccountData() {
        accountsWithUnsavedChanges.addAll(accountSpecificData.keySet());
        return accountSpecificData.values();
    }

    public Collection<AccountData> viewAllAccountData() {
        return accountSpecificData.values();
    }

    //TODO this is a weird solution to the problem of having to know whether data changed...
    //TODO change it to something that perhaps takes a snapshot of data at plugin start and compares it to
    //TODO data at logout/plugin shutdown.
    //calls it if data is going to be updated,
    public AccountData getAccountData(String displayName) {
        accountsWithUnsavedChanges.add(displayName);
        return accountSpecificData.get(displayName);
    }

    //is called if account data just needs to be viewed, not updated
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

    public void storeData() {
        log.debug("storing data");
        if (accountsWithUnsavedChanges.size() > 0) {
            log.debug("accounts with unsaved changes are {}. Saving them.", accountsWithUnsavedChanges);
            accountsWithUnsavedChanges.forEach(accountName -> storeAccountData(accountName));
            accountsWithUnsavedChanges.clear();
        }

        if (accountWideDataChanged) {
            log.debug("accountwide data changed, saving it.");
            storeData("accountwide", accountWideData);
            accountWideDataChanged = false;
        }
    }

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

        backupCheckpoints = plugin.tradePersister.fetchBackupCheckpoints();
        accountWideData = fetchAccountWideData();
        plugin.getRecipeHandler().setLocalRecipes(accountWideData.getLocalRecipes());
        accountSpecificData = fetchAndPrepareAllAccountData();
        backupAllAccountData();
        // After the JSON loading completes, optionally route to SQLite backend
        if (plugin.getConfig().dataSource().isSqlite() && sqliteStorage != null) {
            log.info("Using SQLite storage backend");
            // If no accounts were loaded from JSON, load from SQLite
            if (accountSpecificData.isEmpty()) {
                log.info("No JSON data found, loading accounts from SQLite");
                loadAccountsFromSqlite();
            }
        }
    }
    
    private void backupAllAccountData() {
        log.debug("backing up account data");
        boolean backupCheckpointsChanged = false;
        for (String displayName : accountSpecificData.keySet()) {
            AccountData accountData = accountSpecificData.get(displayName);
            //the data could be empty because there was an exception when loading it (such as in fetchAccountData)
            //or perhaps there are legitimately no trades because it is a new file or the user reset their history. In
            //any of these cases, we shouldn't back it up as its useless to backup an empty AccountData and, even worse, 
            //we may overwrite a previous backup with nothing.

            if (!accountData.getTrades().isEmpty() && backupCheckpoints.shouldBackup(displayName, accountData.getLastStoredAt())) {
                try { 
                    plugin.tradePersister.writeToFile(displayName + ".backup", accountData);
                    backupCheckpoints.getAccountToBackupTime().put(displayName, accountData.getLastStoredAt());
                    backupCheckpointsChanged = true;
                }
                catch (Exception e) {
                    log.warn("Couldn't backup account data for {} due to {}", displayName, e);
                }
            }
            else {
                log.debug("Not backing up data for {} as it's empty or it hasn't changed since last backup", displayName);
            }
        }
        if (backupCheckpointsChanged) {
            storeData("backupCheckpoints.special", backupCheckpoints);
        }
    }

    private AccountWideData fetchAccountWideData() {
        try {
            log.debug("Fetching accountwide data");
            AccountWideData accountWideData = plugin.tradePersister.loadAccountWideData();
            boolean didActuallySetDefaults = accountWideData.setDefaults();
            accountWideDataChanged = didActuallySetDefaults;
            return accountWideData;
        }
        catch (Exception e) {
            log.warn("couldn't load accountwide data, setting defaults", e);
            AccountWideData accountWideData = new AccountWideData();
            accountWideData.setDefaults();
            accountWideDataChanged = true;
            return accountWideData;
        }
    }

    private Map<String, AccountData> fetchAndPrepareAllAccountData()
    {
        Map<String, AccountData> accounts = fetchAllAccountData();
        prepareAllAccountData(accounts);
        return accounts;
    }

    private void prepareAllAccountData(Map<String, AccountData> allAccountData) {
        for (String displayName : allAccountData.keySet()) {
            AccountData accountData = allAccountData.get(displayName);
            try {
                accountData.startNewSession();
                accountData.prepareForUse(plugin);
                
                // Check if migration is needed and save immediately
                if (accountData.needsMigration()) {
                    log.info("Migrating account data for {} (version={}, trades={}, recipeFlips={})", 
                        displayName, accountData.getVersion(), accountData.getTrades().size(), accountData.getRecipeFlipGroups().size());
                    TradePersister.createPreMigrationBackup(displayName);
                    accountData.markMigrated();
                    plugin.tradePersister.writeToFile(displayName, accountData);
                    TradePersister.deletePreMigrationBackup(displayName);
                    log.info("Migration complete for {}", displayName);
                }
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

    private Map<String, AccountData> fetchAllAccountData() {
        // SQLite path: try loading from SQLite first
        if (sqliteStorage != null && plugin.getConfig().dataSource().isSqlite()) {
            try {
                Map<String, AccountData> accounts = new HashMap<>();
                for (String displayName : sqliteStorage.listAccounts()) {
                    AccountData data = fetchAccountData(displayName);
                    accounts.put(displayName, data);
                }
                return accounts;
            } catch (Exception e) {
                log.warn("Failed to load from SQLite, falling back to JSON", e);
            }
        }
        try {
            return plugin.tradePersister.loadAllAccounts();
        }
        catch (Exception e) {
            log.warn("error propagated from tradePersister.loadAllAccounts() when fetching all account data, returning empty hashmap", e);
            return new HashMap<>();
        }
    }

    // Used by other components to set accountWideData on DataHandler
    public void loadAccountWideData() {
        accountWideData = fetchAccountWideData();
        plugin.getRecipeHandler().setLocalRecipes(accountWideData.getLocalRecipes());
    }
    
    // Used by other components to set account data on DataHandler
    public void loadAccountData(String displayName) {
        log.info("loading data for {}", displayName);
        accountSpecificData.put(displayName, fetchAccountData(displayName));
    }

    private AccountData fetchAccountData(String displayName)
    {
        // SQLite path: attempt to load from SQLite when configured
        if (sqliteStorage != null && plugin.getConfig().dataSource().isSqlite()) {
            try {
                AccountData accountData = sqliteStorage.loadAccount(displayName);
                accountData.prepareForUse(plugin);
                if (accountData.needsMigration()) {
                    accountData.markMigrated();
                }
                return accountData;
            } catch (Exception e) {
                log.warn("Couldn't load from SQLite for {}, falling back to JSON", displayName, e);
            }
        }
        try {
            AccountData accountData = plugin.tradePersister.loadAccount(displayName);
            accountData.prepareForUse(plugin);
            
            // Check if migration is needed and save immediately
            if (accountData.needsMigration()) {
                log.info("Migrating account data for {} (version={}, trades={}, recipeFlips={})", 
                    displayName, accountData.getVersion(), accountData.getTrades().size(), accountData.getRecipeFlipGroups().size());
                TradePersister.createPreMigrationBackup(displayName);
                accountData.markMigrated();
                plugin.tradePersister.writeToFile(displayName, accountData);
                TradePersister.deletePreMigrationBackup(displayName);
                log.info("Migration complete for {}", displayName);
            }
            
            return accountData;
        }
        catch (Exception e)
        {
            log.warn("couldn't load trades for {}, e = " + e, displayName);
            return new AccountData();
        }
    }

    private void storeAccountData(String displayName)
    {
        try
        {
            AccountData data = accountSpecificData.get(displayName);
            if (data == null)
            {
                log.debug("for an unknown reason the data associated with {} has been set to null. Storing" +
                        "an empty AccountData object instead.", displayName);
                data = new AccountData();
            }
            thisClientLastStored = displayName;
            data.setLastStoredAt(Instant.now());
            plugin.tradePersister.writeToFile(displayName, data);

            if (plugin.getConfig().dataSource().isSqlite() && sqliteStorage != null) {
                try {
                    sqliteStorage.syncAccountData(displayName, data);
                }
                catch (Exception sqliteEx) {
                    log.warn("SQLite sync failed for {} (best-effort): {}", displayName, sqliteEx.getMessage());
                }
            }
        }
        catch (Exception e)
        {
            log.warn("couldn't store trades, error = " + e);
        }
    }

    private void storeData(String fileName, Object data) {
        try {
            plugin.tradePersister.writeToFile(fileName, data);
        }
        catch (Exception e) {
            log.warn("couldn't store data to {} bc of {}",fileName, e);
        }
    }

    /**
     * Load accounts from SQLite when JSON files don't exist.
     * This is used when transitioning to SQLite mode.
     */
    public void loadAccountsFromSqlite() {
        if (sqliteStorage == null) {
            log.warn("SQLite storage not available");
            return;
        }

        List<String> sqliteAccounts = sqliteStorage.listAccounts();
        log.info("Loading {} accounts from SQLite", sqliteAccounts.size());

        for (String displayName : sqliteAccounts) {
            if (!accountSpecificData.containsKey(displayName)) {
                AccountData accountData = fetchAccountData(displayName);
                accountSpecificData.put(displayName, accountData);
                log.info("Loaded account {} from SQLite", displayName);
            }
        }
    }

    public void reloadFromSqlite() {
        if (sqliteStorage == null || !plugin.getConfig().dataSource().isSqlite()) {
            log.debug("Skipping SQLite reload because storage is not enabled");
            return;
        }

        List<String> sqliteAccounts = sqliteStorage.listAccounts();
        Map<String, AccountData> reloadedAccounts = new HashMap<>();
        log.info("Reloading {} accounts from SQLite", sqliteAccounts.size());

        for (String displayName : sqliteAccounts) {
            reloadedAccounts.put(displayName, fetchAccountData(displayName));
        }

        accountSpecificData = reloadedAccounts;
        accountsWithUnsavedChanges.clear();
    }
}
