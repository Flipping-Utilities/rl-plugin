package com.flippingutilities.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.*;
import java.util.stream.Collectors;

/**
 * This is the structure that represents a flip made out of various different items, either because
 * those items were combined to make some other one, or were the result of breaking some other item.
 * Ex: guthan set bought then broken up into pieces and sold.
 * The "parent" in these CombinationFlips is not necessarily the output item (of combining or breaking stuff), it is
 * the item that I have arbitrarily chosen to own the profits of the combination flips. This is because profits cannot
 * be divided over all the items in the combination flip. What decides how much profit each item gets? For that reason
 * I've decided that each recipe has a parent item (the parent item that will end up in the CombinationFlip) and that
 * parent item shall own the profits, revenue, expense, etc for the combination flip.
 */
@Data
@AllArgsConstructor
public class CombinationFlip {
    PartialOffer parent;
    //item id to a map of offer id to offer
    Map<Integer, Map<String, PartialOffer>> children;

    public CombinationFlip(int parentItemId, String parentOfferId, Map<Integer, Map<String, PartialOffer>> allPartialOffers) {
        this.parent = allPartialOffers.get(parentItemId).get(parentOfferId);
        this.children = allPartialOffers.entrySet().stream().
                filter(e -> e.getKey() != parentItemId).
                collect(
                        Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    }

    public CombinationFlip clone() {
        return new CombinationFlip(
                parent.clone(),
                children.entrySet().stream()
                        .map(e -> Map.entry(
                                e.getKey(),
                                e.getValue().entrySet().stream().map(
                                        entry -> Map.entry(entry.getKey(),
                                                entry.getValue().clone())).
                                        collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
        );
    }

    public long getProfit() {
        return CombinationFlip.calculateProfit(parent, children);
    }

    private long getExpenseOrRevenue(boolean getRevenue) {
        long totalValueOfChildren = children.values().stream().
                flatMap(m -> m.values().stream()).
                mapToLong(po -> po.offer.getPrice() * po.amountConsumed).
                sum();
        long totalValueOfParent = (long) parent.offer.getPrice() * parent.amountConsumed;
        boolean parentIsBuy = parent.offer.isBuy();
        if ((parentIsBuy && getRevenue) || (!parentIsBuy && !getRevenue)) {
            return totalValueOfChildren;
        }
        return totalValueOfParent;
    }

    public long getExpense() {
        return getExpenseOrRevenue(false);
    }

    public long getRevenue() {
        return getExpenseOrRevenue(true);
    }

    public long getTaxPaid() {
        return getOffers().stream().mapToLong(po -> po.getOffer().getTaxPaid()).sum();
    }

    public static long calculateProfit(PartialOffer parent, Map<Integer, Map<String, PartialOffer>> children) {
        long totalValueOfChildren = children.values().stream().
                flatMap(m -> m.values().stream()).
                mapToLong(po -> po.offer.getPrice() * po.amountConsumed).
                sum();
        long totalValueOfParent = (long) parent.offer.getPrice() * parent.amountConsumed;
        //if you buy the parent offer, you must be selling its children. Ex: buy a guthan set to deconstruct it and
        //sell the individual pieces.
        if (parent.offer.isBuy()) {
            return totalValueOfChildren - totalValueOfParent;
        }
        else {
            //if you sell a parent, you must have constructed it from its children. Ex, buy the guthan set pieces to
            //construct a set to sell it.
            return totalValueOfParent - totalValueOfChildren;
        }
    }

    /**
     * Gets all the partial offers contained in this combination flip.
     */
    public List<PartialOffer> getOffers() {
        List<PartialOffer> offers = new ArrayList<>();
        offers.add(parent);
        offers.addAll(getChildrenOffers());
        return offers;
    }

    /**
     * Gets only the partial offers of  children
     */
    public List<PartialOffer> getChildrenOffers() {
        return children.values().stream().
                flatMap(
                        m -> m.values().stream()).
                collect(Collectors.toList());
    }

    /**
     * Gets a specific child's partial offers
     */
    public List<PartialOffer> getChildOffers(int childItemId) {
        if (!children.containsKey(childItemId)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(children.get(childItemId).values());
    }

    /**
     * Gets item ids of all the items that make up the combination flip
     */
    public Set<Integer> getItemIds() {
        Set<Integer> ids = new HashSet<>();
        ids.add(parent.offer.getItemId());
        ids.addAll(children.keySet());
        return ids;
    }
}
