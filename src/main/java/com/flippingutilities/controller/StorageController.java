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

import com.flippingutilities.DataSource;
import com.flippingutilities.SqliteMaintenanceAction;
import com.flippingutilities.db.MigrationService;
import com.flippingutilities.db.SqliteStorage;
import com.flippingutilities.db.TradePersister;
import com.flippingutilities.model.AccountData;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Owns backend switching, ordered SQLite work, recovery and maintenance. */
@Slf4j
final class StorageController {
    private final FlippingPlugin plugin;
    private final Supplier<SqliteStorage> storageFactory;

    StorageController(FlippingPlugin plugin, Supplier<SqliteStorage> storageFactory) {
        this.plugin = plugin;
        this.storageFactory = storageFactory;
    }

    StorageController(FlippingPlugin plugin, Supplier<SqliteStorage> storageFactory, ExecutorService executor) {
        this(plugin, storageFactory);
        this.storageExecutor = executor;
    }

    SqliteStorage getSqliteStorage() {
        return sqliteStorage;
    }

    void migrateLoadedData() {
        if (sqliteStorage != null) {
            queueMigration(sqliteStorage, false);
        }
    }

    // SQLite storage backend (optional - used when dataSource=SQLITE)
    private SqliteStorage sqliteStorage;

    private final Set<SqliteStorage> failedStorages = ConcurrentHashMap.newKeySet();

    // RuneLite's executor can have multiple workers. Serialize database imports, writes and
    // closes explicitly so a live mutation cannot be overwritten by an earlier import.
    private ExecutorService storageExecutor;

    private synchronized ExecutorService getStorageExecutor() {
        if (storageExecutor == null) {
            storageExecutor = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder()
                .setNameFormat("flipping-storage-%d").setDaemon(true).build());
        }
        return storageExecutor;
    }

    synchronized void submitStorageTask(Consumer<SqliteStorage> task) {
        SqliteStorage storage = sqliteStorage;
        if (storage != null) {
            getStorageExecutor().execute(() -> {
                if (failedStorages.contains(storage)) {
                    return;
                }
                try {
                    task.accept(storage);
                } catch (Exception e) {
                    recoverFromStorageFailure(storage, e);
                }
            });
        }
    }

    boolean isStorageFailed(SqliteStorage storage) {
        return storage != null && failedStorages.contains(storage);
    }

    /** Keep a durable recovery marker outside the database, which may itself be read-only. */
    void recoverFromStorageFailure(SqliteStorage storage, Exception failure) {
        if (!failedStorages.add(storage)) {
            return;
        }
        log.warn("SQLite persistence failed; saving JSON and rebuilding SQLite on next startup", failure);
        try {
            storage.markOutOfSync();
        } catch (Exception markerFailure) {
            log.error("Could not mark SQLite for recovery; switching the configured backend to JSON", markerFailure);
            plugin.getConfigManager().setConfiguration(FlippingPlugin.CONFIG_GROUP, "dataSource", DataSource.JSON);
        }
        plugin.getClientThread().invokeLater(() -> {
            if (sqliteStorage != storage) {
                return;
            }
            plugin.getDataHandler().preserveAccountsForRecovery();
            closeStorage();
            plugin.getDataHandler().storeData();
            if (plugin.getMasterPanel() != null) {
                plugin.getMasterPanel().updateSqliteIndicator();
            }
        });
    }

    void initializeStorage() {
        if (plugin.getConfig().dataSource().isSqlite()) {
            try {
                TradePersister.setupFlippingFolder();
                sqliteStorage = storageFactory.get();
                sqliteStorage.initializeSchema();
                if ("true".equalsIgnoreCase(sqliteStorage.getSetting("migration_completed"))
                        && !sqliteStorage.requiresFullResync()) {
                    plugin.getDataHandler().setSqliteStorage(sqliteStorage);
                }
            } catch (Exception e) {
                if (sqliteStorage != null) {
                    recoverFromStorageFailure(sqliteStorage, e);
                } else {
                    log.warn("Cannot initialize SQLite storage; keeping JSON", e);
                }
            }
        }
    }

    /** Switch persistence without replacing the live model or restarting unrelated jobs. */
    void switchStorageBackend() {
        plugin.getClientThread().invokeLater(() -> {
            synchronized (this) {
                plugin.getDataHandler().getAllAccountData();
                if (!plugin.getDataHandler().storeData() && plugin.getConfig().dataSource().isSqlite()) {
                    log.warn("Cannot switch to SQLite until the JSON snapshot is saved");
                    // Config already selects SQLite. A later restart must still prefer the
                    // JSON snapshot once autosave or shutdown successfully retries it.
                    try {
                        storageFactory.get().markOutOfSync();
                    } catch (Exception e) {
                        log.error("Cannot mark the aborted switch for retry; keeping JSON selected", e);
                        plugin.getConfigManager().setConfiguration(FlippingPlugin.CONFIG_GROUP, "dataSource", DataSource.JSON);
                    }
                    return;
                }
                closeStorage();
                if (plugin.getConfig().dataSource().isSqlite()) {
                    sqliteStorage = storageFactory.get();
                    // Enqueue the import before exposing the backend to incoming writes.
                    // The same lock protects submitStorageTask's capture and enqueue.
                    queueMigration(sqliteStorage, true);
                }
            }
            if (plugin.getMasterPanel() != null) {
                plugin.getMasterPanel().updateSqliteIndicator();
            }
        });
    }

    private synchronized Future<?> closeStorage() {
        SqliteStorage oldStorage = sqliteStorage;
        sqliteStorage = null;
        plugin.getDataHandler().setSqliteStorage(null);
        return oldStorage == null ? null : getStorageExecutor().submit(() -> {
            oldStorage.close();
            failedStorages.remove(oldStorage);
        });
    }

    synchronized Future<?> shutDownStorage() {
        Future<?> closed = closeStorage();
        if (storageExecutor != null) {
            storageExecutor.shutdown(); // Drain pending writes and the final close.
            storageExecutor = null;
        }
        return closed;
    }

    private void queueMigration(SqliteStorage storage, boolean fullResync) {
        getStorageExecutor().execute(() -> {
            if (failedStorages.contains(storage)) {
                return;
            }
            boolean completed = runMigrationIfNeeded(storage, fullResync);
            completeMigration(storage, completed);
        });
    }

    private void completeMigration(SqliteStorage storage, boolean completed) {
        if (!completed) {
            recoverFromStorageFailure(storage, new IllegalStateException("SQLite migration incomplete"));
            return;
        }
        plugin.getClientThread().invokeLater(() -> {
            // A later switch, failed write or shutdown owns the active backend now.
            if (sqliteStorage != storage || failedStorages.contains(storage)) {
                return;
            }
            plugin.getDataHandler().setSqliteStorage(storage);
            if (plugin.getMasterPanel() != null) {
                plugin.getMasterPanel().updateSqliteIndicator();
            }
        });
    }

    /** Runs only on the storage executor; never reloads or replaces the live account model. */
    private boolean runMigrationIfNeeded(SqliteStorage storage, boolean fullResync) {
        try {
            storage.initializeSchema();
            boolean rebuild = fullResync || storage.requiresFullResync();
            if (rebuild || "true".equalsIgnoreCase(storage.getSetting("migration_pending"))
                    || !"true".equalsIgnoreCase(storage.getSetting("migration_completed"))) {
                // Read once before clearing SQLite; an unreadable source must not erase it.
                Map<String, AccountData> snapshot = plugin.tradePersister.loadAllAccountsForMigration();
                if (rebuild) {
                    storage.markOutOfSync();
                    for (String name : storage.listAccounts()) {
                        storage.deleteAccountData(name);
                    }
                    storage.clearSetting("migration_completed");
                    storage.clearSetting("migration_completed_at");
                }
                new MigrationService(storage, plugin.tradePersister).migrate(snapshot);
            }
            boolean completed = "true".equalsIgnoreCase(storage.getSetting("migration_completed"));
            if (completed) {
                storage.clearSetting("migration_pending");
                storage.markSynchronized();
            }
            return completed;
        } catch (Exception e) {
            log.warn("SQLite migration failed; keeping the live JSON view", e);
            return false;
        }
    }

    /**
     * SQLite maintenance entry point from the config dropdown. Confirms the destructive
     * action, then runs it on a background thread.
     */
    void handleSqliteMaintenance(SqliteMaintenanceAction action) {
        if (sqliteStorage == null) {
            javax.swing.JOptionPane.showMessageDialog(plugin.getMasterPanel(),
                "SQLite storage is not active. Switch the data source to SQLite first.",
                "SQLite maintenance", javax.swing.JOptionPane.WARNING_MESSAGE);
            resetSqliteMaintenanceConfig();
            return;
        }

        String msg = action == SqliteMaintenanceAction.DELETE
            ? "Delete all SQLite database files? This clears stored trades. Your JSON files are not affected."
            : "Regenerate the SQLite database from JSON? This deletes the current database and rebuilds it from your JSON files.";
        int choice = javax.swing.JOptionPane.showConfirmDialog(plugin.getMasterPanel(), msg,
            "Confirm SQLite maintenance", javax.swing.JOptionPane.YES_NO_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE);
        if (choice != javax.swing.JOptionPane.YES_OPTION) {
            resetSqliteMaintenanceConfig();
            return;
        }

        log.info("Starting SQLite maintenance: {}", action);
        plugin.getClientThread().invokeLater(() -> {
            plugin.getDataHandler().getAllAccountData();
            if (!plugin.getDataHandler().storeData()) {
                log.warn("Cannot rebuild SQLite until the JSON snapshot is saved");
                resetSqliteMaintenanceConfig();
                return;
            }
            submitStorageTask(storage -> doSqliteMaintenance(storage, action));
        });
    }

    /**
     * Deletes the SQLite files and (for REGENERATE) re-runs JSON -> SQLite migration.
     * Must run off the client/EDT thread (DB I/O).
     */
    private void doSqliteMaintenance(SqliteStorage storage, SqliteMaintenanceAction action) {
        try {
            // Close the connection so the files can be deleted (Windows won't delete open files).
            storage.close();
            deleteSqliteFiles(storage);
            storage.initializeSchema();
            // The accounts table was recreated empty; stale cached ids would FK-violate.
            storage.invalidateAccountCache();

            if (action == SqliteMaintenanceAction.REGENERATE) {
                storage.setSetting("migration_pending", "true");
                completeMigration(storage, runMigrationIfNeeded(storage, false));
            } else {
                reloadAfterMaintenance(storage);
            }
        } catch (Exception e) {
            recoverFromStorageFailure(storage, e);
        } finally {
            resetSqliteMaintenanceConfig();
        }
    }

    private void deleteSqliteFiles(SqliteStorage storage) {
        File db = storage.getDbFile();
        File[] files = {
            db,
            new File(db.getPath() + "-wal"),
            new File(db.getPath() + "-shm")
        };
        for (File f : files) {
            if (f.exists() && !f.delete()) {
                log.warn("Could not delete {}", f.getAbsolutePath());
            }
        }
    }

    private void reloadAfterMaintenance(SqliteStorage storage) {
        plugin.getClientThread().invokeLater(() -> {
            if (sqliteStorage != storage) {
                return true;
            }
            try {
                plugin.getDataHandler().reloadFromSqlite();
                plugin.getMasterPanel().setupAccSelectorDropdown(plugin.getDataHandler().getCurrentAccounts());
                plugin.getStatPanel().rebuildItemsDisplay(plugin.viewItemsForCurrentView());
                plugin.getFlippingPanel().rebuild(plugin.viewItemsForCurrentView());
            } catch (Exception e) {
                log.warn("Reload after SQLite maintenance failed", e);
            }
            return true;
        });
    }

    private void resetSqliteMaintenanceConfig() {
        if (plugin.getConfigManager() != null) {
            plugin.getConfigManager().setConfiguration(FlippingPlugin.CONFIG_GROUP, "sqliteMaintenance", SqliteMaintenanceAction.NONE.name());
        }
    }

}
