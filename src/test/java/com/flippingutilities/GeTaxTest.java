package com.flippingutilities;

import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.utilities.Constants;
import org.junit.Test;

import java.time.Instant;
import static org.junit.Assert.assertEquals;


public class GeTaxTest {

    @Test
    public void testGeTaxLow() {
        OfferEvent offerEvent = new OfferEvent();
        offerEvent.setBuy(false);
        offerEvent.setTime(Instant.ofEpochSecond(Constants.GE_TAX_START + 100));
        offerEvent.setPrice(50);

        assertEquals(offerEvent.getPrice(), 50);
    }

    @Test
    public void testGeTaxHigh() {
        OfferEvent offerEvent = new OfferEvent();
        offerEvent.setBuy(false);
        offerEvent.setTime(Instant.ofEpochSecond(Constants.GE_TAX_START + 100));
        offerEvent.setPrice(2147000000);

        assertEquals(offerEvent.getPrice(), 2147000000 - Constants.GE_TAX_CAP);
    }

    @Test
    public void testGeTaxSimple() {
        OfferEvent offerEvent = new OfferEvent();
        offerEvent.setBuy(false);
        offerEvent.setTime(Instant.ofEpochSecond(Constants.GE_TAX_START + 100));
        offerEvent.setPrice(975);

        assertEquals(offerEvent.getPrice(), 975 - 9);
    }

    @Test
    public void testBeforeTax() {
        OfferEvent offerEvent = new OfferEvent();
        offerEvent.setBuy(false);
        offerEvent.setTime(Instant.ofEpochSecond(Constants.GE_TAX_START - 1));
        offerEvent.setPrice(975);

        assertEquals(offerEvent.getPrice(), 975);
    }

    @Test
    public void testNonSellAfterTax() {
        OfferEvent offerEvent = new OfferEvent();
        offerEvent.setBuy(true);
        offerEvent.setTime(Instant.ofEpochSecond(Constants.GE_TAX_START + 100));
        offerEvent.setPrice(975);

        assertEquals(offerEvent.getPrice(), 975);
    }
}
