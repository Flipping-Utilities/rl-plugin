package com.flippingutilities.model;

import org.junit.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class HistoryTotalsTest
{
    @Test
    public void totalValueWidensBeforeMultiplyingPriceAndQuantity()
    {
        List<OfferEvent> offers = Arrays.asList(
            offer("a", true, 50_000, 50_000),
            offer("a", false, 60_000, 50_000));

        assertEquals(2_500_000_000L, FlippingItem.getTotalRevenueOrExpense(offers, true));
        assertEquals(3_000_000_000L, FlippingItem.getTotalRevenueOrExpense(offers, false));
    }

    @Test
    public void matchedValueWidensEachWholeOfferBeforeAddingIt()
    {
        List<OfferEvent> offers = Arrays.asList(
            offer("a", true, 50_000, 50_000),
            offer("a", true, 1, 50_000),
            offer("a", false, 50_001, 60_000));

        assertEquals(2_500_050_000L, FlippingItem.getValueOfMatchedOffers(offers, true));
        assertEquals(3_000_060_000L, FlippingItem.getValueOfMatchedOffers(offers, false));
        assertEquals(500_010_000L, FlippingItem.getProfit(offers));
    }

    @Test
    public void lifetimeQuantityAndMatchedValueCanExceedAnIndividualOfferLimit()
    {
        List<OfferEvent> offers = Arrays.asList(
            offer("a", true, 1_500_000_000, 1),
            offer("a", true, 1_500_000_000, 1),
            offer("a", false, 1_500_000_000, 2),
            offer("a", false, 1_500_000_000, 2));

        assertEquals(3_000_000_000L, FlippingItem.countFlipQuantity(offers));
        assertEquals(3_000_000_000L, FlippingItem.getValueOfMatchedOffers(offers, true));
        assertEquals(6_000_000_000L, FlippingItem.getValueOfMatchedOffers(offers, false));
        assertEquals(3_000_000_000L, FlippingItem.getProfit(offers));
    }

    @Test
    public void matchedValueStopsInsideTheOfferThatCrossesTheIntegerBoundary()
    {
        List<OfferEvent> offers = Arrays.asList(
            offer("a", true, 1_500_000_000, 1),
            offer("a", true, 1_500_000_000, 2),
            offer("a", false, 2_000_000_000, 3));

        assertEquals(2_000_000_000L, FlippingItem.countFlipQuantity(offers));
        assertEquals(2_500_000_000L, FlippingItem.getValueOfMatchedOffers(offers, true));
        assertEquals(6_000_000_000L, FlippingItem.getValueOfMatchedOffers(offers, false));
    }

    @Test
    public void accountWideQuantityMatchesItemsFunnelledAcrossAccounts()
    {
        List<OfferEvent> offers = Arrays.asList(
            offer("a", true, 2_000_000_000, 1),
            offer("a", false, 2_000_000_000, 2),
            offer("b", true, 2_000_000_000, 1),
            offer("b", false, 2_000_000_000, 2),
            offer("buy-only", true, 1_000_000_000, 1),
            offer("sell-only", false, 1_000_000_000, 2));

        //5b bought and 5b sold overall, so every bought item is matched against a sold one even
        //though the surplus inventory moves between accounts (bought on "buy-only", sold on "sell-only")
        assertEquals(5_000_000_000L, FlippingItem.countFlipQuantity(offers));
        assertEquals(5_000_000_000L, FlippingItem.getValueOfMatchedOffers(offers, true));
        assertEquals(10_000_000_000L, FlippingItem.getValueOfMatchedOffers(offers, false));
    }

    @Test
    public void flipQuantitiesWidenWhenTheBuysConsumedForASellExceedTheIntRange()
    {
        List<OfferEvent> offers = Arrays.asList(
            offer("a", true, 1_500_000_000, 4),
            offer("a", true, 1_500_000_000, 4),
            offer("a", false, 2_000_000_000, 5),
            offer("a", false, 1_000_000_000, 5));

        List<Flip> flips = FlippingItem.getFlips(offers);

        assertEquals(2, flips.size());
        assertEquals(2_000_000_000L, flips.get(0).getQuantity());
        assertEquals(4, flips.get(0).getBuyPrice(), 0);
        assertEquals(5L, flips.get(0).getSellPrice());
        //the leftover 1b items of the second buy are matched against the second sell
        assertEquals(1_000_000_000L, flips.get(1).getQuantity());
        assertEquals(4, flips.get(1).getBuyPrice(), 0);

        assertEquals(3_000_000_000L, FlippingItem.getProfit(offers));
    }

    @Test
    public void flipBuyPriceKeepsTheFractionOfAWeightedAverage()
    {
        //3 @ 4gp + 2 @ 5gp = 22gp for 5 items, a 4.4gp average buy price
        List<OfferEvent> offers = Arrays.asList(
            offer("a", true, 3, 4),
            offer("a", true, 2, 5),
            offer("a", false, 5, 6));

        List<Flip> flips = FlippingItem.getFlips(offers);

        assertEquals(1, flips.size());
        assertEquals(4.4, flips.get(0).getBuyPrice(), 1e-9);
        assertEquals(8L, Math.round((flips.get(0).getSellPrice() - flips.get(0).getBuyPrice()) * flips.get(0).getQuantity()));
        assertEquals(8L, FlippingItem.getProfit(offers));
    }

    private OfferEvent offer(String account, boolean buy, int quantity, int price)
    {
        OfferEvent offer = new OfferEvent();
        offer.setTime(Instant.parse("2020-01-01T00:00:00Z")); // Before GE tax.
        offer.setMadeBy(account);
        offer.setBuy(buy);
        offer.setCurrentQuantityInTrade(quantity);
        offer.setPrice(price);
        return offer;
    }
}
