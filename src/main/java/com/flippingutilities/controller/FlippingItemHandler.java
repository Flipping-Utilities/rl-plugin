package com.flippingutilities.controller;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.utilities.SORT;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

public class FlippingItemHandler {
    FlippingPlugin plugin;

    FlippingItemHandler(FlippingPlugin plugin) {
        this.plugin = plugin;
    }

    public List<FlippingItem> sortItems(List<FlippingItem> items, SORT selectedSort, Instant startOfInterval)
    {
        List<FlippingItem> result = new ArrayList<>(items);

        if (selectedSort == null || result.isEmpty()) {
            return result;
        }

        // Sort keys are computed ONCE per item. Computing them inside the comparator ran the
        // recipe-adjusted view plus the full flip pairing (combineToFlips) O(n log n) times,
        // which took seconds on large datasets (2k+ items with thousands of offers each).
        switch (selectedSort) {
            case TIME:
                result.sort(Comparator.comparing(FlippingItem::getLatestActivityTime));
                break;

            case TOTAL_PROFIT: {
                Map<FlippingItem, Long> key = new IdentityHashMap<>();
                for (FlippingItem item : result) {
                    key.put(item, FlippingItem.getProfit(adjustedIntervalView(item, startOfInterval)));
                }
                result.sort(Comparator.comparing(key::get));
                break;
            }

            case PROFIT_EACH: {
                Map<FlippingItem, Long> key = new IdentityHashMap<>();
                for (FlippingItem item : result) {
                    List<OfferEvent> adjustedOffers = adjustedIntervalView(item, startOfInterval);
                    long quantity = FlippingItem.countFlipQuantity(adjustedOffers);
                    key.put(item, quantity == 0 ? Long.MIN_VALUE : FlippingItem.getProfit(adjustedOffers) / quantity);
                }
                result.sort(Comparator.comparing(key::get));
                break;
            }
            case ROI: {
                Map<FlippingItem, Float> key = new IdentityHashMap<>();
                for (FlippingItem item : result) {
                    List<OfferEvent> adjustedOffers = adjustedIntervalView(item, startOfInterval);
                    long profit = FlippingItem.getProfit(adjustedOffers);
                    long expense = FlippingItem.getValueOfMatchedOffers(adjustedOffers, true);
                    key.put(item, expense == 0 ? Float.MIN_VALUE : (float) profit / expense * 100);
                }
                result.sort(Comparator.comparing(key::get));
                break;
            }
            case FLIP_COUNT: {
                Map<FlippingItem, Long> key = new IdentityHashMap<>();
                for (FlippingItem item : result) {
                    key.put(item, (long) FlippingItem.countFlipQuantity(adjustedIntervalView(item, startOfInterval)));
                }
                result.sort(Comparator.comparing(key::get));
                break;
            }
        }
        Collections.reverse(result);
        return result;
    }

    /**
     * The item's interval-filtered history adjusted for recipe consumption, as used by every
     * profit/quantity sort key.
     */
    private List<OfferEvent> adjustedIntervalView(FlippingItem item, Instant startOfInterval) {
        Map<String, PartialOffer> offerIdToPartialOffer = plugin.getOfferIdToPartialOffer(item.getItemId());
        ArrayList<OfferEvent> intervalHistory = item.getIntervalHistory(startOfInterval);
        return FlippingItem.getPartialOfferAdjustedView(intervalHistory, offerIdToPartialOffer);
    }

    public void deleteRemovedItems(List<FlippingItem> currItems) {
        currItems.removeIf((item) ->
        {
            if (item.getGeLimitResetTime() != null) {
                Instant startOfRefresh = item.getGeLimitResetTime().minus(4, ChronoUnit.HOURS);

                return !item.getValidFlippingPanelItem() && !item.hasValidOffers()
                    && (!Instant.now().isAfter(item.getGeLimitResetTime()) || item.getGeLimitResetTime().isBefore(startOfRefresh));
            }
            return !item.getValidFlippingPanelItem() && !item.hasValidOffers();
        });
    }

    /**
     * creates a view of an "account wide tradelist". An account wide tradelist is just a reflection of the flipping
     * items currently in each of the account's tradelists. It does this by merging the flipping items of the same type
     * from each account's trade list into one flipping item.
     */
    List<FlippingItem> createAccountWideFlippingItemList(Collection<AccountData> allAccountData) {
        // Group source items without cloning their history until the aggregate is built.
        Map<Integer, List<FlippingItem>> groupedItems = allAccountData.stream().
            flatMap(accountData -> accountData.getTrades().stream()).
            collect(Collectors.groupingBy(FlippingItem::getItemId));

        List<FlippingItem> mergedItems = groupedItems.values().stream().
            map(this::mergeAccountItems).
            collect(Collectors.toList());

        mergedItems.sort(Collections.reverseOrder(Comparator.comparing(FlippingItem::getLatestActivityTime)));

        return mergedItems;
    }

    private FlippingItem mergeAccountItems(List<FlippingItem> items) {
        FlippingItem latest = items.get(0);
        Deque<FlippingItem> historyOrder = new ArrayDeque<>();
        boolean favorite = false;
        for (FlippingItem item : items) {
            // The previous pairwise merge prepended a new latest item's history.
            // Retain that stable ordering when offers have identical timestamps.
            if (item.getLatestActivityTime().isAfter(latest.getLatestActivityTime())) {
                latest = item;
                historyOrder.addFirst(item);
            } else {
                historyOrder.addLast(item);
            }
            favorite |= item.isFavorite();
        }
        FlippingItem merged = latest.clone();
        merged.setFavorite(favorite);
        if (items.size() == 1) return merged;

        List<OfferEvent> history = new ArrayList<>();
        for (FlippingItem item : historyOrder) {
            if (item == latest) {
                history.addAll(merged.getHistory().getCompressedOfferEvents());
            } else {
                item.getHistory().getCompressedOfferEvents().forEach(offer -> history.add(offer.clone()));
            }
        }
        // Sorting once avoids repeatedly sorting the growing combined history for
        // each account. Every offer is still cloned so edits cannot reach source data.
        history.sort(Comparator.comparing(OfferEvent::getTime));
        merged.getHistory().setCompressedOfferEvents(history);
        return merged;
    }
}
