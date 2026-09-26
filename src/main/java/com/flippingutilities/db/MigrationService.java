package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.RecipeFlipGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.flippingutilities.db.SqliteBindings.bind;

/**
 * Service to migrate data from JSON-based storage to SQLite.
 * Uses batch operations for performance.
 */
public class MigrationService {
    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);

    // Batch size for insert operations
    private static final int BATCH_SIZE = 500;

    private final SqliteStorage storage;
    private final TradePersister tradePersister;

    public MigrationService(SqliteStorage storage, TradePersister tradePersister) {
        this.storage = storage;
        this.tradePersister = tradePersister;
    }

    /**
     * Performs migration from JSON files to SQLite database.
     *
     * Idempotency: bails out immediately if {@code migration_completed=true} is already set.
     * On partial failure, successful accounts are committed but flagged via
     * {@code migrated_<displayName>} so a retry skips them. All INSERTs use OR IGNORE against
     * UNIQUE constraints on trade UUIDs and recipe natural keys make retries idempotent.
     *
     * <p>Each account migrates inside its OWN transaction (not one giant one): the shared
     * cached connection is used by live trade recording and account loading, and a
     * whole-run transaction would either swallow concurrent live writes in a rollback or be
     * committed prematurely by them. Per-account transactions let live traffic interleave
     * safely between accounts.
     *
     * @return Number of accounts migrated
     */
    public int migrate() {
        storage.initializeSchema();
        if (storage.getBooleanSetting(SqliteSettings.MIGRATION_COMPLETED)) {
            return 0;
        }
        return migrate(tradePersister.loadAllAccountsForMigration());
    }

    /** Imports a previously read snapshot so a rebuild cannot reread a changed source. */
    public int migrate(Map<String, AccountData> accounts) {
        Objects.requireNonNull(accounts, "Account snapshot is required");
        storage.initializeSchema();

        // Idempotency guard: never re-run a completed migration.
        if (storage.getBooleanSetting(SqliteSettings.MIGRATION_COMPLETED)) {
            log.info("Migration already completed; skipping.");
            return 0;
        }

        // Additive import can still proceed if a backup cannot be created. Destructive
        // rebuild/regeneration must let the same failure abort before deleting data.
        try {
            createPreMigrationBackup();
        } catch (IllegalStateException e) {
            log.warn("Could not create pre-migration backup (continuing additive import): {}", e.getMessage());
        }
        return importAccounts(accounts);
    }

    /** Replaces SQLite account data from one snapshot, backing up before any account is cleared. */
    public int rebuild(Map<String, AccountData> accounts) {
        Objects.requireNonNull(accounts, "Account snapshot is required");
        synchronized (storage) {
            storage.initializeSchema();
            storage.markOutOfSync();
            createPreMigrationBackup();
            for (String displayName : storage.listAccounts()) {
                storage.deleteAccountData(displayName);
            }
            storage.clearSetting(SqliteSettings.MIGRATION_COMPLETED);
            storage.clearSetting(SqliteSettings.MIGRATION_COMPLETED_AT);
        }
        return importAccounts(accounts);
    }

    /** Recreates the SQLite files for maintenance after preserving their populated contents. */
    public int regenerate(Map<String, AccountData> accounts) {
        Objects.requireNonNull(accounts, "Account snapshot is required");
        synchronized (storage) {
            storage.markOutOfSync();
            createPreMigrationBackup();
            storage.close();
            File dbFile = storage.getDbFile();
            try {
                Files.deleteIfExists(dbFile.toPath());
                Files.deleteIfExists(new File(dbFile.getPath() + "-wal").toPath());
                Files.deleteIfExists(new File(dbFile.getPath() + "-shm").toPath());
            } catch (IOException e) {
                throw new IllegalStateException("Could not recreate SQLite database", e);
            }
            storage.initializeSchema();
        }
        return importAccounts(accounts);
    }

    private int importAccounts(Map<String, AccountData> accounts) {
        log.info("Starting JSON -> SQLite migration...");
        long startTime = System.currentTimeMillis();

        if (accounts.isEmpty()) {
            log.info("No account data found to migrate.");
            // Nothing to migrate; mark complete so we don't keep retrying.
            storage.setBooleanSetting(SqliteSettings.MIGRATION_COMPLETED, true);
            storage.setSetting(SqliteSettings.MIGRATION_COMPLETED_AT, Instant.now().toString());
            return 0;
        }

        int accountsMigrated = 0;
        int accountsSkipped = 0;
        int accountsFailed = 0;
        int totalTrades = 0;
        int totalRecipeFlips = 0;

        for (Map.Entry<String, AccountData> entry : accounts.entrySet()) {
            String displayName = entry.getKey();
            AccountData accountData = entry.getValue();

            // Per-account idempotency: skip accounts that have already been migrated.
            // Combined with INSERT OR IGNORE + UNIQUE constraints this makes re-runs safe.
            if (storage.getSetting(SqliteSettings.accountMigrationKey(displayName)) != null) {
                log.debug("Account {} already migrated; skipping.", displayName);
                accountsSkipped++;
                continue;
            }

            int[] counts = migrateAccountInTransaction(displayName, accountData);
            if (counts != null) {
                accountsMigrated++;
                totalTrades += counts[0];
                totalRecipeFlips += counts[1];
            } else {
                accountsFailed++;
            }
        }

        // Only mark completion when every account is either migrated (now) or was already
        // (prior run). Otherwise leave migration_completed unset so the next startup retries
        // the failed accounts (the migration_pending flag is preserved by the caller).
        if (accountsFailed == 0 && accountsMigrated + accountsSkipped == accounts.size()) {
            storage.setBooleanSetting(SqliteSettings.MIGRATION_COMPLETED, true);
            storage.setSetting(SqliteSettings.MIGRATION_COMPLETED_AT, Instant.now().toString());
        } else {
            log.warn("Migration incomplete: {}/{} accounts migrated, {} failed. Will retry on next startup.",
                accountsMigrated, accounts.size(), accountsFailed);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Migration complete. Accounts: {} ({} failed), Trades: {}, Recipe Flips: {}, Time: {}ms",
            accountsMigrated, accountsFailed, totalTrades, totalRecipeFlips, elapsed);
        return accountsMigrated;
    }

    /**
     * Migrates a single account inside its own transaction. Holds the storage monitor for the
     * duration so live writes (which go through synchronized storage methods / the single
     * plugin executor) cannot join the open transaction; between accounts the lock is
     * released so normal traffic proceeds.
     *
     * @return int[2] counts on success ({trades, recipe flips}), or null on failure
     */
    private int[] migrateAccountInTransaction(String displayName, AccountData accountData) {
        synchronized (storage) {
            Connection conn = null;
            try {
                conn = storage.getConnection();
                boolean wasAutoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    int[] counts = migrateAccountBatched(conn, displayName, accountData);
                    conn.commit();
                    log.info("Migrated account: {} ({} trades, {} recipe flips)",
                        displayName, counts[0], counts[1]);
                    return counts;
                } catch (Exception e) {
                    log.error("Failed to migrate account: {}", displayName, e);
                    try {
                        conn.rollback();
                    } catch (SQLException re) {
                        log.warn("Rollback failed for account {}", displayName, re);
                    }
                    return null;
                } finally {
                    try {
                        conn.setAutoCommit(wasAutoCommit);
                    } catch (SQLException e) {
                        log.warn("Failed to restore autocommit", e);
                    }
                }
            } catch (SQLException e) {
                log.error("Could not start transaction for account {}", displayName, e);
                return null;
            }
        }
    }

    /**
     * Snapshot the SQLite DB before migration. Uses VACUUM INTO so the live connection is
     * not disturbed. Destructive callers must abort if the backup cannot be created.
     */
    private void createPreMigrationBackup() {
        synchronized (storage) {
            try {
                File dbFile = storage.getDbFile();
                String backupPath = dbFile.getAbsolutePath() + ".pre-migration-" + System.currentTimeMillis()
                    + "-" + UUID.randomUUID() + ".db";
                Connection vacuumConn = storage.getConnection();
                try (Statement stmt = vacuumConn.createStatement()) {
                    stmt.execute("VACUUM INTO '" + backupPath.replace("'", "''") + "'");
                    log.info("Created pre-migration backup at {}", backupPath);
                }
            } catch (Exception e) {
                throw new IllegalStateException("Could not create SQLite pre-migration backup", e);
            }
        }
    }

    /**
     * Migrate a single account using batched operations.
     * @return int[2] where [0] = trade count and [1] = recipe flip count
     */
    int[] migrateAccountBatched(Connection conn, String displayName, AccountData accountData) throws SQLException {
        int tradesCount = 0;
        int recipeFlipsCount = 0;

        if (accountData == null) {
            log.warn("Account data is null for: {}", displayName);
            return new int[]{0, 0};
        }

        accountData.normalizeOfferIds();

        List<FlippingItem> tradeItems = accountData.getTrades();
        if (tradeItems == null) {
            tradeItems = Collections.emptyList();
        }

        // Get or create account ID
        int accountId = storage.getOrCreateAccountId(displayName);

        // Preserve session time from JSON into the accounts row (getOrCreateAccountId
        // initializes accumulated_time to 0; update it from the source AccountData).
        updateAccountSessionTime(conn, accountId, accountData.getAccumulatedSessionTimeMillis(), accountData.getSessionStartTime());

        List<RecipeFlipGroup> recipeFlipGroups = accountData.getRecipeFlipGroups();

        // Collect all trades for batch insert
        List<TradeRecord> tradesToInsert = new ArrayList<>();
        Set<String> historyOfferUuids = new HashSet<>();
        Map<Integer, OfferEvent> lastOffers = accountData.getLastOffers();
        Set<String> activeOfferUuids = new HashSet<>();
        if (lastOffers != null) {
            for (OfferEvent offer : lastOffers.values()) {
                if (offer != null && !offer.isComplete() && !offer.isCausedByEmptySlot()
                    && offer.getUuid() != null) {
                    activeOfferUuids.add(offer.getUuid());
                }
            }
        }

        for (FlippingItem item : tradeItems) {
            storage.upsertItemVisibility(displayName, item.getItemId(), !Boolean.FALSE.equals(item.getValidFlippingPanelItem()));
            if (item.getHistory() == null) continue;

            List<OfferEvent> offers = item.getHistory().getCompressedOfferEvents();
            if (offers == null) continue;

            for (OfferEvent offer : offers) {
                if (offer != null && offer.getUuid() != null) {
                    historyOfferUuids.add(offer.getUuid());
                }
                if (offer == null || offer.isCausedByEmptySlot()) continue;
                // Active partials belong only in active_slots: the next GE update has a new
                // UUID and replaces that slot. Archived filled partials still belong in
                // history, even if another offer now occupies the same item/slot.
                if (!offer.isComplete() && (offer.getCurrentQuantityInTrade() <= 0
                    || activeOfferUuids.contains(offer.getUuid()))) continue;

                long timestamp = offer.getTime() != null ? offer.getTime().toEpochMilli() : Instant.now().toEpochMilli();
                // Recipe components retain consumption separately from the original trade.
                int qty = offer.getCurrentQuantityInTrade();
                long price = offer.getPreTaxPrice();
                boolean isBuy = offer.isBuy();
                tradesToInsert.add(new TradeRecord(accountId, item.getItemId(), offer.getUuid(), timestamp, qty, price, isBuy, SqliteStorage.serializeOffer(offer)));

                tradesCount++;
            }

            Instant resetTime = item.getGeLimitResetTime();
            if (resetTime != null && !Instant.EPOCH.equals(resetTime)) {
                storage.upsertGeLimitState(displayName, item.getItemId(), resetTime,
                    item.getItemsBoughtThisLimitWindow(),
                    item.getHistory().getItemsBoughtThroughCompleteOffers());
            }
        }

        // Batch insert trades
        if (!tradesToInsert.isEmpty()) {
            batchInsertTrades(conn, tradesToInsert);
        }

        // Resolve UUID-only recipes before persisting their independent offer snapshots.
        if (recipeFlipGroups != null && !recipeFlipGroups.isEmpty()) {
            int dangling = hydrateRecipeFlipOffers(recipeFlipGroups, tradeItems);
            if (dangling > 0) {
                // One summary line instead of one WARN per component: real accounts carry
                // hundreds of references to offers destroyed by historical data-loss bugs,
                // and the per-component spam buried actually-useful log output.
                log.warn("{} recipe component(s) in account {} reference offers that no longer "
                    + "exist anywhere; their references were preserved without prices", dangling, displayName);
            }
            recipeFlipsCount = migrateRecipeFlips(conn, accountId, recipeFlipGroups);
        }

        // Migrate last offers (active slots) - skip for batch, use storage method
        if (lastOffers != null) {
            for (Map.Entry<Integer, OfferEvent> slotEntry : lastOffers.entrySet()) {
                int slotIndex = slotEntry.getKey();
                OfferEvent offer = slotEntry.getValue();
                if (offer != null && !offer.isCausedByEmptySlot()) {
                    storage.upsertSlot(displayName, slotIndex, offer, historyOfferUuids.contains(offer.getUuid()));
                }
            }
        }

        // Favorites participate in the account transaction, so a failed write cannot mark
        // this account complete and silently lose them on the next reload.
        migrateFavoritesForAccount(displayName, accountData);

        // Store migration metadata
        storage.setSetting(SqliteSettings.accountMigrationKey(displayName), Instant.now().toString());

        return new int[]{tradesCount, recipeFlipsCount};
    }

    private void updateAccountSessionTime(Connection conn, int accountId, long accumulatedMillis, Instant sessionStart) throws SQLException {
        String sql = "UPDATE accounts SET accumulated_time = ?, session_start = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, accumulatedMillis, sessionStart == null ? null : sessionStart.toEpochMilli(), accountId);
            ps.executeUpdate();
        }
    }

    private void batchInsertTrades(Connection conn, List<TradeRecord> trades) throws SQLException {
        // INSERT OR IGNORE against UNIQUE(account_id, uuid) makes re-runs idempotent: a trade
        // whose uuid already exists for this account is silently skipped instead of duplicated.
        String sql = "INSERT OR IGNORE INTO trades (account_id, item_id, uuid, timestamp, qty, price, is_buy, offer_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int count = 0;
            for (TradeRecord trade : trades) {
                bind(ps, trade.accountId, trade.itemId, trade.uuid, trade.timestamp,
                    trade.qty, trade.price, trade.isBuy, trade.offerJson);
                ps.addBatch();
                count++;

                if (count % BATCH_SIZE == 0) {
                    ps.executeBatch();
                }
            }
            if (count % BATCH_SIZE != 0) {
                ps.executeBatch();
            }
        }
    }

    private int migrateRecipeFlips(Connection conn, int accountId, List<RecipeFlipGroup> groups) throws SQLException {
        int count = 0;
        for (RecipeFlipGroup group : groups) {
            if (group == null || group.getRecipeFlips() == null) continue;
            for (RecipeFlip flip : group.getRecipeFlips()) {
                if (SqliteStorage.insertRecipeFlip(conn, accountId, group.getRecipeKey(), flip)) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Hydrate PartialOffers in recipe flips by linking them to their corresponding OfferEvents.
     * Older UUID-only files need the history lookup; embedded legacy offers also
     * normalize their UUID here. Neither source may be discarded before snapshotting.
     * @return the number of components whose offers remain unresolved
     */
    private int hydrateRecipeFlipOffers(List<RecipeFlipGroup> recipeFlipGroups, List<FlippingItem> tradeItems) {
        // Build UUID -> OfferEvent lookup map from all trade items
        Map<String, OfferEvent> offersByUuid = new HashMap<>();
        for (FlippingItem item : tradeItems) {
            if (item.getHistory() == null) continue;
            List<OfferEvent> offers = item.getHistory().getCompressedOfferEvents();
            if (offers == null) continue;
            for (OfferEvent offer : offers) {
                if (offer != null) {
                    offersByUuid.put(offer.getUuid(), offer);
                }
            }
        }

        // Missing backing offers must neither abort account migration nor acquire invented
        // prices. Preserve unresolved references so the UI can report incomplete data.
        int dangling = 0;
        for (RecipeFlipGroup group : recipeFlipGroups) {
            for (RecipeFlip flip : group.getRecipeFlips()) {
                dangling += hydrateComponents(flip.getInputs(), offersByUuid);
                dangling += hydrateComponents(flip.getOutputs(), offersByUuid);
            }
        }
        return dangling;
    }

    /** @return the number of components whose offers remain unresolved */
    private int hydrateComponents(Map<Integer, Map<String, PartialOffer>> components,
                                  Map<String, OfferEvent> offersByUuid) {
        if (components == null) {
            return 0;
        }
        int dangling = 0;
        for (Map<String, PartialOffer> offerMap : components.values()) {
            if (offerMap == null) {
                continue;
            }
            for (PartialOffer po : offerMap.values()) {
                if (po == null) {
                    continue;
                }
                po.hydrateOffer(offersByUuid);
                if (po.getOffer() == null) {
                    dangling++;
                }
            }
        }
        return dangling;
    }

    /**
     * Migrate favorites for a single account. Package-visible for testing.
     */
    int migrateFavoritesForAccount(String displayName, AccountData accountData) {
        Integer accountId = storage.getAccountId(displayName);
        if (accountId == null) return 0;

        List<FlippingItem> items = accountData.getTrades();
        if (items == null) return 0;

        int count = 0;
        for (FlippingItem item : items) {
            // Unfavoriting retains a custom code for the next time the item is favorited.
            // Null/default codes on unfavorited items need no separate favorite row.
            String favoriteCode = item.getFavoriteCode();
            if (item.isFavorite() || (favoriteCode != null && !FlippingItem.DEFAULT_FAVORITE_CODE.equals(favoriteCode))) {
                storage.upsertFavorite(displayName, item.getItemId(), item.isFavorite(), favoriteCode);
                count++;
            }
        }
        return count;
    }

    // Helper record classes
    private static class TradeRecord {
        final int accountId;
        final int itemId;
        final String uuid;
        final long timestamp;
        final int qty;
        final long price;
        final boolean isBuy;
        final String offerJson;

        TradeRecord(int accountId, int itemId, String uuid, long timestamp, int qty, long price, boolean isBuy, String offerJson) {
            this.accountId = accountId;
            this.itemId = itemId;
            this.uuid = uuid;
            this.timestamp = timestamp;
            this.qty = qty;
            this.price = price;
            this.isBuy = isBuy;
            this.offerJson = offerJson;
        }
    }


}
