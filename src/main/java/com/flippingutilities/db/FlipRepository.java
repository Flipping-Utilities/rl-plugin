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

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Repository interface for flip data access.
 * Abstracts whether data comes from JSON files or SQLite database.
 * 
 * SQLite implementation loads data on-demand via queries.
 * JSON implementation loads from in-memory AccountData.
 */
public interface FlipRepository {
    
    /**
     * Item summary for display in the flipping panel list.
     */
    class ItemSummary {
        public final int itemId;
        public final String itemName;
        public final int totalQty;
        public final int buyQty;
        public final int sellQty;
        public final long totalProfit;
        public final double roi;
        public final long latestTimestamp;
        public final boolean isFavorite;
        
        public ItemSummary(int itemId, String itemName, int totalQty, int buyQty, int sellQty,
                          long totalProfit, double roi, long latestTimestamp, boolean isFavorite) {
            this.itemId = itemId;
            this.itemName = itemName;
            this.totalQty = totalQty;
            this.buyQty = buyQty;
            this.sellQty = sellQty;
            this.totalProfit = totalProfit;
            this.roi = roi;
            this.latestTimestamp = latestTimestamp;
            this.isFavorite = isFavorite;
        }
    }
    
    /**
     * Individual trade record for history display.
     */
    class TradeRecord {
        public final long id;
        public final int itemId;
        public final long timestamp;
        public final int qty;
        public final int price;
        public final boolean isBuy;
        
        public TradeRecord(long id, int itemId, long timestamp, int qty, int price, boolean isBuy) {
            this.id = id;
            this.itemId = itemId;
            this.timestamp = timestamp;
            this.qty = qty;
            this.price = price;
            this.isBuy = isBuy;
        }
    }
    
    /**
     * Aggregate statistics for the stats panel.
     */
    class AggregateStats {
        public final long totalProfit;
        public final long totalExpense;
        public final long totalRevenue;
        public final int flipCount;
        public final long taxPaid;
        public final long sessionTimeMillis;
        
        public AggregateStats(long totalProfit, long totalExpense, long totalRevenue, 
                             int flipCount, long taxPaid, long sessionTimeMillis) {
            this.totalProfit = totalProfit;
            this.totalExpense = totalExpense;
            this.totalRevenue = totalRevenue;
            this.flipCount = flipCount;
            this.taxPaid = taxPaid;
            this.sessionTimeMillis = sessionTimeMillis;
        }
    }
    
    /**
     * Get list of item summaries for the flipping panel.
     * @param account Account display name
     * @param since Only include trades after this time
     * @param sortBy Sort field: "TIME", "PROFIT", "ROI"
     * @param limit Max results
     * @param offset Pagination offset
     * @return List of item summaries
     */
    List<ItemSummary> getItemSummaries(String account, Instant since, String sortBy, int limit, int offset);
    
    /**
     * Get trade history for a specific item.
     * @param account Account display name
     * @param itemId Item ID
     * @param since Only include trades after this time
     * @return List of trade records
     */
/**
     * Get trade history for a specific item.
     * @param account Account display name
     * @param itemId Item ID
     * @param since Only include trades after this time
     * @return List of trade records
     */
    List<TradeRecord> getTradesForItem(String account, int itemId, Instant since);
    
    /**
     * Get count of unique items traded.
     * @param account Account display name
     * @param since Only include trades after this time
     * @return Number of unique items
     */
    int getItemCount(String account, Instant since);
    
    /**
    
    /**
     * Get aggregate statistics for an account.
     * @param account Account display name
     * @param since Only include trades after this time
     * @return Aggregate stats
     */
    AggregateStats getAggregateStats(String account, Instant since);
    
    /**
     * Get list of account names.
     * @return List of display names
     */
    List<String> getAccountNames();
    
    /**
     * Record a new trade.
     * @param account Account display name
     * @param itemId Item ID
     * @param timestamp Trade timestamp
     * @param qty Quantity
     * @param price Price per item
     * @param isBuy True if buy, false if sell
     */
    void recordTrade(String account, int itemId, long timestamp, int qty, int price, boolean isBuy);
    
    /**
     * Get GE limit state for an item.
     * @param account Account display name
     * @param itemId Item ID
     * @return Map with nextRefresh (Instant) and itemsBought (int), or null if not found
     */
    Map<String, Object> getGeLimitState(String account, int itemId);
    
    /**
     * Update GE limit state for an item.
     * @param account Account display name
     * @param itemId Item ID
     * @param nextRefresh When limit resets
     * @param itemsBought Items bought this window
     */
    void updateGeLimitState(String account, int itemId, Instant nextRefresh, int itemsBought);
    
    /**
     * Close any resources.
     */
    void close();

    /**
     * Set the favorite status and code for an item.
     * @param account Account display name
     * @param itemId Item ID
     * @param isFavorite Whether the item is favorited
     * @param favoriteCode Quick search code
     */
    default void setFavorite(String account, int itemId, boolean isFavorite, String favoriteCode) {}

    /**
     * Get the favorite status for an item.
     * @param account Account display name
     * @param itemId Item ID
     * @return Map with "isFavorite" and "favoriteCode", or null if not found
     */
    default Map<String, Object> getFavorite(String account, int itemId) { return null; }
}
