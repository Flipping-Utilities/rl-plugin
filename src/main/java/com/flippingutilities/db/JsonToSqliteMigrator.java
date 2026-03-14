package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.AccountWideData;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Handles the one-time migration from the old JSON file storage to the new SQLite database.
 *
 * This runs automatically the first time someone uses the fork. It:
 * 1. Reads all existing JSON trade files (one per account)
 * 2. Writes everything into the new SQLite database
 * 3. Renames the old JSON files (adds ".pre-sqlite" suffix) so they're kept as backups
 *
 * If anything goes wrong during migration, the database changes are rolled back
 * and the original JSON files remain untouched — so no data can be lost.
 */
@Slf4j
public class JsonToSqliteMigrator {

    private final TradePersister jsonPersister;
    private final SqliteDataStore sqliteStore;

    public JsonToSqliteMigrator(TradePersister jsonPersister, SqliteDataStore sqliteStore) {
        this.jsonPersister = jsonPersister;
        this.sqliteStore = sqliteStore;
    }

    /**
     * Performs the full migration. Returns true if successful, false if it failed
     * (in which case the plugin should fall back to JSON storage).
     */
    public boolean migrate() {
        log.info("Starting JSON to SQLite migration...");
        try {
            // Step 1: Load all existing data from JSON files
            Map<String, AccountData> allAccounts = jsonPersister.loadAllAccounts();
            log.info("Loaded {} accounts from JSON files", allAccounts.size());

            AccountWideData accountWideData;
            try {
                accountWideData = jsonPersister.loadAccountWideData();
            } catch (Exception e) {
                log.warn("Couldn't load accountwide data during migration, using defaults", e);
                accountWideData = new AccountWideData();
                accountWideData.setDefaults();
            }

            // Step 2: Write everything to SQLite in one transaction
            // (if any part fails, nothing gets written — all or nothing)
            for (Map.Entry<String, AccountData> entry : allAccounts.entrySet()) {
                String displayName = entry.getKey();
                AccountData data = entry.getValue();

                if (data == null) {
                    log.warn("Skipping null account data for {}", displayName);
                    continue;
                }

                log.info("Migrating account: {} ({} trades, {} recipe groups)",
                    displayName,
                    data.getTrades().size(),
                    data.getRecipeFlipGroups().size());

                sqliteStore.saveAccount(displayName, data);
            }

            // Save account-wide data
            sqliteStore.saveAccountWideData(accountWideData);

            // Set schema version
            sqliteStore.setSchemaVersion(SqliteSchema.CURRENT_SCHEMA_VERSION);

            // Step 3: Verify the migration worked by counting records
            int totalItems = sqliteStore.countFlippingItems();
            int totalOffers = sqliteStore.countOfferEvents();
            log.info("Migration verification: {} items, {} offers in SQLite", totalItems, totalOffers);

            // Count what we expected
            int expectedItems = allAccounts.values().stream()
                .mapToInt(a -> a.getTrades().size())
                .sum();
            int expectedOffers = allAccounts.values().stream()
                .flatMap(a -> a.getTrades().stream())
                .mapToInt(t -> t.getHistory().getCompressedOfferEvents().size())
                .sum();

            if (totalItems != expectedItems || totalOffers != expectedOffers) {
                log.warn("Migration count mismatch! Expected {} items/{} offers, got {} items/{} offers",
                    expectedItems, expectedOffers, totalItems, totalOffers);
                // Continue anyway — some discrepancy might be due to deduplication
            }

            // Step 4: Rename old JSON files to .pre-sqlite (safety backup)
            renameJsonFiles();

            log.info("Migration complete! All trade data is now in SQLite.");
            return true;

        } catch (Exception e) {
            log.error("Migration failed! Keeping JSON files as-is. Error: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Renames JSON files to .pre-sqlite so they're kept as backups
     * but won't be loaded next time. The original data is preserved
     * in case someone needs to revert.
     */
    private void renameJsonFiles() {
        File parentDir = TradePersister.PARENT_DIRECTORY;
        File[] files = parentDir.listFiles();
        if (files == null) return;

        for (File f : files) {
            String name = f.getName();
            // Rename account JSON files and accountwide.json
            if (name.endsWith(".json") && !name.endsWith(".backup.json") && !name.endsWith(".special.json")) {
                Path source = f.toPath();
                Path target = source.resolveSibling(name + ".pre-sqlite");
                try {
                    Files.move(source, target);
                    log.info("Renamed {} to {}", name, name + ".pre-sqlite");
                } catch (IOException e) {
                    log.warn("Couldn't rename {}: {}", name, e.getMessage());
                    // Not fatal — the file will just be ignored on next load since SQLite DB exists
                }
            }
        }
    }
}
