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
        
        String sql = "SELECT " +
            "t.item_id, " +
            "SUM(t.qty) as total_qty, " +
            "SUM(CASE WHEN t.is_buy = 1 THEN t.qty ELSE 0 END) as buy_qty, " +
            "SUM(CASE WHEN t.is_buy = 0 THEN t.qty ELSE 0 END) as sell_qty, " +
            "SUM(CASE WHEN t.is_buy = 0 THEN t.qty * t.price ELSE -t.qty * t.price END) as total_profit, " +
            "CASE " +
            "  WHEN SUM(CASE WHEN t.is_buy = 1 THEN t.qty * t.price ELSE 0 END) > 0 " +
            "  THEN (SUM(CASE WHEN t.is_buy = 0 THEN t.qty * t.price ELSE -t.qty * t.price END) * 100.0 / " +
            "        SUM(CASE WHEN t.is_buy = 1 THEN t.qty * t.price ELSE 0 END)) " +
            "  ELSE 0 " +
            "END as roi, " +
            "MAX(t.timestamp) as latest_timestamp, " +
            "(SELECT value FROM settings WHERE key = 'favorite_' || t.item_id) as is_favorite " +
            "FROM trades t " +
            "LEFT JOIN consumed_trade ct ON t.id = ct.trade_id " +
            "WHERE t.account_id = ? AND t.timestamp > ? AND ct.id IS NULL " +
            "GROUP BY t.item_id " +
            "ORDER BY " + orderColumn + " DESC " +
            "LIMIT ? OFFSET ?";
        
        try (Connection conn = storage.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            ps.setLong(2, sinceMillis);
            ps.setInt(3, limit);
            ps.setInt(4, offset);
            
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int itemId = rs.getInt("item_id");
                    String itemName = getItemName(itemId);
                    int totalQty = rs.getInt("total_qty");
                    int buyQty = rs.getInt("buy_qty");
                    int sellQty = rs.getInt("sell_qty");
                    long totalProfit = rs.getLong("total_profit");
                    double roi = rs.getDouble("roi");
                    long latestTimestamp = rs.getLong("latest_timestamp");
                    boolean isFavorite = "true".equals(rs.getString("is_favorite"));
                    
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
        
        String sql = "SELECT COUNT(DISTINCT item_id) as item_count " +
            "FROM trades t " +
            "LEFT JOIN consumed_trade ct ON t.id = ct.trade_id " +
            "WHERE t.account_id = ? AND t.timestamp > ? AND ct.id IS NULL";
        
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
        // Query trades for expense/revenue (unconsumed trades = potential future flips)
        String sql = "SELECT " +
            "COALESCE((SELECT SUM(e.profit) FROM events e WHERE e.account_id = ? AND e.timestamp > ?), 0) as total_profit, " +
            "COALESCE((SELECT SUM(t.qty * t.price) FROM trades t " +
            "  LEFT JOIN consumed_trade ct ON t.id = ct.trade_id " +
            "  WHERE t.account_id = ? AND t.timestamp > ? AND ct.id IS NULL AND t.is_buy = 1), 0) as total_expense, " +
            "COALESCE((SELECT SUM(t.qty * t.price) FROM trades t " +
            "  LEFT JOIN consumed_trade ct ON t.id = ct.trade_id " +
            "  WHERE t.account_id = ? AND t.timestamp > ? AND ct.id IS NULL AND t.is_buy = 0), 0) as total_revenue, " +
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
                    // Tax is 1% of sell value, capped at 5M per trade
                    long tax = Math.min(revenue / 100, 5_000_000);
                    return new AggregateStats(profit, expense, revenue, flipCount, tax, sessionTime);
                }
            }
        } catch (SQLException e) {
            log.error("Error getting aggregate stats for account={}", account, e);
        }
        
        return defaultStats;
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
