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
        //take all flipping items from the account cache, regardless of account, and segregate them based on item name.
        Map<Integer, List<FlippingItem>> groupedItems = allAccountData.stream().
            flatMap(accountData -> accountData.getTrades().stream()).
            map(FlippingItem::clone).
            collect(Collectors.groupingBy(FlippingItem::getItemId));

        //take every list containing flipping items of the same type and reduce it to one merged flipping item and put that
        //item in a final merged list
        List<FlippingItem> mergedItems = groupedItems.values().stream().
            map(list -> list.stream().reduce(FlippingItem::merge)).filter(Optional::isPresent).map(Optional::get).
            collect(Collectors.toList());

        mergedItems.sort(Collections.reverseOrder(Comparator.comparing(FlippingItem::getLatestActivityTime)));

        return mergedItems;
    }
}
