package com.flippingutilities.controller;

import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.Option;
import com.flippingutilities.utilities.InvalidOptionException;
import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;

public class LongPriceOptionTest {
    @Test
    public void priceHotkeysKeepSingleCoinPrecisionAboveIntRange() throws Exception {
        assertEquals(3_000_000_002L, calculate(3_000_000_001L, "+1", false));
        assertEquals(3_000_000_000L, calculate(3_000_000_001L, "-1", false));
        assertEquals(3_150_000_001L, calculate(3_000_000_001L, "*1.05", false));
    }

    @Test(expected = InvalidOptionException.class)
    public void quantityHotkeysRejectAmountsAboveTheStackLimit() throws Exception {
        calculate(3_000_000_001L, "+0", true);
    }

    @Test(expected = InvalidOptionException.class)
    public void priceModifierOverflowIsReportedInsteadOfClamped() throws Exception {
        calculate(Long.MAX_VALUE, "+1", false);
    }

    private long calculate(long price, String modifier, boolean quantity) throws Exception {
        FlippingItem item = new FlippingItem(4151, "Whip", 70, "Account");
        OfferEvent lastSale = new OfferEvent();
        lastSale.setPrice(price);
        item.setLatestSell(Optional.of(lastSale));
        return new OptionHandler(null).calculateOptionValue(
            new Option("", Option.LAST_SELL, modifier, quantity), Optional.of(item), item.getItemId());
    }
}
