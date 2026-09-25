package com.flippingutilities.utilities;

import com.flippingutilities.model.TimeseriesResponse;
import com.flippingutilities.model.Timestep;
import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Wiki v2 parsing: prices may exceed 32 bit integers, unseen sides are null, and average
 * prices may include decimal points (per the API docs). All forms must parse without
 * losing information, and int consumers saturate through the capped accessors.
 */
public class WikiV2ParsingTest {
    private final Gson gson = new Gson();

    @Test
    public void latestParsesNumbersNullsAndStringEncodedOverMaxPrices() {
        String json = "{\"data\":{"
            + "\"4151\":{\"high\":826559,\"highTime\":1790372656,\"low\":805287,\"lowTime\":1790372654},"
            + "\"1042\":{\"high\":2400002345,\"highTime\":1790372656,\"low\":\"2350000000\",\"lowTime\":1790372654},"
            + "\"1234\":{\"high\":null,\"highTime\":null,\"low\":100,\"lowTime\":1790370000}"
            + "}}";

        WikiRequest request = gson.fromJson(json, WikiRequest.class);

        WikiItemMargins whip = request.getData().get(4151);
        assertEquals(Long.valueOf(826559L), whip.getHigh());
        assertEquals(Long.valueOf(805287L), whip.getLow());

        // Over max cash: number form and string form must both survive intact.
        WikiItemMargins phat = request.getData().get(1042);
        assertEquals(Long.valueOf(2400002345L), phat.getHigh());
        assertEquals(Long.valueOf(2350000000L), phat.getLow());
        // Saturating accessors cap instead of overflowing.
        assertEquals(Integer.MAX_VALUE, phat.getHighCapped());
        assertEquals(Integer.MAX_VALUE, phat.getLowCapped());

        // Unseen side is null, not 0: hasHigh distinguishes no-data from a real price.
        WikiItemMargins unseen = request.getData().get(1234);
        assertNull(unseen.getHigh());
        assertFalse(unseen.hasHigh());
        assertTrue(unseen.hasLow());
        assertEquals(0, unseen.getHighCapped());
    }

    @Test
    public void timeseriesParsesDecimalsNullsAndLookbackMetadata() {
        String json = "{"
            + "\"data\":["
            + "{\"timestamp\":1790351100,\"avgHighPrice\":822258,\"avgLowPrice\":802652,\"highPriceVolume\":1,\"lowPriceVolume\":7},"
            + "{\"timestamp\":1790351400,\"avgHighPrice\":2400002345.0,\"avgLowPrice\":null,\"highPriceVolume\":1,\"lowPriceVolume\":0}"
            + "],"
            + "\"itemId\":4151,\"startTimestamp\":1790286600,\"endTimestamp\":1790372700,\"timestep\":300}";

        TimeseriesResponse response = gson.fromJson(json, TimeseriesResponse.class);

        assertEquals(2, response.getData().size());
        assertEquals(Long.valueOf(822258L), response.getData().get(0).getAvgHighPrice());
        // Decimal average truncates instead of throwing; null side stays null.
        assertEquals(Long.valueOf(2400002345L), response.getData().get(1).getAvgHighPrice());
        assertNull(response.getData().get(1).getAvgLowPrice());
        // Lookback metadata from v2 round-trips (used for cache expiry).
        assertEquals(Long.valueOf(300L), response.getTimestep());
        assertEquals(Long.valueOf(4151L), response.getItemId());
    }

    @Test
    public void timestepsUseV2LookbackValues() {
        for (Timestep timestep : Timestep.values()) {
            // v2 lookback grammar: 6h, 24h, 7d, 30d, 6m, 1y
            assertTrue(timestep.getApiValue(),
                timestep.getApiValue().matches("(6h|24h|7d|30d|6m|1y)"));
        }
    }
}
