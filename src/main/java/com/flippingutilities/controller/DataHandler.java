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

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.AccountWideData;
import com.flippingutilities.model.BackupCheckpoints;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * Responsible for loading data from disk, handling any operations to access/change data during the plugin's life, and storing
 * data to disk.
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
        accountData.setDisplayName(displayName);
        accountData.prepareForUse(plugin);
        accountSpecificData.put(displayName, accountData);
        plugin.tradePersister.setAccountIndex(accountSpecificData);
    }

    public void deleteAccount(String displayName) {
        log.info("deleting account: {}", displayName);
        plugin.tradePersister.deleteAccount(displayName);
        accountSpecificData.remove(displayName);
        accountsWithUnsavedChanges.remove(displayName);
        plugin.tradePersister.setAccountIndex(accountSpecificData);
    }

    /** Binds a verified logged-in character to its existing history before accepting offers. */
    public String bindLoggedInAccount(String accountId, String displayName) throws IOException {
        AccountData account = accountSpecificData.values().stream()
            .filter(data -> accountId.equals(data.getAccountId())).findFirst().orElse(null);
        if (account == null) {
            account = accountSpecificData.values().stream()
                .filter(data -> data.getAccountId() == null && displayName.equals(data.getDisplayName()))
                .findFirst().orElse(null);
        }
        String oldKey = getAccountKey(account);
        String oldName = account == null ? null : account.getDisplayName();
        if (oldKey == null || !accountsWithUnsavedChanges.contains(oldKey)) {
            // A different client may have created or updated history before its polling event arrived.
            AccountData disk = plugin.tradePersister.loadAccountById(accountId);
            if (disk == null && account != null && account.getStorageFileName() != null) {
                disk = plugin.tradePersister.loadAccountFile(account.getStorageFileName());
                if (disk == null) {
                    throw new IOException("Account history moved before login; wait for its reload");
                }
            }
            if (disk != null) {
                if (disk.isPersistenceReadFailed()) {
                    throw new IOException("Cannot bind unreadable account history");
                }
                disk.prepareForUse(plugin);
                account = disk;
                if (oldName == null) {
                    oldName = disk.getDisplayName();
                }
            }
        }
        if (account == null) {
            account = new AccountData();
            account.prepareForUse(plugin);
        }
        plugin.tradePersister.bindAccount(accountId, displayName, account);
        if (oldKey != null) {
            accountSpecificData.remove(oldKey);
        }
        // Keep a distinct temporary key until all UI names are reconciled below.
        String insertionKey = "account:" + accountId;
        accountSpecificData.put(insertionKey, account);
        if (oldKey != null && accountsWithUnsavedChanges.remove(oldKey)) {
            accountsWithUnsavedChanges.add(insertionKey);
        }
        rebuildAccountKeys();
        String key = getAccountKey(account);
        accountsWithUnsavedChanges.add(key);
        if (backupCheckpoints != null && oldName != null) {
            Instant checkpoint = backupCheckpoints.getAccountToBackupTime().remove(oldName);
            if (checkpoint != null) {
                backupCheckpoints.getAccountToBackupTime().putIfAbsent("id:" + accountId, checkpoint);
                storeData("backupcheckpoints.special", backupCheckpoints);
            }
        }
        return key;
    }

    /** Resolves a view after a name change without confusing characters that reused an RSN. */
    public String getAccountKey(AccountData account) {
        if (account == null) {
            return null;
        }
        for (Map.Entry<String, AccountData> entry : accountSpecificData.entrySet()) {
            if (entry.getValue() == account || sameAccount(entry.getValue(), account)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static boolean sameAccount(AccountData first, AccountData second) {
        if (first.getAccountId() != null || second.getAccountId() != null) {
            return first.getAccountId() != null && first.getAccountId().equals(second.getAccountId());
        }
        return Objects.equals(first.getDisplayName(), second.getDisplayName());
    }

    private void rebuildAccountKeys() {
        Map<String, Integer> nameCounts = new HashMap<>();
        accountSpecificData.forEach((key, data) -> {
            if (data.getDisplayName() == null) {
                data.setDisplayName(key);
            }
            nameCounts.merge(data.getDisplayName(), 1, Integer::sum);
        });
        Map<String, AccountData> renamed = new HashMap<>();
        Set<String> renamedDirty = new HashSet<>();
        accountSpecificData.forEach((oldKey, data) -> {
            String key = data.getDisplayName();
            if (nameCounts.get(key) > 1 || FlippingPlugin.ACCOUNT_WIDE.equals(key)) {
                key += " [" + (data.getAccountId() == null ? "legacy" : data.getAccountId()) + "]";
            }
            renamed.put(key, data);
            if (accountsWithUnsavedChanges.contains(oldKey)) {
                renamedDirty.add(key);
            }
            data.renameAccount(key);
        });
        accountSpecificData = renamed;
        accountsWithUnsavedChanges = renamedDirty;
        plugin.tradePersister.setAccountIndex(accountSpecificData);
    }

    /** Reloads a committed filename; a delayed event for a vanished file must never create empty data. */
    public String loadAccountFile(String fileName) {
        try {
            AccountData loaded = plugin.tradePersister.loadAccountFile(fileName);
            if (loaded == null || loaded.isPersistenceReadFailed()) {
                return null;
            }
            String oldKey = getAccountKey(loaded);
            if (oldKey == null && loaded.getAccountId() != null) {
                for (Map.Entry<String, AccountData> entry : accountSpecificData.entrySet()) {
                    AccountData legacy = entry.getValue();
                    if (legacy.getAccountId() == null && loaded.getDisplayName().equals(legacy.getDisplayName())
                        && !plugin.tradePersister.accountFileExists(legacy)) {
                        oldKey = entry.getKey();
                        break;
                    }
                }
            }
            AccountData existing = oldKey == null ? null : accountSpecificData.get(oldKey);
            if (existing != null && existing.getAccountId() == null && loaded.getAccountId() != null) {
                // Preserve references held by the currently selected view across a legacy migration.
                existing.setAccountId(loaded.getAccountId());
            }
            if (existing != null && accountsWithUnsavedChanges.contains(oldKey)) {
                // A different client may have renamed the character while this client still has edits.
                existing.setDisplayName(loaded.getDisplayName());
                existing.setStorageFileName(loaded.getStorageFileName());
                loaded = existing;
            } else {
                loaded.prepareForUse(plugin);
            }
            if (oldKey != null) {
                accountSpecificData.put(oldKey, loaded);
            } else {
                accountSpecificData.put("file:" + fileName, loaded);
            }
            rebuildAccountKeys();
            return getAccountKey(loaded);
        } catch (Exception e) {
            log.warn("Could not reload account file {}; keeping cached data", fileName, e);
            return null;
        }
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
            for (String accountName : new HashSet<>(accountsWithUnsavedChanges)) {
                if (storeAccountData(accountName)) {
                    accountsWithUnsavedChanges.remove(accountName);
                }
            }
        }

        if (accountWideDataChanged) {
            log.debug("accountwide data changed, saving it.");
            storeData("accountwide", accountWideData);
            accountWideDataChanged = false;
        }
    }

    public void loadData() {
        log.debug("Loading data on startup");
        backupCheckpoints = plugin.tradePersister.fetchBackupCheckpoints();
        accountWideData = fetchAccountWideData();
        plugin.getRecipeHandler().setLocalRecipes(accountWideData.getLocalRecipes());
        accountSpecificData = fetchAndPrepareAllAccountData();
        rebuildAccountKeys();
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

            String checkpointKey = accountData.getAccountId() == null ? accountData.getDisplayName() : "id:" + accountData.getAccountId();
            if (!accountData.getTrades().isEmpty() && backupCheckpoints.shouldBackup(checkpointKey, accountData.getLastStoredAt())) {
                try { 
                    plugin.tradePersister.writeBackup(displayName, accountData);
                    backupCheckpoints.getAccountToBackupTime().put(checkpointKey, accountData.getLastStoredAt());
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
            storeData("backupcheckpoints.special", backupCheckpoints);
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
                    plugin.tradePersister.createPreMigrationBackup(displayName);
                    accountData.markMigrated();
                    plugin.tradePersister.writeToFile(displayName, accountData);
                    plugin.tradePersister.deletePreMigrationBackup(displayName);
                    log.info("Migration complete for {}", displayName);
                }
            }
            catch (Exception e) {
                log.warn("Couldn't prepare account data for {}; preserving its existing history", displayName, e);
                accountData.setPersistenceReadFailed(true);
            }
        }
    }

    private Map<String, AccountData> fetchAllAccountData() {
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
        rebuildAccountKeys();
    }

    private AccountData fetchAccountData(String displayName)
    {
        try {
            AccountData accountData = plugin.tradePersister.loadAccount(displayName);
            accountData.prepareForUse(plugin);
            
            // Check if migration is needed and save immediately
            if (accountData.needsMigration()) {
                log.info("Migrating account data for {} (version={}, trades={}, recipeFlips={})", 
                    displayName, accountData.getVersion(), accountData.getTrades().size(), accountData.getRecipeFlipGroups().size());
                plugin.tradePersister.createPreMigrationBackup(displayName);
                accountData.markMigrated();
                plugin.tradePersister.writeToFile(displayName, accountData);
                plugin.tradePersister.deletePreMigrationBackup(displayName);
                log.info("Migration complete for {}", displayName);
            }
            
            return accountData;
        }
        catch (Exception e)
        {
            log.warn("couldn't load trades for {}, e = " + e, displayName);
            AccountData failed = new AccountData();
            failed.setDisplayName(displayName);
            failed.setPersistenceReadFailed(true);
            return failed;
        }
    }

    private boolean storeAccountData(String displayName)
    {
        try
        {
            AccountData data = accountSpecificData.get(displayName);
            if (data == null)
            {
                log.warn("No cached data for {}; refusing to write an empty account", displayName);
                return false;
            }
            thisClientLastStored = displayName;
            data.setLastStoredAt(Instant.now());
            plugin.tradePersister.writeToFile(displayName, data);
            return true;
        }
        catch (Exception e)
        {
            log.warn("couldn't store trades, error = " + e);
            return false;
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
}
