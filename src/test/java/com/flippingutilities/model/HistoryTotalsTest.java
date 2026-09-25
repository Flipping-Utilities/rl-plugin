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
    public void accountWideQuantityWidensTheSumWithoutSharingUnmatchedInventory()
    {
        List<OfferEvent> offers = Arrays.asList(
            offer("a", true, 2_000_000_000, 1),
            offer("a", false, 2_000_000_000, 2),
            offer("b", true, 2_000_000_000, 1),
            offer("b", false, 2_000_000_000, 2),
            offer("buy-only", true, 1_000_000_000, 1),
            offer("sell-only", false, 1_000_000_000, 2));

        assertEquals(4_000_000_000L, FlippingItem.countFlipQuantity(offers));
        assertEquals(4_000_000_000L, FlippingItem.getValueOfMatchedOffers(offers, true));
        assertEquals(8_000_000_000L, FlippingItem.getValueOfMatchedOffers(offers, false));
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
