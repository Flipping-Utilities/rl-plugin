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
    List<TradeRecord> getTradesForItem(String account, int itemId, Instant since);
    
    /**
     * Get count of unique items traded.
     * @param account Account display name
     * @param since Only include trades after this time
     * @return Number of unique items
     */
    int getItemCount(String account, Instant since);
    
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
     * Record a new trade. The uuid links the trade to recipe-flip consumption and deduplicates
     * re-records of the same offer; taxPaid is the actual tax recorded on the offer (-1 = compute).
     * @param account Account display name
     * @param itemId Item ID
     * @param uuid Offer UUID (nullable)
     * @param timestamp Trade timestamp
     * @param qty Quantity
     * @param price Price per item
     * @param isBuy True if buy, false if sell
     * @param taxPaid Total tax paid on this trade, or -1 to compute from the item/price/time
     */
    void recordTrade(String account, int itemId, String uuid, long timestamp, int qty, int price, boolean isBuy, long taxPaid);

    /**
     * Record a new trade without a uuid; tax is computed from the item, price, and time.
     */
    default void recordTrade(String account, int itemId, long timestamp, int qty, int price, boolean isBuy) {
        recordTrade(account, itemId, null, timestamp, qty, price, isBuy, -1L);
    }
    
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
    void updateGeLimitState(String account, int itemId, Instant nextRefresh, int itemsBought, int itemsBoughtThroughCompleteOffers);
    
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
