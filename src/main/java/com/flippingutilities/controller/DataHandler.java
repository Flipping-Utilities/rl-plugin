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
    private final Set<String> accountsAwaitingRecoverySnapshot = new HashSet<>();
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
        if (plugin.isStorageFailed(sqliteStorage)) {
            preserveAccountsForRecovery();
        }
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
        accountsAwaitingRecoverySnapshot.remove(displayName);
        accountsWithUnsavedChanges.remove(displayName);
        TradePersister.deleteFile(displayName + ".json");
    }

    /** Keep cached accounts authoritative until each recovery snapshot is safely written. */
    void preserveAccountsForRecovery() {
        accountsAwaitingRecoverySnapshot.addAll(accountSpecificData.keySet());
        accountsWithUnsavedChanges.addAll(accountSpecificData.keySet());
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

    /** Keep failed snapshots dirty so autosave or shutdown can retry them. */
    public boolean storeData() {
        accountsWithUnsavedChanges.removeIf(this::storeAccountData);
        if (accountWideDataChanged && storeData("accountwide", accountWideData)) {
            accountWideDataChanged = false;
        }
        return accountsWithUnsavedChanges.isEmpty() && !accountWideDataChanged;
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
            this.accountWideData = accountWideData;
            plugin.tradePersister.accountPrepared("accountwide");
            accountWideDataChanged = didActuallySetDefaults;
            return accountWideData;
        }
        catch (Exception e) {
            plugin.tradePersister.protectAccount("accountwide");
            log.warn("Could not prepare account-wide data; JSON saves disabled until valid data is loaded", e);
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
                accountSpecificData.put(displayName, accountData);
                plugin.tradePersister.accountPrepared(displayName);

                // Check if migration is needed and save immediately
                if (accountData.needsMigration()) {
                    log.info("Migrating account data for {} (version={}, trades={}, recipeFlips={})", 
                        displayName, accountData.getVersion(), accountData.getTrades().size(), accountData.getRecipeFlipGroups().size());
                    plugin.tradePersister.createPreMigrationBackup(displayName);
                    accountData.markMigrated();
                    plugin.tradePersister.writeToFile(displayName, accountData);
                    log.info("Migration complete for {}", displayName);
                }
            }
            catch (Exception | OutOfMemoryError e) {
                plugin.tradePersister.protectAccount(displayName);
                log.error("Could not prepare {}; JSON saves and backups are disabled until valid data is loaded", displayName, e);
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
                    // guard against junk rows named after the pseudo account-wide view
                    if (displayName.equalsIgnoreCase(FlippingPlugin.ACCOUNT_WIDE)) {
                        continue;
                    }
                    AccountData data = sqliteStorage.loadAccount(displayName);
                    if (data != null) {
                        accounts.put(displayName, data);
                    }
                }
                if (!accounts.isEmpty()) {
                    return accounts;
                }
                // Empty DB: fall through to JSON so a fresh SQLite install can still bootstrap.
            } catch (Exception e) {
                handleSqliteReadFailure(e);
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

    private void handleSqliteReadFailure(Exception failure) {
        com.flippingutilities.db.SqliteStorage failed = sqliteStorage;
        sqliteStorage = null;
        preserveAccountsForRecovery();
        plugin.recoverFromStorageFailure(failed, failure);
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
        // A write can fail before its queued client-thread recovery callback runs.
        if (plugin.isStorageFailed(sqliteStorage)) {
            setSqliteStorage(null);
        }
        if (accountsAwaitingRecoverySnapshot.contains(displayName)
                && accountSpecificData.containsKey(displayName)) {
            return accountSpecificData.get(displayName);
        }
        // SQLite path: attempt to load from SQLite when configured
        if (sqliteStorage != null && plugin.getConfig().dataSource().isSqlite()) {
            try {
                AccountData accountData = sqliteStorage.loadAccount(displayName);
                if (accountData != null) {
                    accountData.prepareForUse(plugin);
                    if (accountData.needsMigration()) {
                        accountData.markMigrated();
                    }
                    return accountData;
                }
                // SQLite returned null (account not found); fall through to JSON.
            } catch (Exception e) {
                handleSqliteReadFailure(e);
                if (accountSpecificData.containsKey(displayName)) {
                    return accountSpecificData.get(displayName);
                }
            }
        }
        try {
            AccountData accountData = plugin.tradePersister.loadAccount(displayName);
            accountData.prepareForUse(plugin);
            accountSpecificData.put(displayName, accountData);
            plugin.tradePersister.accountPrepared(displayName);

            // Check if migration is needed and save immediately
            if (accountData.needsMigration()) {
                log.info("Migrating account data for {} (version={}, trades={}, recipeFlips={})", 
                    displayName, accountData.getVersion(), accountData.getTrades().size(), accountData.getRecipeFlipGroups().size());
                plugin.tradePersister.createPreMigrationBackup(displayName);
                accountData.markMigrated();
                plugin.tradePersister.writeToFile(displayName, accountData);
                log.info("Migration complete for {}", displayName);
            }
            
            return accountData;
        }
        catch (Exception | OutOfMemoryError e)
        {
            plugin.tradePersister.protectAccount(displayName);
            log.error("Could not load {}; keeping cached data and disabling JSON saves until valid data is loaded", displayName, e);
            return accountSpecificData.getOrDefault(displayName, new AccountData());
        }
    }

    private boolean storeAccountData(String displayName) {
        // The account-wide view and deleted accounts must never become JSON accounts.
        AccountData data = accountSpecificData.get(displayName);
        if (displayName == null || displayName.equalsIgnoreCase(FlippingPlugin.ACCOUNT_WIDE) || data == null) {
            return true;
        }
        thisClientLastStored = displayName;
        data.setLastStoredAt(Instant.now());
        boolean stored = storeData(displayName, data);
        if (stored) {
            accountsAwaitingRecoverySnapshot.remove(displayName);
        }
        return stored;
    }

    private boolean storeData(String fileName, Object data) {
        try {
            plugin.tradePersister.writeToFile(fileName, data);
            return true;
        } catch (Exception e) {
            log.warn("Couldn't store data to {}; retaining it for retry", fileName, e);
            return false;
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
            if (displayName.equalsIgnoreCase(FlippingPlugin.ACCOUNT_WIDE)) {
                continue;
            }
            reloadedAccounts.put(displayName, fetchAccountData(displayName));
        }

        // Carry over in-memory accounts ONLY while their migration has not actually
        // completed. Once migration_completed is set, SQLite reflects the authoritative
        // import; carrying over in-memory accounts then (e.g. one read from the pre-wipe
        // database during a backend switch) would resurrect accounts the user deleted.
        boolean migrationCompleted = "true".equalsIgnoreCase(sqliteStorage.getSetting("migration_completed"));
        if (!migrationCompleted) {
            for (Map.Entry<String, AccountData> entry : accountSpecificData.entrySet()) {
                if (sqliteStorage.getSetting("migrated_" + entry.getKey()) == null) {
                    reloadedAccounts.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        }

        accountSpecificData = reloadedAccounts;
        accountsWithUnsavedChanges.clear();
    }
}
