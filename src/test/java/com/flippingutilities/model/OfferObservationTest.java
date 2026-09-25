package com.flippingutilities.model;

import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;
import org.junit.Test;
import java.lang.reflect.Proxy;

import static org.junit.Assert.*;

public class OfferObservationTest {
    @Test
    public void capturesRawMoneyBeforeAverageRoundingAndPreservesItWhenQueued() {
        OfferEvent first = observation(3, 301L);
        OfferEvent correction = observation(3, 302L);
        assertEquals(100, first.getPreTaxPrice());
        assertEquals(100, correction.getPreTaxPrice());
        assertFalse("A changed raw amount must survive duplicate screening", first.isDuplicate(correction));
        first.setPredecessorUuid("previous");
        OfferEvent queued = first.clone();
        assertEquals(Long.valueOf(301), queued.getCumulativeAmount());
        assertEquals(first.getObservedAt(), queued.getObservedAt());
        assertEquals(first.getOrderId(), queued.getOrderId());
        assertEquals("previous", queued.getPredecessorUuid());
    }

    @Test
    public void totalAboveIntegerLimitIsNotSaturatedInAccountingObservation() {
        OfferEvent event = observation(3, 301L);
        // The currently approved API is int-based; the persisted observation must support
        // the wider API too without narrowing it when it is cloned for the writer.
        event.setCumulativeAmount(9_000_000_001L);
        assertEquals(Long.valueOf(9_000_000_001L), event.getCumulativeAmount());
        assertEquals(Long.valueOf(9_000_000_001L), event.clone().getCumulativeAmount());
        assertTrue(event.isDuplicate(event.clone()));
    }

    @Test
    public void firstExactObservationIsNotDiscardedAgainstLegacyAverage() {
        OfferEvent event = observation(3, 301L);
        OfferEvent legacy = event.clone();
        legacy.setCumulativeAmount(null);
        assertFalse(legacy.isDuplicate(event));
        assertTrue(legacy.isDuplicate(legacy.clone()));
    }

    private OfferEvent observation(int quantity, long total) {
        GrandExchangeOffer offer = (GrandExchangeOffer) Proxy.newProxyInstance(
            GrandExchangeOffer.class.getClassLoader(), new Class<?>[]{GrandExchangeOffer.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getQuantitySold": return quantity;
                    case "getItemId": return 4151;
                    case "getTotalQuantity": return 10;
                    case "getState": return GrandExchangeOfferState.BUYING;
                    case "getPrice":
                    case "getSpent":
                        long value = method.getName().equals("getPrice") ? 110L : total;
                        if (method.getReturnType() == long.class) return value;
                        return Math.toIntExact(value);
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        GrandExchangeOfferChanged changed = new GrandExchangeOfferChanged();
        changed.setOffer(offer);
        changed.setSlot(1);
        return OfferEvent.fromGrandExchangeEvent(changed);
    }
}
