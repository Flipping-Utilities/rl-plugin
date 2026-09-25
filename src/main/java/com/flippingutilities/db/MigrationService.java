package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.Flip;
import com.flippingutilities.model.HistoryManager;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.RecipeFlipGroup;
import com.flippingutilities.utilities.Recipe;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

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
     * UNIQUE constraints (trades.uuid, events.natural_key) so a retried account is a no-op.
     *
     * <p>Each account migrates inside its OWN transaction (not one giant one): the shared
     * cached connection is used by live trade recording and repository queries, and a
     * whole-run transaction would either swallow concurrent live writes in a rollback or be
     * committed prematurely by them. Per-account transactions let live traffic interleave
     * safely between accounts.
     *
     * @return Number of accounts migrated
     */
    public int migrate() {
        log.info("Starting JSON -> SQLite migration...");
        long startTime = System.currentTimeMillis();
        storage.initializeSchema();

        // Idempotency guard: never re-run a completed migration.
        if ("true".equalsIgnoreCase(storage.getSetting("migration_completed"))) {
            log.info("Migration already completed; skipping.");
            return 0;
        }

        // Best-effort pre-migration backup so a botched run can be restored.
        createPreMigrationBackup();

        // Use TradePersister to load all accounts
        Map<String, AccountData> accounts = tradePersister.loadAllAccounts();

        if (accounts == null || accounts.isEmpty()) {
            log.info("No account data found to migrate.");
            // Nothing to migrate; mark complete so we don't keep retrying.
            storage.setSetting("migration_completed", "true");
            storage.setSetting("migration_completed_at", Instant.now().toString());
            return 0;
        }

        int accountsMigrated = 0;
        int accountsSkipped = 0;
        int accountsFailed = 0;
        int totalTrades = 0;
        int totalFlips = 0;
        int totalRecipeFlips = 0;

        for (Map.Entry<String, AccountData> entry : accounts.entrySet()) {
            String displayName = entry.getKey();
            AccountData accountData = entry.getValue();

            // Per-account idempotency: skip accounts that have already been migrated.
            // Combined with INSERT OR IGNORE + UNIQUE constraints this makes re-runs safe.
            if (storage.getSetting("migrated_" + displayName) != null) {
                log.debug("Account {} already migrated; skipping.", displayName);
                accountsSkipped++;
                continue;
            }

            int[] counts = migrateAccountInTransaction(displayName, accountData);
            if (counts != null) {
                accountsMigrated++;
                totalTrades += counts[0];
                totalFlips += counts[1];
                totalRecipeFlips += counts[2];
            } else {
                accountsFailed++;
            }
        }

        // Migrate local recipes (stored in AccountWideData). Best-effort, outside the account
        // transactions (INSERT OR REPLACE keyed on recipe_key is idempotent by itself).
        try {
            com.flippingutilities.model.AccountWideData accountWideData = tradePersister.loadAccountWideData();
            if (accountWideData != null && accountWideData.getLocalRecipes() != null && !accountWideData.getLocalRecipes().isEmpty()) {
                int localRecipesCount = migrateLocalRecipes(accountWideData.getLocalRecipes());
                log.info("Migrated {} local recipes", localRecipesCount);
            }
        } catch (Exception e) {
            log.warn("Could not migrate local recipes", e);
        }

        // Migrate favorites. Best-effort (upserts are idempotent).
        try {
            int favoritesMigrated = migrateFavorites();
            if (favoritesMigrated > 0) {
                log.info("Migrated {} item favorites", favoritesMigrated);
            }
        } catch (Exception e) {
            log.warn("Could not migrate favorites", e);
        }

        // Only mark completion when every account is either migrated (now) or was already
        // (prior run). Otherwise leave migration_completed unset so the next startup retries
        // the failed accounts (the migration_pending flag is preserved by the caller).
        if (accountsFailed == 0 && accountsMigrated + accountsSkipped == accounts.size()) {
            storage.setSetting("migration_completed", "true");
            storage.setSetting("migration_completed_at", Instant.now().toString());
        } else {
            log.warn("Migration incomplete: {}/{} accounts migrated, {} failed. Will retry on next startup.",
                accountsMigrated, accounts.size(), accountsFailed);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Migration complete. Accounts: {} ({} failed), Trades: {}, Flips: {}, Recipe Flips: {}, Time: {}ms",
            accountsMigrated, accountsFailed, totalTrades, totalFlips, totalRecipeFlips, elapsed);
        return accountsMigrated;
    }

    /**
     * Migrates a single account inside its own transaction. Holds the storage monitor for the
     * duration so live writes (which go through synchronized storage methods / the single
     * plugin executor) cannot join the open transaction; between accounts the lock is
     * released so normal traffic proceeds.
     *
     * @return int[3] counts on success ({trades, flips, recipe flips}), or null on failure
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
                    log.info("Migrated account: {} ({} trades, {} flips, {} recipe flips)",
                        displayName, counts[0], counts[1], counts[2]);
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
     * Best-effort snapshot of the SQLite DB before migration. Uses VACUUM INTO so the live
     * connection is not disturbed. Failures are logged but do not block migration.
     */
    private void createPreMigrationBackup() {
        try {
            java.io.File dbFile = storage.getDbFile();
            String backupPath = dbFile.getAbsolutePath() + ".pre-migration-" + System.currentTimeMillis() + ".db";
            Connection vacuumConn = storage.getConnection();
            try (Statement stmt = vacuumConn.createStatement()) {
                stmt.execute("VACUUM INTO '" + backupPath.replace("'", "''") + "'");
                log.info("Created pre-migration backup at {}", backupPath);
            }
        } catch (Exception e) {
            log.warn("Could not create pre-migration backup (continuing anyway): {}", e.getMessage());
        }
    }

    /**
     * Migrate a single account using batched operations.
     * @return int[3] where [0] = trades count, [1] = flips count, [2] = recipe flips count
     */
    int[] migrateAccountBatched(Connection conn, String displayName, AccountData accountData) throws SQLException {
        int tradesCount = 0;
        int flipsCount = 0;
        int recipeFlipsCount = 0;

        if (accountData == null) {
            log.warn("Account data is null for: {}", displayName);
            return new int[]{0, 0, 0};
        }

        List<FlippingItem> tradeItems = accountData.getTrades();
        if (tradeItems == null || tradeItems.isEmpty()) {
            log.debug("No trades to migrate for: {}", displayName);
            // Still try to migrate recipe flips even if no regular trades
            List<RecipeFlipGroup> recipeFlipGroups = accountData.getRecipeFlipGroups();
            if (recipeFlipGroups != null && !recipeFlipGroups.isEmpty()) {
                int accountId = getOrCreateAccountId(conn, displayName);
                recipeFlipsCount = migrateRecipeFlipsBatched(conn, accountId, recipeFlipGroups, Collections.emptyMap());
            }
            return new int[]{0, 0, recipeFlipsCount};
        }

        // Get or create account ID
        int accountId = getOrCreateAccountId(conn, displayName);

        // Preserve session time from JSON into the accounts row (getOrCreateAccountId
        // initializes accumulated_time to 0; update it from the source AccountData).
        updateAccountSessionTime(conn, accountId, accountData.getAccumulatedSessionTimeMillis(), accountData.getSessionStartTime());

        // Build map of offer UUID -> totalAmountConsumed from recipe flip PartialOffers.
        // This prevents double-counting: trades consumed by recipe flips must not also
        // generate regular flip profit.
        Map<String, Integer> recipeConsumptionByUuid = new HashMap<>();
        List<RecipeFlipGroup> recipeFlipGroups = accountData.getRecipeFlipGroups();
        if (recipeFlipGroups != null) {
            for (RecipeFlipGroup group : recipeFlipGroups) {
                for (RecipeFlip flip : group.getRecipeFlips()) {
                    if (flip.getInputs() != null) {
                        for (Map.Entry<Integer, Map<String, PartialOffer>> entry : flip.getInputs().entrySet()) {
                            for (PartialOffer po : entry.getValue().values()) {
                                recipeConsumptionByUuid.merge(po.getOfferUuid(), po.getAmountConsumed(), Integer::sum);
                            }
                        }
                    }
                    if (flip.getOutputs() != null) {
                        for (Map.Entry<Integer, Map<String, PartialOffer>> entry : flip.getOutputs().entrySet()) {
                            for (PartialOffer po : entry.getValue().values()) {
                                recipeConsumptionByUuid.merge(po.getOfferUuid(), po.getAmountConsumed(), Integer::sum);
                            }
                        }
                    }
                }
            }
        }

        // Collect all trades for batch insert
        List<TradeRecord> tradesToInsert = new ArrayList<>();
        Map<Integer, List<OfferEvent>> offersByItem = new HashMap<>();

        for (FlippingItem item : tradeItems) {
            if (item.getHistory() == null) continue;

            List<OfferEvent> offers = item.getHistory().getCompressedOfferEvents();
            if (offers == null) continue;

            List<OfferEvent> validOffers = new ArrayList<>();
            for (OfferEvent offer : offers) {
                if (offer == null || !offer.isComplete() || offer.isCausedByEmptySlot()) continue;

                long timestamp = offer.getTime() != null ? offer.getTime().toEpochMilli() : Instant.now().toEpochMilli();
                // Trades always store the ORIGINAL quantity; recipe consumption is recorded
                // in consumed_trade and subtracted at read time (reconcileFlipsForItem, the
                // recipe input/output loaders). Reducing the trade row here as well would
                // double-subtract and corrupt remaining-quantity displays and re-saved JSON.
                int qty = offer.getCurrentQuantityInTrade();
                Integer consumed = recipeConsumptionByUuid.get(offer.getUuid());
                int remaining = consumed != null ? Math.max(0, qty - consumed) : qty;
                int price = offer.getPreTaxPrice();
                boolean isBuy = offer.isBuy();
                // Preserve the real per-item tax from the OfferEvent. Buy-side trades pay no
                // tax. Null-time offers are legacy pre-tax-era records (getPrice() would NPE);
                // tax them at 0.
                long tax = isBuy || offer.getTime() == null ? 0L : (long) offer.getTaxPaidPerItem() * qty;

                tradesToInsert.add(new TradeRecord(accountId, item.getItemId(), offer.getUuid(), timestamp, qty, price, isBuy, tax));

                // Only include offers with unconsumed units for regular flip computation,
                // adjusted down by recipe consumption so consumed units don't pair into flips.
                if (remaining > 0) {
                    if (consumed != null && consumed > 0) {
                        OfferEvent adjusted = offer.clone();
                        adjusted.setCurrentQuantityInTrade(remaining);
                        validOffers.add(adjusted);
                    } else {
                        validOffers.add(offer);
                    }
                }
                tradesCount++;
            }

            if (!validOffers.isEmpty()) {
                offersByItem.put(item.getItemId(), validOffers);
            }

            // Migrate GE limit state
            try {
                Instant resetTime = item.getGeLimitResetTime();
                if (resetTime != null && resetTime != Instant.EPOCH) {
                    upsertGeLimitStateBatched(conn, accountId, item.getItemId(), resetTime,
                        item.getItemsBoughtThisLimitWindow(),
                        item.getHistory().getItemsBoughtThroughCompleteOffers());
                }
            } catch (Exception e) {
                log.debug("Could not migrate GE limit for item {}: {}", item.getItemId(), e.getMessage());
            }
        }

        // Batch insert trades
        if (!tradesToInsert.isEmpty()) {
            batchInsertTrades(conn, tradesToInsert);
        }

        // Build UUID -> trade ID map for recipe flip consumed_trade linking
        Map<String, Long> offerUuidToTradeId = buildUuidToTradeIdMap(conn, accountId);

        // Migrate flips with consumed trades
        flipsCount = migrateFlipsBatched(conn, accountId, offersByItem);

        // Migrate recipe flips (hydrate PartialOffers first so profit/expense are correct)
        if (recipeFlipGroups != null && !recipeFlipGroups.isEmpty()) {
            hydrateRecipeFlipOffers(recipeFlipGroups, tradeItems);
            recipeFlipsCount = migrateRecipeFlipsBatched(conn, accountId, recipeFlipGroups, offerUuidToTradeId);
        }

        // Migrate last offers (active slots) - skip for batch, use storage method
        Map<Integer, OfferEvent> lastOffers = accountData.getLastOffers();
        if (lastOffers != null) {
            for (Map.Entry<Integer, OfferEvent> slotEntry : lastOffers.entrySet()) {
                int slotIndex = slotEntry.getKey();
                OfferEvent offer = slotEntry.getValue();
                if (offer != null && !offer.isComplete() && !offer.isCausedByEmptySlot()) {
                    storage.upsertSlot(displayName, slotIndex, offer);
                }
            }
        }

        // Store migration metadata
        storage.setSetting("migrated_" + displayName, Instant.now().toString());

        return new int[]{tradesCount, flipsCount, recipeFlipsCount};
    }

    private int getOrCreateAccountId(Connection conn, String displayName) throws SQLException {
        // Try to get existing account ID
        String selectSql = "SELECT id FROM accounts WHERE display_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setString(1, displayName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }

        // Create new account. player_id is left NULL: there is no RuneLite API that exposes
        // the in-game account ID today. Clobbering it with the display name (as before) would
        // lose the ability to track accounts across name changes.
        String insertSql = "INSERT INTO accounts (display_name, player_id, session_start, accumulated_time) VALUES (?, NULL, ?, ?)";
        long now = Instant.now().toEpochMilli();
        try (PreparedStatement ps = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, displayName);
            ps.setLong(2, now);
            ps.setLong(3, 0L);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        throw new SQLException("Failed to create account for displayName=" + displayName);
    }

    private void updateAccountSessionTime(Connection conn, int accountId, long accumulatedMillis, Instant sessionStart) throws SQLException {
        String sql = "UPDATE accounts SET accumulated_time = ?, session_start = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, accumulatedMillis);
            if (sessionStart != null) {
                ps.setLong(2, sessionStart.toEpochMilli());
            } else {
                ps.setNull(2, Types.INTEGER);
            }
            ps.setInt(3, accountId);
            ps.executeUpdate();
        }
    }

    private void batchInsertTrades(Connection conn, List<TradeRecord> trades) throws SQLException {
        // INSERT OR IGNORE against UNIQUE(account_id, uuid) makes re-runs idempotent: a trade
        // whose uuid already exists for this account is silently skipped instead of duplicated.
        String sql = "INSERT OR IGNORE INTO trades (account_id, item_id, uuid, timestamp, qty, price, is_buy, tax) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int count = 0;
            for (TradeRecord trade : trades) {
                ps.setInt(1, trade.accountId);
                ps.setInt(2, trade.itemId);
                if (trade.uuid == null) {
                    ps.setNull(3, Types.VARCHAR);
                } else {
                    ps.setString(3, trade.uuid);
                }
                ps.setLong(4, trade.timestamp);
                ps.setInt(5, trade.qty);
                ps.setInt(6, trade.price);
                ps.setInt(7, trade.isBuy ? 1 : 0);
                ps.setLong(8, trade.tax);
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

    private int migrateFlipsBatched(Connection conn, int accountId, Map<Integer, List<OfferEvent>> offersByItem) throws SQLException {
        int flipsCount = 0;

        List<FlipRecord> flipsToInsert = new ArrayList<>();

        for (Map.Entry<Integer, List<OfferEvent>> entry : offersByItem.entrySet()) {
            int itemId = entry.getKey();
            List<OfferEvent> offers = entry.getValue();

            List<OfferEvent> validOffers = offers.stream()
                .filter(o -> o != null)
                .collect(Collectors.toList());

            log.debug("Item {}: {} offers total", itemId, validOffers.size());
            if (validOffers.isEmpty()) continue;

            List<OfferEvent> clonedOffers = validOffers.stream()
                .map(OfferEvent::clone)
                .collect(Collectors.toList());
            clonedOffers.forEach(o -> o.setMadeBy("migrated"));

            List<Flip> flips = HistoryManager.getFlips(clonedOffers);
            log.debug("Item {}: getFlips returned {} flips", itemId, flips == null ? 0 : flips.size());
            if (flips == null || flips.isEmpty()) continue;

            Map<Long, Map<String, Object>> tradesById = loadTradesByItemAsMap(conn, accountId, itemId);

            List<Map<String, Object>> sortedTrades = new ArrayList<>(tradesById.values());
            sortedTrades.sort(Comparator.comparingLong(t -> (Long) t.get("timestamp")));

            LinkedList<long[]> buyQueue = new LinkedList<>();
            LinkedList<long[]> sellQueue = new LinkedList<>();
            for (Map<String, Object> trade : sortedTrades) {
                Long tradeId = (Long) trade.get("id");
                int qty = (Integer) trade.get("qty");
                boolean isBuy = (Integer) trade.get("isBuy") == 1;
                if (isBuy) {
                    buyQueue.add(new long[]{tradeId, qty});
                } else {
                    sellQueue.add(new long[]{tradeId, qty});
                }
            }

            for (Flip flip : flips) {
                if (flip == null || flip.getTime() == null) continue;

                int flipQty = flip.getQuantity();
                int buyPrice = flip.getBuyPrice();
                int sellPrice = flip.getSellPrice();

                List<long[]> consumedTrades = new ArrayList<>();
                Long firstBuyTradeId = null;
                Long firstSellTradeId = null;

                int qtyNeeded = flipQty;
                while (qtyNeeded > 0 && !buyQueue.isEmpty()) {
                    long[] buyEntry = buyQueue.peekFirst();
                    if (buyEntry == null) break;

                    long tradeId = buyEntry[0];
                    int remaining = (int) buyEntry[1];

                    if (remaining <= 0) {
                        buyQueue.pollFirst();
                        continue;
                    }

                    if (firstBuyTradeId == null) {
                        firstBuyTradeId = tradeId;
                    }
                    int consume = Math.min(qtyNeeded, remaining);
                    consumedTrades.add(new long[]{tradeId, consume});
                    buyEntry[1] = remaining - consume;
                    qtyNeeded -= consume;

                    if (buyEntry[1] <= 0) {
                        buyQueue.pollFirst();
                    }
                }

                qtyNeeded = flipQty;
                while (qtyNeeded > 0 && !sellQueue.isEmpty()) {
                    long[] sellEntry = sellQueue.peekFirst();
                    if (sellEntry == null) break;

                    long tradeId = sellEntry[0];
                    int remaining = (int) sellEntry[1];

                    if (remaining <= 0) {
                        sellQueue.pollFirst();
                        continue;
                    }

                    if (firstSellTradeId == null) {
                        firstSellTradeId = tradeId;
                    }
                    int consume = Math.min(qtyNeeded, remaining);
                    consumedTrades.add(new long[]{tradeId, consume});
                    sellEntry[1] = remaining - consume;
                    qtyNeeded -= consume;

                    if (sellEntry[1] <= 0) {
                        sellQueue.pollFirst();
                    }
                }

                long profit = (long) (sellPrice - buyPrice) * flipQty;
                long cost = (long) buyPrice * flipQty;
                long timestamp = flip.getTime().toEpochMilli();
                String note = flip.isMarginCheck() ? "margin_check" : null;
                // First consumed buy/sell trade ids are part of the key so that two identical
                // same-millisecond partial fills backed by different trades produce distinct
                // events. Must match the format used by SqliteStorage.insertReconciledFlips.
                String naturalKey = "flip:" + accountId + ":" + itemId + ":" + timestamp + ":" + buyPrice + ":" + sellPrice + ":" + flipQty
                    + ":" + (firstBuyTradeId == null ? -1 : firstBuyTradeId)
                    + ":" + (firstSellTradeId == null ? -1 : firstSellTradeId);

                flipsToInsert.add(new FlipRecord(accountId, timestamp, cost, profit, note, naturalKey, consumedTrades));
                flipsCount++;
            }
        }

        if (!flipsToInsert.isEmpty()) {
            batchInsertFlipsAndConsumed(conn, flipsToInsert);
        }

        return flipsCount;
    }

    private Map<Long, Map<String, Object>> loadTradesByItemAsMap(Connection conn, int accountId, int itemId) throws SQLException {
        Map<Long, Map<String, Object>> result = new HashMap<>();
        String sql = "SELECT id, timestamp, qty, price, is_buy FROM trades WHERE account_id = ? AND item_id = ? ORDER BY timestamp";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            ps.setInt(2, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> trade = new HashMap<>();
                    Long id = rs.getLong("id");
                    trade.put("id", id);
                    trade.put("timestamp", rs.getLong("timestamp"));
                    trade.put("qty", rs.getInt("qty"));
                    trade.put("price", rs.getInt("price"));
                    trade.put("isBuy", rs.getInt("is_buy"));
                    result.put(id, trade);
                }
            }
        }
        return result;
    }

    private void batchInsertFlipsAndConsumed(Connection conn, List<FlipRecord> flips) throws SQLException {
        // INSERT OR IGNORE against the partial unique index on events.natural_key makes re-runs
        // idempotent at the event level too.
        String eventSql = "INSERT OR IGNORE INTO events (account_id, timestamp, type, cost, profit, note, natural_key) VALUES (?, ?, 'flip', ?, ?, ?, ?)";
        String consumedSql = "INSERT OR IGNORE INTO consumed_trade (trade_id, event_id, qty) VALUES (?, ?, ?)";

        try (PreparedStatement eventPs = conn.prepareStatement(eventSql, Statement.RETURN_GENERATED_KEYS);
             PreparedStatement consumedPs = conn.prepareStatement(consumedSql)) {

            int consumedCount = 0;
            for (FlipRecord flip : flips) {
                eventPs.setInt(1, flip.accountId);
                eventPs.setLong(2, flip.timestamp);
                eventPs.setLong(3, flip.cost);
                eventPs.setLong(4, flip.profit);
                if (flip.note == null) {
                    eventPs.setNull(5, Types.VARCHAR);
                } else {
                    eventPs.setString(5, flip.note);
                }
                eventPs.setString(6, flip.naturalKey);
                // Detect the OR IGNORE skip via the update count; getGeneratedKeys() after an
                // ignored insert returns a stale rowid in sqlite-jdbc, which would mis-link the
                // consumed_trade rows below to an unrelated row.
                if (eventPs.executeUpdate() == 0) {
                    // natural_key already present from a prior run; its consumption rows exist too.
                    continue;
                }

                long eventId;
                try (ResultSet rs = eventPs.getGeneratedKeys()) {
                    if (!rs.next()) {
                        continue;
                    }
                    eventId = rs.getLong(1);
                }

                if (flip.consumedTrades != null) {
                    for (long[] consumed : flip.consumedTrades) {
                        consumedPs.setLong(1, consumed[0]);
                        consumedPs.setLong(2, eventId);
                        consumedPs.setInt(3, (int) consumed[1]);
                        consumedPs.addBatch();
                        consumedCount++;

                        if (consumedCount % BATCH_SIZE == 0) {
                            consumedPs.executeBatch();
                        }
                    }
                }
            }
            if (consumedCount % BATCH_SIZE != 0) {
                consumedPs.executeBatch();
            }
        }
    }

    private void upsertGeLimitStateBatched(Connection conn, int accountId, int itemId, Instant nextRefresh,
                                           int itemsBought, int itemsBoughtThroughCompleteOffers) throws SQLException {
        String sql = "INSERT OR REPLACE INTO ge_limit_state (account_id, item_id, next_refresh, items_bought, items_bought_complete) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            ps.setInt(2, itemId);
            ps.setLong(3, nextRefresh.toEpochMilli());
            ps.setInt(4, itemsBought);
            ps.setInt(5, itemsBoughtThroughCompleteOffers);
            ps.executeUpdate();
        }
    }

    /**
     * Build a map from offer UUID to trade row ID for consumed_trade linking.
     */
    private Map<String, Long> buildUuidToTradeIdMap(Connection conn, int accountId) throws SQLException {
        Map<String, Long> map = new HashMap<>();
        String sql = "SELECT id, uuid FROM trades WHERE account_id = ? AND uuid IS NOT NULL";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String uuid = rs.getString("uuid");
                    long id = rs.getLong("id");
                    if (uuid != null) {
                        map.put(uuid, id);
                    }
                }
            }
        }
        return map;
    }

    /**
     * Migrate recipe flip groups for an account.
     */
    private int migrateRecipeFlipsBatched(Connection conn, int accountId, List<RecipeFlipGroup> recipeFlipGroups,
                                          Map<String, Long> offerUuidToTradeId) throws SQLException {
        if (recipeFlipGroups == null || recipeFlipGroups.isEmpty()) {
            return 0;
        }

        int totalRecipeFlips = 0;
        Gson gson = new Gson();

        String eventSql = "INSERT OR IGNORE INTO events (account_id, timestamp, type, cost, profit, note, natural_key) VALUES (?, ?, 'recipe', ?, ?, ?, ?)";
        String recipeFlipSql = "INSERT INTO recipe_flips (event_id, recipe_key, coin_cost) VALUES (?, ?, ?)";
        String inputSql = "INSERT INTO recipe_flip_inputs (recipe_flip_id, item_id, offer_uuid, amount_consumed) VALUES (?, ?, ?, ?)";
        String outputSql = "INSERT INTO recipe_flip_outputs (recipe_flip_id, item_id, offer_uuid, amount_consumed) VALUES (?, ?, ?, ?)";
        // Guards against over-consumption: real recipe data can reference more consumption than
        // the underlying trade row holds (e.g. offers deleted after the recipe flip was made,
        // or duplicate legacy records). The trade's remaining qty is recomputed per row so
        // rows inserted earlier in this same transaction are respected.
        String consumedSql =
            "INSERT OR IGNORE INTO consumed_trade (trade_id, event_id, qty) " +
            "SELECT ?, ?, MIN(?, t.qty - COALESCE((SELECT SUM(qty) FROM consumed_trade ct2 WHERE ct2.trade_id = t.id), 0)) " +
            "FROM trades t " +
            "WHERE t.id = ? " +
            "AND t.qty - COALESCE((SELECT SUM(qty) FROM consumed_trade ct2 WHERE ct2.trade_id = t.id), 0) > 0";

        try (PreparedStatement eventPs = conn.prepareStatement(eventSql, Statement.RETURN_GENERATED_KEYS);
             PreparedStatement recipeFlipPs = conn.prepareStatement(recipeFlipSql, Statement.RETURN_GENERATED_KEYS);
             PreparedStatement inputPs = conn.prepareStatement(inputSql);
             PreparedStatement outputPs = conn.prepareStatement(outputSql);
             PreparedStatement consumedPs = conn.prepareStatement(consumedSql)) {

            for (RecipeFlipGroup group : recipeFlipGroups) {
                if (group == null || group.getRecipeFlips() == null) continue;

                String recipeKey = group.getRecipeKey();

                for (RecipeFlip flip : group.getRecipeFlips()) {
                    if (flip == null || flip.getTimeOfCreation() == null) continue;

                    // Calculate cost and profit
                    long cost = flip.getExpense();
                    long profit = flip.getProfit();
                    long timestamp = flip.getTimeOfCreation().toEpochMilli();
                    String naturalKey = "recipe:" + accountId + ":" + recipeKey + ":" + timestamp;

                    // Insert event
                    eventPs.setInt(1, accountId);
                    eventPs.setLong(2, timestamp);
                    eventPs.setLong(3, cost);
                    eventPs.setLong(4, profit);
                    eventPs.setNull(5, Types.VARCHAR);
                    eventPs.setString(6, naturalKey);
                    // Detect the OR IGNORE skip via the update count; getGeneratedKeys() after
                    // an ignored insert returns a stale rowid in sqlite-jdbc. A skipped event
                    // means this recipe flip was already persisted (its components exist too).
                    if (eventPs.executeUpdate() == 0) {
                        continue;
                    }

                    long eventId;
                    try (ResultSet rs = eventPs.getGeneratedKeys()) {
                        if (!rs.next()) {
                            continue;
                        }
                        eventId = rs.getLong(1);
                    }

                    // Insert recipe_flip
                    recipeFlipPs.setLong(1, eventId);
                    recipeFlipPs.setString(2, recipeKey);
                    recipeFlipPs.setLong(3, flip.getCoinCost());
                    recipeFlipPs.executeUpdate();

                    long recipeFlipId;
                    try (ResultSet rs = recipeFlipPs.getGeneratedKeys()) {
                        if (rs.next()) {
                            recipeFlipId = rs.getLong(1);
                        } else {
                            continue;
                        }
                    }

                    // Insert inputs
                    if (flip.getInputs() != null) {
                        for (Map.Entry<Integer, Map<String, PartialOffer>> entry : flip.getInputs().entrySet()) {
                            int itemId = entry.getKey();
                            for (Map.Entry<String, PartialOffer> offerEntry : entry.getValue().entrySet()) {
                                PartialOffer po = offerEntry.getValue();
                                if (po != null && po.getAmountConsumed() > 0) {
                                    inputPs.setLong(1, recipeFlipId);
                                    inputPs.setInt(2, itemId);
                                    inputPs.setString(3, po.getOfferUuid());
                                    inputPs.setInt(4, po.getAmountConsumed());
                                    inputPs.addBatch();
                                }
                            }
                        }
                    }

                    // Insert outputs
                    if (flip.getOutputs() != null) {
                        for (Map.Entry<Integer, Map<String, PartialOffer>> entry : flip.getOutputs().entrySet()) {
                            int itemId = entry.getKey();
                            for (Map.Entry<String, PartialOffer> offerEntry : entry.getValue().entrySet()) {
                                PartialOffer po = offerEntry.getValue();
                                if (po != null && po.getAmountConsumed() > 0) {
                                    outputPs.setLong(1, recipeFlipId);
                                    outputPs.setInt(2, itemId);
                                    outputPs.setString(3, po.getOfferUuid());
                                    outputPs.setInt(4, po.getAmountConsumed());
                                    outputPs.addBatch();
                                }
                            }
                        }
                    }

                    // Insert consumed_trade entries linking this recipe event to the underlying
                    // trades (clamped to each trade's remaining quantity by the guarded SQL).
                    if (flip.getInputs() != null) {
                        for (Map.Entry<Integer, Map<String, PartialOffer>> entry : flip.getInputs().entrySet()) {
                            for (Map.Entry<String, PartialOffer> offerEntry : entry.getValue().entrySet()) {
                                PartialOffer po = offerEntry.getValue();
                                if (po != null && po.getAmountConsumed() > 0 && po.getOfferUuid() != null) {
                                    Long tradeId = offerUuidToTradeId.get(po.getOfferUuid());
                                    if (tradeId != null) {
                                        consumedPs.setLong(1, tradeId);
                                        consumedPs.setLong(2, eventId);
                                        consumedPs.setInt(3, po.getAmountConsumed());
                                        consumedPs.setLong(4, tradeId);
                                        consumedPs.addBatch();
                                    }
                                }
                            }
                        }
                    }
                    if (flip.getOutputs() != null) {
                        for (Map.Entry<Integer, Map<String, PartialOffer>> entry : flip.getOutputs().entrySet()) {
                            for (Map.Entry<String, PartialOffer> offerEntry : entry.getValue().entrySet()) {
                                PartialOffer po = offerEntry.getValue();
                                if (po != null && po.getAmountConsumed() > 0 && po.getOfferUuid() != null) {
                                    Long tradeId = offerUuidToTradeId.get(po.getOfferUuid());
                                    if (tradeId != null) {
                                        consumedPs.setLong(1, tradeId);
                                        consumedPs.setLong(2, eventId);
                                        consumedPs.setInt(3, po.getAmountConsumed());
                                        consumedPs.setLong(4, tradeId);
                                        consumedPs.addBatch();
                                    }
                                }
                            }
                        }
                    }

                    totalRecipeFlips++;
                }
            }

            // Execute batches
            inputPs.executeBatch();
            outputPs.executeBatch();
            consumedPs.executeBatch();
        }

        return totalRecipeFlips;
    }

    /**
     * Migrate local recipes to the recipes table.
     */
    public int migrateLocalRecipes(List<Recipe> localRecipes) {
        if (localRecipes == null || localRecipes.isEmpty()) {
            log.info("No local recipes to migrate.");
            return 0;
        }

        Gson gson = new Gson();
        int count = 0;

        try {
            String sql = "INSERT OR REPLACE INTO recipes (recipe_key, name, inputs_json, outputs_json) VALUES (?, ?, ?, ?)";
            try (PreparedStatement ps = storage.getConnection().prepareStatement(sql)) {
                for (Recipe recipe : localRecipes) {
                    if (recipe == null) continue;

                    String recipeKey = com.flippingutilities.controller.RecipeHandler.createRecipeKey(recipe);
                    String name = recipe.getName();
                    String inputsJson = gson.toJson(recipe.getInputs());
                    String outputsJson = gson.toJson(recipe.getOutputs());

                    ps.setString(1, recipeKey);
                    ps.setString(2, name);
                    ps.setString(3, inputsJson);
                    ps.setString(4, outputsJson);
                    ps.addBatch();
                    count++;
                }
                ps.executeBatch();
            }
        } catch (SQLException e) {
            log.error("Error migrating local recipes", e);
        }

        log.info("Migrated {} local recipes", count);
        return count;
    }

    /**
     * Hydrate PartialOffers in recipe flips by linking them to their corresponding OfferEvents.
     * This is necessary because RecipeFlip.getProfit()/getExpense() depend on
     * po.getOffer() being non-null to compute correct values.
     */
    private void hydrateRecipeFlipOffers(List<RecipeFlipGroup> recipeFlipGroups, List<FlippingItem> tradeItems) {
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

        // Hydrate each PartialOffer in recipe flips
        for (RecipeFlipGroup group : recipeFlipGroups) {
            for (RecipeFlip flip : group.getRecipeFlips()) {
                for (PartialOffer po : flip.getPartialOffers()) {
                    po.hydrateOffer(offersByUuid);
                }
            }
        }
    }

    /**
     * Migrate favorites from JSON FlippingItem objects to the item_favorites table.
     */
    private int migrateFavorites() {
        Map<String, AccountData> accounts = tradePersister.loadAllAccounts();
        if (accounts == null || accounts.isEmpty()) return 0;

        int count = 0;
        for (Map.Entry<String, AccountData> entry : accounts.entrySet()) {
            count += migrateFavoritesForAccount(entry.getKey(), entry.getValue());
        }
        return count;
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
            // Only persist actual favorites. The previous predicate
            // `isFavorite() || !"1".equals(getFavoriteCode())` erroneously migrated any item
            // whose code wasn't the default "1" (including null), polluting the table.
            if (item.isFavorite()) {
                storage.upsertFavorite(displayName, item.getItemId(), true, item.getFavoriteCode());
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
        final int price;
        final boolean isBuy;
        final long tax;

        TradeRecord(int accountId, int itemId, String uuid, long timestamp, int qty, int price, boolean isBuy, long tax) {
            this.accountId = accountId;
            this.itemId = itemId;
            this.uuid = uuid;
            this.timestamp = timestamp;
            this.qty = qty;
            this.price = price;
            this.isBuy = isBuy;
            // Preserve the caller-supplied tax (the actual amount recorded on the OfferEvent)
            // rather than recomputing via TaxCalculator, which can diverge if the formula or
            // historical brackets change.
            this.tax = tax;
        }
    }

    private static class FlipRecord {
        final int accountId;
        final long timestamp;
        final long cost;
        final long profit;
        final String note;
        final String naturalKey;
        final List<long[]> consumedTrades;

        FlipRecord(int accountId, long timestamp, long cost, long profit, String note, String naturalKey, List<long[]> consumedTrades) {
            this.accountId = accountId;
            this.timestamp = timestamp;
            this.cost = cost;
            this.profit = profit;
            this.note = note;
            this.naturalKey = naturalKey;
            this.consumedTrades = consumedTrades;
        }
    }
}
