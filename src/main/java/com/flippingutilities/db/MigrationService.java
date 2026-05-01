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
import java.sql.Savepoint;
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
     * @return Number of accounts migrated
     */
    public int migrate() {
        log.info("[MigrationService] Starting JSON -> SQLite migration...");
        long startTime = System.currentTimeMillis();
        storage.initializeSchema();

        int accountsMigrated = 0;
        int totalTrades = 0;
        int totalFlips = 0;
        int totalRecipeFlips = 0;

        // Use TradePersister to load all accounts
        Map<String, AccountData> accounts = tradePersister.loadAllAccounts();

        if (accounts == null || accounts.isEmpty()) {
            log.info("[MigrationService] No account data found to migrate.");
            return 0;
        }

        try (Connection conn = storage.getConnection()) {
            // Disable auto-commit for batch performance
            conn.setAutoCommit(false);

            for (Map.Entry<String, AccountData> entry : accounts.entrySet()) {
                String displayName = entry.getKey();
                AccountData accountData = entry.getValue();
                Savepoint accountSavepoint = conn.setSavepoint();

                try {
                    int[] counts = migrateAccountBatched(conn, displayName, accountData);
                    accountsMigrated++;
                    totalTrades += counts[0];
                    totalFlips += counts[1];
                    totalRecipeFlips += counts[2];
                    log.info("[MigrationService] Migrated account: {} ({} trades, {} flips, {} recipe flips)",
                        displayName, counts[0], counts[1], counts[2]);
                } catch (Exception e) {
                    log.error("[MigrationService] Failed to migrate account: {}", displayName, e);
                    conn.rollback(accountSavepoint);
                }
            }

            conn.commit();
        } catch (SQLException e) {
            log.error("[MigrationService] Database error during migration", e);
            return accountsMigrated;
        }

        // Migrate local recipes (stored in AccountWideData)
        try {
            com.flippingutilities.model.AccountWideData accountWideData = tradePersister.loadAccountWideData();
            if (accountWideData != null) {
                // Persist entire AccountWideData as JSON blob in settings
                Gson gson = new Gson();
                String json = gson.toJson(accountWideData);
                storage.setSetting("accountwide_data", json);
                log.info("[MigrationService] Migrated AccountWideData to settings table");

                // Also migrate local recipes to recipes table
                if (accountWideData.getLocalRecipes() != null && !accountWideData.getLocalRecipes().isEmpty()) {
                    int localRecipesCount = migrateLocalRecipes(accountWideData.getLocalRecipes());
                    log.info("[MigrationService] Migrated {} local recipes", localRecipesCount);
                }
            }
        } catch (Exception e) {
            log.warn("[MigrationService] Could not migrate AccountWideData", e);
        }

        // Migrate favorites from FlippingItem.favorite/favoriteCode
        try {
            int favoritesMigrated = migrateFavorites();
            if (favoritesMigrated > 0) {
                log.info("[MigrationService] Migrated {} item favorites", favoritesMigrated);
            }
        } catch (Exception e) {
            log.warn("[MigrationService] Could not migrate favorites", e);
        }

        if (accountsMigrated == accounts.size()) {
            storage.setSetting("migration_completed", "true");
            storage.setSetting("migration_completed_at", Instant.now().toString());
        } else {
            log.warn("[MigrationService] Migration incomplete: {}/{} accounts migrated. Will retry on next startup.",
                accountsMigrated, accounts.size());
            storage.setSetting("migration_completed", "false");
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[MigrationService] Migration complete. Accounts: {}, Trades: {}, Flips: {}, Recipe Flips: {}, Time: {}ms",
            accountsMigrated, totalTrades, totalFlips, totalRecipeFlips, elapsed);
        return accountsMigrated;
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
            log.warn("[MigrationService] Account data is null for: {}", displayName);
            return new int[]{0, 0, 0};
        }

        List<FlippingItem> tradeItems = accountData.getTrades();
        if (tradeItems == null || tradeItems.isEmpty()) {
            log.debug("[MigrationService] No trades to migrate for: {}", displayName);
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
                int qty = offer.getCurrentQuantityInTrade();
                // Reduce qty by recipe flip consumption to avoid double-counting
                Integer consumed = recipeConsumptionByUuid.get(offer.getUuid());
                if (consumed != null) {
                    qty -= consumed;
                }
                // Clamp to 0: fully-consumed trades still get inserted (qty=0)
                // so recipe events can link to them via consumed_trade.
                int insertQty = Math.max(0, qty);
                int price = offer.getPreTaxPrice();
                boolean isBuy = offer.isBuy();

                tradesToInsert.add(new TradeRecord(accountId, item.getItemId(), offer.getUuid(), timestamp, insertQty, price, isBuy));

                // Only include non-fully-consumed offers for regular flip computation
                if (qty > 0) {
                    if (consumed != null && consumed > 0) {
                        OfferEvent adjusted = offer.clone();
                        adjusted.setCurrentQuantityInTrade(qty);
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
                    upsertGeLimitStateBatched(conn, accountId, item.getItemId(), resetTime, item.getItemsBoughtThisLimitWindow());
                }
            } catch (Exception e) {
                log.debug("[MigrationService] Could not migrate GE limit for item {}: {}", item.getItemId(), e.getMessage());
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

        // Create new account
        String insertSql = "INSERT INTO accounts (display_name, player_id, session_start, accumulated_time) VALUES (?, ?, ?, ?)";
        long now = Instant.now().toEpochMilli();
        try (PreparedStatement ps = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, displayName);
            ps.setString(2, displayName); // player_id placeholder
            ps.setLong(3, now);
            ps.setLong(4, 0L);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return -1;
    }

    private void batchInsertTrades(Connection conn, List<TradeRecord> trades) throws SQLException {
        String sql = "INSERT INTO trades (account_id, item_id, uuid, timestamp, qty, price, is_buy) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int count = 0;
            for (TradeRecord trade : trades) {
                ps.setInt(1, trade.accountId);
                ps.setInt(2, trade.itemId);
                ps.setString(3, trade.uuid);
                ps.setLong(4, trade.timestamp);
                ps.setInt(5, trade.qty);
                ps.setInt(6, trade.price);
                ps.setInt(7, trade.isBuy ? 1 : 0);
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

        // Collect all flips and consumed trades for batch insert
        List<FlipRecord> flipsToInsert = new ArrayList<>();

        for (Map.Entry<Integer, List<OfferEvent>> entry : offersByItem.entrySet()) {
            int itemId = entry.getKey();
            List<OfferEvent> offers = entry.getValue();

            // Filter out null offers
            List<OfferEvent> validOffers = offers.stream()
                .filter(o -> o != null)
                .collect(Collectors.toList());

            log.debug("[MigrationService] Item {}: {} offers total", itemId, validOffers.size());
            if (validOffers.isEmpty()) continue;

            // Clone offers before processing to avoid modifying original data
            // HistoryManager.getFlips modifies currentQuantityInTrade in place
            List<OfferEvent> clonedOffers = validOffers.stream()
                .map(OfferEvent::clone)
                .collect(Collectors.toList());

            // Set madeBy on cloned offers so groupingBy works
            clonedOffers.forEach(o -> o.setMadeBy("migrated"));

            List<Flip> flips = HistoryManager.getFlips(clonedOffers);
            log.debug("[MigrationService] Item {}: getFlips returned {} flips", itemId, flips == null ? 0 : flips.size());
            if (flips == null || flips.isEmpty()) continue;

            // Compute proportional profit per item (same as JSON backend's FlippingItem.getProfit)
            // Need fresh clones since getFlips mutates currentQuantityInTrade
            List<OfferEvent> clonedForProfit = validOffers.stream()
                .map(OfferEvent::clone)
                .collect(Collectors.toList());
            clonedForProfit.forEach(o -> o.setMadeBy("migrated"));
            long proportionalProfit = FlippingItem.getProfit(clonedForProfit);

            // Compute FIFO total profit for scaling
            long fifoTotalProfit = 0;
            for (Flip f : flips) {
                if (f == null || f.getTime() == null) continue;
                fifoTotalProfit += (long) (f.getSellPrice() - f.getBuyPrice()) * f.getQuantity();
            }

            // Load trade IDs for this item
            Map<Long, Map<String, Object>> tradesById = loadTradesByItemAsMap(conn, accountId, itemId);

            // Sort trades by timestamp for FIFO
            List<Map<String, Object>> sortedTrades = new ArrayList<>(tradesById.values());
            sortedTrades.sort(Comparator.comparingLong(t -> (Long) t.get("timestamp")));

            // Create mutable queues with remaining quantities
            // Each entry: [tradeId, remainingQty]
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

            // Process each flip - consume trades using FIFO and scale profit to match proportional matching
            long accumulatedScaledProfit = 0;
            int flipIndex = 0;
            for (Flip flip : flips) {
                if (flip == null || flip.getTime() == null) continue;

                int flipQty = flip.getQuantity();
                int buyPrice = flip.getBuyPrice();
                int sellPrice = flip.getSellPrice();

                List<long[]> consumedTrades = new ArrayList<>();

                // Consume from buy queue (FIFO) - no price matching needed
                int qtyNeeded = flipQty;
                while (qtyNeeded > 0 && !buyQueue.isEmpty()) {
                    long[] buyEntry = buyQueue.peekFirst();
                    if (buyEntry == null) break;

                    long tradeId = buyEntry[0];
                    int remaining = (int) buyEntry[1];

                    if (remaining <= 0) {
                        buyQueue.pollFirst(); // Remove exhausted trade
                        continue;
                    }

                    int consume = Math.min(qtyNeeded, remaining);
                    consumedTrades.add(new long[]{tradeId, consume});
                    buyEntry[1] = remaining - consume;
                    qtyNeeded -= consume;

                    if (buyEntry[1] <= 0) {
                        buyQueue.pollFirst(); // Remove exhausted trade
                    }
                }

                // Consume from sell queue (FIFO) - no price matching needed
                qtyNeeded = flipQty;
                while (qtyNeeded > 0 && !sellQueue.isEmpty()) {
                    long[] sellEntry = sellQueue.peekFirst();
                    if (sellEntry == null) break;

                    long tradeId = sellEntry[0];
                    int remaining = (int) sellEntry[1];

                    if (remaining <= 0) {
                        sellQueue.pollFirst(); // Remove exhausted trade
                        continue;
                    }

                    int consume = Math.min(qtyNeeded, remaining);
                    consumedTrades.add(new long[]{tradeId, consume});
                    sellEntry[1] = remaining - consume;
                    qtyNeeded -= consume;

                    if (sellEntry[1] <= 0) {
                        sellQueue.pollFirst(); // Remove exhausted trade
                    }
                }

                // Scale profit to match proportional matching (same as JSON backend)
                long fifoProfit = (long) (sellPrice - buyPrice) * flipQty;
                long scaledProfit;
                flipIndex++;
                if (flipIndex == flips.size()) {
                    // Last flip for this item: use remaining profit to avoid rounding errors
                    scaledProfit = proportionalProfit - accumulatedScaledProfit;
                } else if (fifoTotalProfit != 0) {
                    scaledProfit = Math.round(fifoProfit * (double) proportionalProfit / fifoTotalProfit);
                    accumulatedScaledProfit += scaledProfit;
                } else {
                    scaledProfit = 0;
                }

                long cost = (long) buyPrice * flipQty;
                long timestamp = flip.getTime().toEpochMilli();
                String note = flip.isMarginCheck() ? "margin_check" : null;

                flipsToInsert.add(new FlipRecord(accountId, timestamp, cost, scaledProfit, note, consumedTrades));
                flipsCount++;
            }
        }

        // Batch insert flips and consumed trades
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
        String eventSql = "INSERT INTO events (account_id, timestamp, type, cost, profit, note) VALUES (?, ?, 'flip', ?, ?, ?)";
        String consumedSql = "INSERT INTO consumed_trade (trade_id, qty, event_id) VALUES (?, ?, ?)";

        try (PreparedStatement eventPs = conn.prepareStatement(eventSql, Statement.RETURN_GENERATED_KEYS);
             PreparedStatement consumedPs = conn.prepareStatement(consumedSql)) {

            int consumedCount = 0;
            for (FlipRecord flip : flips) {
                // Insert event one at a time to capture generated key immediately
                eventPs.setInt(1, flip.accountId);
                eventPs.setLong(2, flip.timestamp);
                eventPs.setLong(3, flip.cost);
                eventPs.setLong(4, flip.profit);
                if (flip.note == null) {
                    eventPs.setNull(5, Types.VARCHAR);
                } else {
                    eventPs.setString(5, flip.note);
                }
                eventPs.executeUpdate();

                long eventId;
                try (ResultSet rs = eventPs.getGeneratedKeys()) {
                    if (!rs.next()) continue;
                    eventId = rs.getLong(1);
                }

                // Insert consumed trades for this event
                if (flip.consumedTrades != null) {
                    for (long[] consumed : flip.consumedTrades) {
                        consumedPs.setLong(1, consumed[0]); // trade_id
                        consumedPs.setInt(2, (int) consumed[1]); // qty
                        consumedPs.setLong(3, eventId);
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

    private void upsertGeLimitStateBatched(Connection conn, int accountId, int itemId, Instant nextRefresh, int itemsBought) throws SQLException {
        String sql = "INSERT OR REPLACE INTO ge_limit_state (account_id, item_id, next_refresh, items_bought) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            ps.setInt(2, itemId);
            ps.setLong(3, nextRefresh.toEpochMilli());
            ps.setInt(4, itemsBought);
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

        String eventSql = "INSERT INTO events (account_id, timestamp, type, cost, profit, note) VALUES (?, ?, 'recipe', ?, ?, ?)";
        String recipeFlipSql = "INSERT INTO recipe_flips (event_id, recipe_key, coin_cost) VALUES (?, ?, ?)";
        String inputSql = "INSERT INTO recipe_flip_inputs (recipe_flip_id, item_id, offer_uuid, amount_consumed) VALUES (?, ?, ?, ?)";
        String outputSql = "INSERT INTO recipe_flip_outputs (recipe_flip_id, item_id, offer_uuid, amount_consumed) VALUES (?, ?, ?, ?)";
        String consumedSql = "INSERT INTO consumed_trade (trade_id, qty, event_id) VALUES (?, ?, ?)";

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

                    // Insert event
                    eventPs.setInt(1, accountId);
                    eventPs.setLong(2, timestamp);
                    eventPs.setLong(3, cost);
                    eventPs.setLong(4, profit);
                    eventPs.setNull(5, Types.VARCHAR);
                    eventPs.executeUpdate();

                    long eventId;
                    try (ResultSet rs = eventPs.getGeneratedKeys()) {
                        if (rs.next()) {
                            eventId = rs.getLong(1);
                        } else {
                            continue;
                        }
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

                    // Insert consumed_trade entries linking this recipe event to the underlying trades
                    if (flip.getInputs() != null) {
                        for (Map.Entry<Integer, Map<String, PartialOffer>> entry : flip.getInputs().entrySet()) {
                            for (Map.Entry<String, PartialOffer> offerEntry : entry.getValue().entrySet()) {
                                PartialOffer po = offerEntry.getValue();
                                if (po != null && po.getAmountConsumed() > 0 && po.getOfferUuid() != null) {
                                    Long tradeId = offerUuidToTradeId.get(po.getOfferUuid());
                                    if (tradeId != null) {
                                        consumedPs.setLong(1, tradeId);
                                        consumedPs.setInt(2, po.getAmountConsumed());
                                        consumedPs.setLong(3, eventId);
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
                                        consumedPs.setInt(2, po.getAmountConsumed());
                                        consumedPs.setLong(3, eventId);
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
            log.info("[MigrationService] No local recipes to migrate.");
            return 0;
        }

        Gson gson = new Gson();
        int count = 0;

        try (Connection conn = storage.getConnection()) {
            String sql = "INSERT OR REPLACE INTO recipes (recipe_key, name, inputs_json, outputs_json) VALUES (?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
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
            log.error("[MigrationService] Error migrating local recipes", e);
        }

        log.info("[MigrationService] Migrated {} local recipes", count);
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
            if (item.isFavorite() || !"1".equals(item.getFavoriteCode())) {
                storage.upsertFavorite(displayName, item.getItemId(), item.isFavorite(), item.getFavoriteCode());
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

        TradeRecord(int accountId, int itemId, String uuid, long timestamp, int qty, int price, boolean isBuy) {
            this.accountId = accountId;
            this.itemId = itemId;
            this.uuid = uuid;
            this.timestamp = timestamp;
            this.qty = qty;
            this.price = price;
            this.isBuy = isBuy;
        }
    }

    private static class FlipRecord {
        final int accountId;
        final long timestamp;
        final long cost;
        final long profit;
        final String note;
        final List<long[]> consumedTrades;

        FlipRecord(int accountId, long timestamp, long cost, long profit, String note, List<long[]> consumedTrades) {
            this.accountId = accountId;
            this.timestamp = timestamp;
            this.cost = cost;
            this.profit = profit;
            this.note = note;
            this.consumedTrades = consumedTrades;
        }
    }
}
