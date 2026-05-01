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

package com.flippingutilities.db;

import com.flippingutilities.utilities.Constants;
import net.runelite.client.game.ItemManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * SQLite-based FlipRepository.
 * Loads data on-demand via SQL queries - minimal in-memory state.
 */
public class SqliteFlipRepository implements FlipRepository {
    private static final Logger log = LoggerFactory.getLogger(SqliteFlipRepository.class);
    
    private final SqliteStorage storage;
    private final ItemManager itemManager;
    
    public SqliteFlipRepository(SqliteStorage storage, ItemManager itemManager) {
        this.storage = storage;
        this.itemManager = itemManager;
    }
    
    @Override
    public List<ItemSummary> getItemSummaries(String account, Instant since, String sortBy, int limit, int offset) {
        List<ItemSummary> results = new ArrayList<>();
        Integer accountId = storage.getAccountId(account);
        if (accountId == null) {
            return results;
        }

        // Map sortBy to column
        String orderColumn = "latest_timestamp";
        if ("PROFIT".equalsIgnoreCase(sortBy)) {
            orderColumn = "total_profit";
        } else if ("ROI".equalsIgnoreCase(sortBy)) {
            orderColumn = "roi";
        }

        long sinceMillis = since != null ? since.toEpochMilli() : 0L;

        // Calculate profit from completed flips (events table) grouped by item
        // This matches how getAggregateStats calculates total profit
        // Use DISTINCT subquery to avoid double-counting when both buy and sell trades have same item_id
        String sql = "SELECT " +
            "t.item_id, " +
            "SUM(t.qty) as total_qty, " +
            "SUM(CASE WHEN t.is_buy = 1 THEN t.qty ELSE 0 END) as buy_qty, " +
            "SUM(CASE WHEN t.is_buy = 0 THEN t.qty ELSE 0 END) as sell_qty, " +
            "COALESCE((" +
            "  SELECT SUM(sub.profit) FROM (" +
            "    SELECT DISTINCT e.id, e.profit FROM events e " +
            "    JOIN consumed_trade ct ON ct.event_id = e.id " +
            "    JOIN trades ct_trade ON ct_trade.id = ct.trade_id " +
            "    WHERE e.account_id = ? AND e.timestamp > ? AND e.type = 'flip' AND ct_trade.item_id = t.item_id" +
            "  ) sub" +
            "), 0) as total_profit, " +
            "COALESCE((" +
            "  SELECT SUM(sub.cost) FROM (" +
            "    SELECT DISTINCT e.id, e.cost FROM events e " +
            "    JOIN consumed_trade ct ON ct.event_id = e.id " +
            "    JOIN trades ct_trade ON ct_trade.id = ct.trade_id " +
            "    WHERE e.account_id = ? AND e.timestamp > ? AND e.type = 'flip' AND ct_trade.item_id = t.item_id" +
            "  ) sub" +
            "), 0) as total_cost, " +
            "MAX(t.timestamp) as latest_timestamp, " +
            "COALESCE(fav.is_favorite, 0) as is_favorite " +
            "FROM trades t " +
            "LEFT JOIN item_favorites fav ON fav.account_id = t.account_id AND fav.item_id = t.item_id " +
            "WHERE t.account_id = ? AND t.timestamp > ? " +
            "GROUP BY t.item_id " +
            "ORDER BY " + orderColumn + " DESC " +
            "LIMIT ? OFFSET ?";

        try (Connection conn = storage.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            ps.setLong(2, sinceMillis);
            ps.setInt(3, accountId);
            ps.setLong(4, sinceMillis);
            ps.setInt(5, accountId);
            ps.setLong(6, sinceMillis);
            ps.setInt(7, limit);
            ps.setInt(8, offset);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int itemId = rs.getInt("item_id");
                    String itemName = getItemName(itemId);
                    int totalQty = rs.getInt("total_qty");
                    int buyQty = rs.getInt("buy_qty");
                    int sellQty = rs.getInt("sell_qty");
                    long totalProfit = rs.getLong("total_profit");
                    long totalCost = rs.getLong("total_cost");
                    // ROI = profit / cost * 100
                    double roi = totalCost > 0 ? (totalProfit * 100.0 / totalCost) : 0;
                    long latestTimestamp = rs.getLong("latest_timestamp");
                    boolean isFavorite = rs.getInt("is_favorite") == 1;

                    results.add(new ItemSummary(itemId, itemName, totalQty, buyQty, sellQty,
                        totalProfit, roi, latestTimestamp, isFavorite));
                }
            }
        } catch (SQLException e) {
            log.error("Error getting item summaries for account={}", account, e);
        }

        return results;
    }
    
    @Override
    public int getItemCount(String account, Instant since) {
        Integer accountId = storage.getAccountId(account);
        if (accountId == null) {
            return 0;
        }

        long sinceMillis = since != null ? since.toEpochMilli() : 0L;

        // Count all traded items (not filtered by consumed_trade)
        String sql = "SELECT COUNT(DISTINCT item_id) as item_count " +
            "FROM trades t " +
            "WHERE t.account_id = ? AND t.timestamp > ?";
        
        try (Connection conn = storage.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            ps.setLong(2, sinceMillis);
            
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("item_count");
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
        Integer accountId = storage.getAccountId(account);
        if (accountId == null) {
            return results;
        }
        
        long sinceMillis = since != null ? since.toEpochMilli() : 0L;
        
        String sql = "SELECT id, timestamp, qty, price, is_buy " +
            "FROM trades " +
            "WHERE account_id = ? AND item_id = ? AND timestamp > ? " +
            "ORDER BY timestamp DESC";
        
        try (Connection conn = storage.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            ps.setInt(2, itemId);
            ps.setLong(3, sinceMillis);
            
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
        } catch (SQLException e) {
            log.error("Error getting trades for account={}, itemId={}", account, itemId, e);
        }
        
        return results;
    }
    
@Override
    public AggregateStats getAggregateStats(String account, Instant since) {
        Integer accountId = storage.getAccountId(account);
        long sessionTime = getSessionTime(accountId);
        AggregateStats defaultStats = new AggregateStats(0L, 0L, 0L, 0, 0L, sessionTime);
        if (accountId == null) {
            return defaultStats;
        }
        
        long sinceMillis = since != null ? since.toEpochMilli() : 0L;

        // Query events for profit (completed flips)
        // Query trades for expense/revenue (unconsumed qty = potential future flips)
        // For partially consumed trades, only count the remaining (unconsumed) quantity
        String sql = "SELECT " +
            "COALESCE((SELECT SUM(e.profit) FROM events e WHERE e.account_id = ? AND e.timestamp > ?), 0) as total_profit, " +
            "COALESCE((SELECT SUM((t.qty - COALESCE(ct.qty, 0)) * t.price) FROM trades t " +
            "  LEFT JOIN consumed_trade ct ON t.id = ct.trade_id " +
            "  WHERE t.account_id = ? AND t.timestamp > ? AND (ct.id IS NULL OR ct.qty < t.qty) AND t.is_buy = 1), 0) as total_expense, " +
            "COALESCE((SELECT SUM((t.qty - COALESCE(ct.qty, 0)) * t.price) FROM trades t " +
            "  LEFT JOIN consumed_trade ct ON t.id = ct.trade_id " +
            "  WHERE t.account_id = ? AND t.timestamp > ? AND (ct.id IS NULL OR ct.qty < t.qty) AND t.is_buy = 0), 0) as total_revenue, " +
            "COALESCE((SELECT COUNT(*) FROM events e WHERE e.account_id = ? AND e.timestamp > ?), 0) as flip_count";

        try (Connection conn = storage.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            ps.setLong(2, sinceMillis);
            ps.setInt(3, accountId);
            ps.setLong(4, sinceMillis);
            ps.setInt(5, accountId);
            ps.setLong(6, sinceMillis);
            ps.setInt(7, accountId);
            ps.setLong(8, sinceMillis);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long profit = rs.getLong("total_profit");
                    long expense = rs.getLong("total_expense");
                    long revenue = rs.getLong("total_revenue");
                    int flipCount = rs.getInt("flip_count");
                    // Tax is calculated per trade: 1% of sell value, capped at 5M per trade
                    long tax = calculateTotalTax(accountId, sinceMillis);
                    return new AggregateStats(profit, expense, revenue, flipCount, tax, sessionTime);
                }
            }
        } catch (SQLException e) {
            log.error("Error getting aggregate stats for account={}", account, e);
        }
        
        return defaultStats;
    }

    /**
     * Calculate total tax by summing per-trade tax.
     * Tax rules:
     * - Before GE_TAX_START: no tax
     * - Between GE_TAX_START and GE_TAX_INCREASED: 1% tax, capped at 5M per trade
     * - After GE_TAX_INCREASED: 2% tax, capped at 5M per trade
     * - TAX_EXEMPT_ITEMS: always exempt
     * - NEW_TAX_EXEMPT_ITEMS: exempt after GE_TAX_INCREASED
     */
    private long calculateTotalTax(int accountId, long sinceMillis) {
        String sql = "SELECT item_id, timestamp, qty, price FROM trades WHERE account_id = ? AND timestamp > ? AND is_buy = 0";
        long totalTax = 0L;

        try (Connection conn = storage.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            ps.setLong(2, sinceMillis);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int itemId = rs.getInt("item_id");
                    long timestamp = rs.getLong("timestamp");
                    int qty = rs.getInt("qty");
                    int price = rs.getInt("price");

                    long tradeTax = calculateTaxForTrade(itemId, timestamp, qty, price);
                    totalTax += tradeTax;
                }
            }
        } catch (SQLException e) {
            log.error("Error calculating tax for accountId={}", accountId, e);
        }

        return totalTax;
    }

    /**
     * Calculate tax for a single trade based on item, timestamp, quantity, and price.
     */
    private long calculateTaxForTrade(int itemId, long timestamp, int qty, int price) {
        long epochSeconds = timestamp / 1000;

        // No tax before GE tax was introduced
        if (epochSeconds < Constants.GE_TAX_START) {
            return 0;
        }

        // Always exempt items
        if (Constants.TAX_EXEMPT_ITEMS.contains(itemId)) {
            return 0;
        }

        // New exempt items (only after tax increase)
        if (epochSeconds >= Constants.GE_TAX_INCREASED
            && Constants.NEW_TAX_EXEMPT_ITEMS.contains(itemId)) {
            return 0;
        }

        long tradeValue = (long) qty * price;

        // Calculate tax based on time period
        if (epochSeconds < Constants.GE_TAX_INCREASED) {
            // 1% tax period
            if (tradeValue >= Constants.OLD_MAX_PRICE_FOR_GE_TAX) {
                return Constants.GE_TAX_CAP;
            }
            return (long) Math.floor(tradeValue * Constants.OLD_GE_TAX);
        } else {
            // 2% tax period
            if (tradeValue >= Constants.MAX_PRICE_FOR_GE_TAX) {
                return Constants.GE_TAX_CAP;
            }
            return (long) Math.floor(tradeValue * Constants.GE_TAX);
        }
    }
    
    @Override
    public List<String> getAccountNames() {
        return storage.listAccounts();
    }
    
    @Override
    public void recordTrade(String account, int itemId, long timestamp, int qty, int price, boolean isBuy) {
        storage.insertTrade(account, itemId, timestamp, qty, price, isBuy);
    }
    
    @Override
    public Map<String, Object> getGeLimitState(String account, int itemId) {
        return storage.loadGeLimitState(account, itemId);
    }
    
    @Override
    public void updateGeLimitState(String account, int itemId, Instant nextRefresh, int itemsBought) {
        storage.upsertGeLimitState(account, itemId, nextRefresh, itemsBought);
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
        try (Connection conn = storage.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT accumulated_time FROM accounts WHERE id = ?")) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long time = rs.getLong("accumulated_time");
                    return rs.wasNull() ? 0L : time;
                }
            }
        } catch (SQLException e) {
            log.debug("Could not get session time for accountId={}", accountId);
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
