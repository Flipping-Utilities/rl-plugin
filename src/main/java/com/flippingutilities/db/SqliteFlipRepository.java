package com.flippingutilities.db;

import net.runelite.client.game.ItemManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * SQLite-based FlipRepository.
 * Loads data on-demand via SQL queries - minimal in-memory state.
 *
 * <p>Profit/ROI for an item is sourced from the {@code events} table, which is populated by
 * {@link MigrationService} at migration time and incrementally maintained by
 * {@link SqliteStorage#reconcileFlipsForItem} on each live {@link #recordTrade} call. Partial
 * GE fills each emit their own flip event for the matched quantity, so profit grows as sells
 * arrive against an existing buy.
 */
public class SqliteFlipRepository implements FlipRepository {
    private static final Logger log = LoggerFactory.getLogger(SqliteFlipRepository.class);

    private final SqliteStorage storage;
    private final ItemManager itemManager;

    /**
     * Optional in-memory view used for user-facing aggregate/item queries. The panels are
     * driven by the in-memory model in BOTH modes, and interval-scoped aggregates recomputed
     * from interval offers (this is what the JSON repository does) do not always agree with
     * the globally-materialized events table (e.g. an account that sold more than it bought
     * has later sells paired differently in each view). Delegating keeps the totals and the
     * item rows consistent with each other; SQLite remains the persistence/query layer.
     * Null in tests, which exercise the SQL paths directly.
     */
    private JsonFlipRepository memoryView;

    public SqliteFlipRepository(SqliteStorage storage, ItemManager itemManager) {
        this.storage = storage;
        this.itemManager = itemManager;
    }

    public void setMemoryView(JsonFlipRepository memoryView) {
        this.memoryView = memoryView;
    }

    /**
     * Returns true if the account selector is the synthetic "all accounts" view.
     */
    private static boolean isAccountWide(String account) {
        return account == null || com.flippingutilities.controller.FlippingPlugin.ACCOUNT_WIDE.equals(account);
    }

    @Override
    public List<ItemSummary> getItemSummaries(String account, Instant since, String sortBy, int limit, int offset) {
        if (memoryView != null) {
            return memoryView.getItemSummaries(account, since, sortBy, limit, offset);
        }
        List<ItemSummary> results = new ArrayList<>();

        // Map sortBy to column
        String orderColumn = "latest_timestamp";
        if ("PROFIT".equalsIgnoreCase(sortBy)) {
            orderColumn = "total_profit";
        } else if ("ROI".equalsIgnoreCase(sortBy)) {
            orderColumn = "roi";
        }

        long sinceMillis = since != null ? since.toEpochMilli() : 0L;

        // Quantity uses a CTE that pre-aggregates consumed_trade per trade so that partially
        // recipe-consumed trades contribute only their remaining qty (matching the JSON path's
        // getPartialOfferAdjustedView). Profit/cost are sourced from completed flip events
        // (type='flip') joined through consumed_trade to the item's trades.
        boolean accountWide = isAccountWide(account);
        String accountFilter = accountWide ? "" : "t.account_id = ? AND ";
        String eventAccountFilter = accountWide ? "" : "e.account_id = ? AND ";
        int eventParamCount = accountWide ? 0 : 2; // pairs of (accountId, since) in the profit/cost subqueries

        String sql = "WITH consumed AS (" +
            "  SELECT trade_id, SUM(qty) AS consumed_qty FROM consumed_trade GROUP BY trade_id" +
            ") " +
            "SELECT " +
            "t.item_id, " +
            "SUM(MAX(t.qty - COALESCE(c.consumed_qty, 0), 0)) AS total_qty, " +
            "SUM(CASE WHEN t.is_buy = 1 THEN MAX(t.qty - COALESCE(c.consumed_qty, 0), 0) ELSE 0 END) AS buy_qty, " +
            "SUM(CASE WHEN t.is_buy = 0 THEN MAX(t.qty - COALESCE(c.consumed_qty, 0), 0) ELSE 0 END) AS sell_qty, " +
            "COALESCE((" +
            "  SELECT SUM(sub.profit) FROM (" +
            "    SELECT DISTINCT e.id, e.profit FROM events e " +
            "    JOIN consumed_trade ct ON ct.event_id = e.id " +
            "    JOIN trades ct_trade ON ct_trade.id = ct.trade_id " +
            "    WHERE " + eventAccountFilter + " e.timestamp > ? AND e.type = 'flip' AND ct_trade.item_id = t.item_id" +
            "  ) sub" +
            "), 0) AS total_profit, " +
            "COALESCE((" +
            "  SELECT SUM(sub.cost) FROM (" +
            "    SELECT DISTINCT e.id, e.cost FROM events e " +
            "    JOIN consumed_trade ct ON ct.event_id = e.id " +
            "    JOIN trades ct_trade ON ct_trade.id = ct.trade_id " +
            "    WHERE " + eventAccountFilter + " e.timestamp > ? AND e.type = 'flip' AND ct_trade.item_id = t.item_id" +
            "  ) sub" +
            "), 0) AS total_cost, " +
            "MAX(t.timestamp) AS latest_timestamp, " +
            "COALESCE(fav.is_favorite, 0) AS is_favorite " +
            "FROM trades t " +
            "LEFT JOIN consumed c ON t.id = c.trade_id " +
            "LEFT JOIN item_favorites fav ON fav.account_id = t.account_id AND fav.item_id = t.item_id " +
            "WHERE " + accountFilter + "t.timestamp > ? " +
            "GROUP BY t.item_id " +
            "ORDER BY " + orderColumn + " DESC " +
            "LIMIT ? OFFSET ?";

        // The whole query (connection fetch -> result iteration) holds the storage monitor so
        // it cannot interleave with a migration account transaction or a backend switch closing
        // the shared connection mid-query.
        try {
            synchronized (storage) {
                Connection conn = storage.getConnection();
                Integer accountId = accountWide ? null : storage.getAccountId(account);
                if (!accountWide && accountId == null) {
                    // Unknown account: no data yet, not an error.
                    return results;
                }
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    int idx = 1;
                    // Profit subquery params
                    if (!accountWide) ps.setInt(idx++, accountId);
                    ps.setLong(idx++, sinceMillis);
                    // Cost subquery params
                    if (!accountWide) ps.setInt(idx++, accountId);
                    ps.setLong(idx++, sinceMillis);
                    // Main WHERE params
                    if (!accountWide) ps.setInt(idx++, accountId);
                    ps.setLong(idx++, sinceMillis);
                    // Pagination
                    ps.setInt(idx++, limit);
                    ps.setInt(idx++, offset);

                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            int itemId = rs.getInt("item_id");
                            String itemName = getItemName(itemId);
                            int totalQty = rs.getInt("total_qty");
                            int buyQty = rs.getInt("buy_qty");
                            int sellQty = rs.getInt("sell_qty");
                            long totalProfit = rs.getLong("total_profit");
                            long totalCost = rs.getLong("total_cost");
                            double roi = totalCost > 0 ? (totalProfit * 100.0 / totalCost) : 0;
                            long latestTimestamp = rs.getLong("latest_timestamp");
                            boolean isFavorite = rs.getInt("is_favorite") == 1;

                            results.add(new ItemSummary(itemId, itemName, totalQty, buyQty, sellQty,
                                totalProfit, roi, latestTimestamp, isFavorite));
                        }
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Error getting item summaries for account={}", account, e);
        }

        return results;
    }
    
    @Override
    public int getItemCount(String account, Instant since) {
        long sinceMillis = since != null ? since.toEpochMilli() : 0L;
        boolean accountWide = isAccountWide(account);

        String sql = "SELECT COUNT(DISTINCT item_id) as item_count " +
            "FROM trades t " +
            "WHERE " + (accountWide ? "" : "t.account_id = ? AND ") + "t.timestamp > ?";

        try {
            synchronized (storage) {
                Connection conn = storage.getConnection();
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    int idx = 1;
                    if (!accountWide) {
                        Integer accountId = storage.getAccountId(account);
                        if (accountId == null) return 0;
                        ps.setInt(idx++, accountId);
                    }
                    ps.setLong(idx++, sinceMillis);

                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return rs.getInt("item_count");
                        }
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Error getting item count for account={}", account, e);
        }

        return 0;
    }

    @Override
    public List<TradeRecord> getTradesForItem(String account, int itemId, Instant since) {
        List<TradeRecord> results = new ArrayList<>();
        boolean accountWide = isAccountWide(account);
        Integer accountId = accountWide ? null : storage.getAccountId(account);
        if (!accountWide && accountId == null) {
            return results;
        }

        long sinceMillis = since != null ? since.toEpochMilli() : 0L;

        // qty > 0 excludes cancelled/margin-check rows that the JSON path would also skip.
        // Account-wide view queries across all accounts, matching the JSON path's merged view.
        String sql = "SELECT id, timestamp, qty, price, is_buy " +
            "FROM trades " +
            "WHERE " + (accountWide ? "" : "account_id = ? AND ") + "item_id = ? AND timestamp > ? AND qty > 0 " +
            "ORDER BY timestamp DESC, id";

        try {
            synchronized (storage) {
                Connection conn = storage.getConnection();
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    int idx = 1;
                    if (!accountWide) ps.setInt(idx++, accountId);
                    ps.setInt(idx++, itemId);
                    ps.setLong(idx++, sinceMillis);

                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            results.add(new TradeRecord(
                                rs.getLong("id"),
                                itemId,
                                rs.getLong("timestamp"),
                                rs.getInt("qty"),
                                rs.getInt("price"),
                                rs.getInt("is_buy") == 1
                            ));
                        }
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Error getting trades for account={}, itemId={}", account, itemId, e);
        }

        return results;
    }
    
@Override
    public AggregateStats getAggregateStats(String account, Instant since) {
        // Delegate to the in-memory computation (same one the JSON backend and the item
        // panels use) so totals and item rows always agree. See setMemoryView.
        if (memoryView != null) {
            return memoryView.getAggregateStats(account, since);
        }

        boolean accountWide = isAccountWide(account);
        Integer accountId = accountWide ? null : storage.getAccountId(account);

        long sessionTime = accountWide ? getSessionTimeAcrossAccounts() : getSessionTime(accountId);
        AggregateStats defaultStats = new AggregateStats(0L, 0L, 0L, 0, 0L, sessionTime);
        if (!accountWide && accountId == null) {
            return defaultStats;
        }

        long sinceMillis = since != null ? since.toEpochMilli() : 0L;

        // Filter consistency: profit/expense/revenue come from ALL events (both 'flip' and
        // 'recipe' types) so that recipe-flip profit is included alongside regular flips,
        // matching the JSON path's getAggregateStats which sums both. flipCount counts all
        // events for the same reason. Tax is summed from sell-side trades directly.
        String acctFilter = accountWide ? "" : "account_id = ? AND ";
        String sql = "SELECT " +
            "COALESCE((SELECT SUM(e.profit) FROM events e WHERE " + acctFilter + "e.timestamp > ?), 0) as total_profit, " +
            "COALESCE((SELECT SUM(e.cost) FROM events e WHERE " + acctFilter + "e.timestamp > ?), 0) as total_expense, " +
            "COALESCE((SELECT SUM(e.cost + e.profit) FROM events e WHERE " + acctFilter + "e.timestamp > ?), 0) as total_revenue, " +
            "COALESCE((SELECT COUNT(*) FROM events e WHERE " + acctFilter + "e.timestamp > ?), 0) as flip_count, " +
            "COALESCE((SELECT SUM(tax) FROM trades WHERE " + acctFilter + "timestamp > ? AND is_buy = 0), 0) as total_tax";

        try {
            synchronized (storage) {
                Connection conn = storage.getConnection();
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    int idx = 1;
                    if (!accountWide) ps.setInt(idx++, accountId);
                    ps.setLong(idx++, sinceMillis);
                    if (!accountWide) ps.setInt(idx++, accountId);
                    ps.setLong(idx++, sinceMillis);
                    if (!accountWide) ps.setInt(idx++, accountId);
                    ps.setLong(idx++, sinceMillis);
                    if (!accountWide) ps.setInt(idx++, accountId);
                    ps.setLong(idx++, sinceMillis);
                    if (!accountWide) ps.setInt(idx++, accountId);
                    ps.setLong(idx++, sinceMillis);

                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            long profit = rs.getLong("total_profit");
                            long expense = rs.getLong("total_expense");
                            long revenue = rs.getLong("total_revenue");
                            int flipCount = rs.getInt("flip_count");
                            long tax = rs.getLong("total_tax");
                            return new AggregateStats(profit, expense, revenue, flipCount, tax, sessionTime);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Error getting aggregate stats for account={}", account, e);
        }

        return defaultStats;
    }

    /**
     * Sum accumulated_time across all accounts (for the ACCOUNT_WIDE view).
     */
    private long getSessionTimeAcrossAccounts() {
        try {
            synchronized (storage) {
                Connection conn = storage.getConnection();
                try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(SUM(accumulated_time), 0) AS total FROM accounts");
                     ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getLong("total");
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("Could not get aggregate session time");
        }
        return 0L;
    }

    @Override
    public List<String> getAccountNames() {
        return storage.listAccounts();
    }
    
    @Override
    public void recordTrade(String account, int itemId, String uuid, long timestamp, int qty, int price, boolean isBuy, long taxPaid) {
        // Insert the trade, then incrementally reconcile flip events so the item shows profit
        // immediately (partial fills each emit their own event for the matched qty).
        // The uuid deduplicates re-records of the same offer and lets recipe flips link back
        // to their trades. Best-effort: errors are logged, not thrown.
        try {
            Integer accountId = storage.getAccountId(account);
            if (accountId == null) {
                storage.upsertAccount(account, null);
                accountId = storage.getAccountId(account);
                if (accountId == null) {
                    log.warn("Could not create account {} for live trade recording", account);
                    return;
                }
            }

            int tradeId = storage.insertTrade(account, itemId, uuid, timestamp, qty, price, isBuy, taxPaid);
            if (tradeId < 0) {
                // -1 strictly means "duplicate offer (account_id, uuid) already recorded";
                // real failures throw IllegalStateException from insertTrade and land in the
                // catch below instead of being silently dropped.
                return;
            }
            storage.reconcileFlipsForItem(accountId, itemId);
        } catch (Exception e) {
            log.warn("Trade recording/reconciliation failed for account={}, item={} (best-effort; JSON dual-write keeps a copy)",
                account, itemId, e);
        }
    }
    
    @Override
    public Map<String, Object> getGeLimitState(String account, int itemId) {
        return storage.loadGeLimitState(account, itemId);
    }
    
    @Override
    public void updateGeLimitState(String account, int itemId, Instant nextRefresh, int itemsBought, int itemsBoughtThroughCompleteOffers) {
        storage.upsertGeLimitState(account, itemId, nextRefresh, itemsBought, itemsBoughtThroughCompleteOffers);
    }
    
    @Override
    public void close() {
        storage.close();
    }

    @Override
    public void setFavorite(String account, int itemId, boolean isFavorite, String favoriteCode) {
        storage.upsertFavorite(account, itemId, isFavorite, favoriteCode);
    }

    @Override
    public Map<String, Object> getFavorite(String account, int itemId) {
        return storage.loadFavorite(account, itemId);
    }
    
    private long getSessionTime(Integer accountId) {
        if (accountId == null) return 0L;
        try {
            synchronized (storage) {
                Connection conn = storage.getConnection();
                try (PreparedStatement ps = conn.prepareStatement("SELECT accumulated_time FROM accounts WHERE id = ?")) {
                    ps.setInt(1, accountId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            long time = rs.getLong("accumulated_time");
                            return rs.wasNull() ? 0L : time;
                        }
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("Could not get session time for accountId={}", accountId);
        }
        return 0L;
    }
    
    private String getItemName(int itemId) {
        if (itemManager != null) {
            try {
                return itemManager.getItemComposition(itemId).getName();
            } catch (Exception e) {
                log.debug("Could not get item name for itemId={}", itemId);
            }
        }
        return "Item " + itemId;
    }
}
