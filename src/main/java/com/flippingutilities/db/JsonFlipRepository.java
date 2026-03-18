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

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.*;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * JSON-based FlipRepository implementation.
 * Wraps existing in-memory AccountData access for backward compatibility.
 * 
 * Note: This implementation works with data already loaded in memory.
 * It does not perform on-demand loading like SqliteFlipRepository.
 */
@Slf4j
public class JsonFlipRepository implements FlipRepository {
    
    private final FlippingPlugin plugin;
    
    public JsonFlipRepository(FlippingPlugin plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public List<ItemSummary> getItemSummaries(String account, Instant since, String sortBy, int limit, int offset) {
        List<FlippingItem> items = getItemsForAccount(account);
        
        // Filter by time interval
        List<FlippingItem> filteredItems = items.stream()
            .filter(item -> item.isInInterval(since))
            .collect(Collectors.toList());
        
        // Sort items
        String sortField = sortBy != null ? sortBy.toUpperCase() : "TIME";
        Comparator<FlippingItem> comparator = getComparator(sortField, since);
        filteredItems.sort(comparator);
        
        // Paginate
        int fromIndex = Math.min(offset, filteredItems.size());
        int toIndex = Math.min(offset + limit, filteredItems.size());
        List<FlippingItem> pagedItems = filteredItems.subList(fromIndex, toIndex);
        
        // Convert to ItemSummary
        List<ItemSummary> summaries = new ArrayList<>();
        for (FlippingItem item : pagedItems) {
            List<OfferEvent> intervalHistory = item.getIntervalHistory(since);
            if (intervalHistory.isEmpty()) continue;
            
            Map<String, PartialOffer> partialOffers = plugin.getOfferIdToPartialOffer(item.getItemId());
            List<OfferEvent> adjustedOffers = FlippingItem.getPartialOfferAdjustedView(intervalHistory, partialOffers);
            
            int totalQty = adjustedOffers.stream()
                .mapToInt(OfferEvent::getCurrentQuantityInTrade)
                .sum();
            int buyQty = adjustedOffers.stream()
                .filter(OfferEvent::isBuy)
                .mapToInt(OfferEvent::getCurrentQuantityInTrade)
                .sum();
            int sellQty = totalQty - buyQty;
            
            long profit = FlippingItem.getProfit(adjustedOffers);
            long expense = FlippingItem.getValueOfMatchedOffers(adjustedOffers, true);
            double roi = expense > 0 ? (profit * 100.0 / expense) : 0;
            
            long latestTs = adjustedOffers.stream()
                .mapToLong(o -> o.getTime().toEpochMilli())
                .max()
                .orElse(0);
            
            summaries.add(new ItemSummary(
                item.getItemId(),
                item.getItemName(),
                totalQty,
                buyQty,
                sellQty,
                profit,
                roi,
                latestTs,
                item.isFavorite()
            ));
        }
        
        return summaries;
    }
    
    private Comparator<FlippingItem> getComparator(String sortBy, Instant since) {
        switch (sortBy) {
            case "PROFIT":
                return (a, b) -> {
                    long profitA = FlippingItem.getProfit(a.getIntervalHistory(since));
                    long profitB = FlippingItem.getProfit(b.getIntervalHistory(since));
                    return Long.compare(profitB, profitA); // Descending
                };
            case "ROI":
                return (a, b) -> {
                    double roiA = getRoi(a, since);
                    double roiB = getRoi(b, since);
                    return Double.compare(roiB, roiA); // Descending
                };
            case "TIME":
            default:
                return (a, b) -> {
                    Instant timeA = a.getLatestActivityTime();
                    Instant timeB = b.getLatestActivityTime();
                    if (timeA == null && timeB == null) return 0;
                    if (timeA == null) return 1;
                    if (timeB == null) return -1;
                    return timeB.compareTo(timeA); // Descending (newest first)
                };
        }
    }
    
    private double getRoi(FlippingItem item, Instant since) {
        List<OfferEvent> history = item.getIntervalHistory(since);
        long profit = FlippingItem.getProfit(history);
        long expense = FlippingItem.getValueOfMatchedOffers(history, true);
        return expense > 0 ? (profit * 100.0 / expense) : 0;
    }
    
    @Override
    public List<TradeRecord> getTradesForItem(String account, int itemId, Instant since) {
        List<TradeRecord> records = new ArrayList<>();
        FlippingItem item = findItem(account, itemId);
        if (item == null) return records;
        
        List<OfferEvent> history = item.getIntervalHistory(since);
        long id = 0;
        for (OfferEvent offer : history) {
            records.add(new TradeRecord(
                id++,
                itemId,
                offer.getTime().toEpochMilli(),
                offer.getCurrentQuantityInTrade(),
                offer.getPrice(),
                offer.isBuy()
            ));
        }
        
        // Sort by timestamp descending
        records.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        return records;
    }
    
    @Override
    public int getItemCount(String account, Instant since) {
        List<FlippingItem> items = getItemsForAccount(account);
        return (int) items.stream()
            .filter(item -> item.isInInterval(since))
            .count();
    }
    
    @Override
    public AggregateStats getAggregateStats(String account, Instant since) {
        List<FlippingItem> items = getItemsForAccount(account);
        List<RecipeFlipGroup> recipeGroups = getRecipeGroupsForAccount(account);
        
        long totalProfit = 0;
        long totalExpense = 0;
        long totalRevenue = 0;
        int flipCount = 0;
        long taxPaid = 0;
        
        // Compute from FlippingItems
        for (FlippingItem item : items) {
            List<OfferEvent> intervalHistory = item.getIntervalHistory(since);
            if (intervalHistory.isEmpty()) continue;
            
            Map<String, PartialOffer> partialOffers = plugin.getOfferIdToPartialOffer(item.getItemId());
            List<OfferEvent> adjustedOffers = FlippingItem.getPartialOfferAdjustedView(intervalHistory, partialOffers);
            
            taxPaid += adjustedOffers.stream().mapToLong(OfferEvent::getTaxPaid).sum();
            totalProfit += FlippingItem.getProfit(adjustedOffers);
            totalExpense += FlippingItem.getValueOfMatchedOffers(adjustedOffers, true);
            totalRevenue += FlippingItem.getValueOfMatchedOffers(adjustedOffers, false);
            flipCount += FlippingItem.getFlips(adjustedOffers).size();
        }
        
        // Add recipe flip stats
        for (RecipeFlipGroup group : recipeGroups) {
            List<RecipeFlip> recipeFlips = group.getFlipsInInterval(since);
            if (recipeFlips.isEmpty()) continue;
            
            taxPaid += recipeFlips.stream().mapToLong(RecipeFlip::getTaxPaid).sum();
            totalProfit += recipeFlips.stream().mapToLong(RecipeFlip::getProfit).sum();
            totalExpense += recipeFlips.stream().mapToLong(RecipeFlip::getExpense).sum();
            flipCount += recipeFlips.size();
        }
        
        long sessionTime = getSessionTimeForAccount(account);
        
        return new AggregateStats(totalProfit, totalExpense, totalRevenue, flipCount, taxPaid, sessionTime);
    }
    
    @Override
    public List<String> getAccountNames() {
        return new ArrayList<>(plugin.getDataHandler().getCurrentAccounts());
    }
    
    @Override
    public void recordTrade(String account, int itemId, long timestamp, int qty, int price, boolean isBuy) {
        // For JSON mode, trades are recorded through the normal flow (OfferEvent handling)
        // This method is primarily for SQLite mode
        log.debug("recordTrade called on JsonFlipRepository - trades are handled via OfferEvent flow");
    }
    
    @Override
    public Map<String, Object> getGeLimitState(String account, int itemId) {
        FlippingItem item = findItem(account, itemId);
        if (item == null) return null;
        
        Map<String, Object> result = new HashMap<>();
        result.put("nextRefresh", item.getGeLimitResetTime());
        result.put("itemsBought", item.getItemsBoughtThisLimitWindow());
        return result;
    }
    
    @Override
    public void updateGeLimitState(String account, int itemId, Instant nextRefresh, int itemsBought) {
        // For JSON mode, GE limit state is managed through HistoryManager
        log.debug("updateGeLimitState called on JsonFlipRepository - GE limits are managed via HistoryManager");
    }
    
    @Override
    public void close() {
        // Nothing to close for in-memory data
    }
    
    // Helper methods
    
    private List<FlippingItem> getItemsForAccount(String account) {
        if (account == null || FlippingPlugin.ACCOUNT_WIDE.equals(account)) {
            return plugin.viewItemsForCurrentView();
        }
        AccountData data = plugin.getDataHandler().viewAccountData(account);
        return data != null ? data.getTrades() : Collections.emptyList();
    }
    
    private List<RecipeFlipGroup> getRecipeGroupsForAccount(String account) {
        if (account == null || FlippingPlugin.ACCOUNT_WIDE.equals(account)) {
            return plugin.viewRecipeFlipGroupsForCurrentView();
        }
        AccountData data = plugin.getDataHandler().viewAccountData(account);
        return data != null ? data.getRecipeFlipGroups() : Collections.emptyList();
    }
    
    private FlippingItem findItem(String account, int itemId) {
        List<FlippingItem> items = getItemsForAccount(account);
        return items.stream()
            .filter(item -> item.getItemId() == itemId)
            .findFirst()
            .orElse(null);
    }
    
    private long getSessionTimeForAccount(String account) {
        if (account == null || FlippingPlugin.ACCOUNT_WIDE.equals(account)) {
            return plugin.viewAccumulatedTimeForCurrentView().toMillis();
        }
        AccountData data = plugin.getDataHandler().viewAccountData(account);
        return data != null ? data.getAccumulatedSessionTimeMillis() : 0L;
    }
}
