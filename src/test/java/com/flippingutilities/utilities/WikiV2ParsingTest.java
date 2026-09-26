package com.flippingutilities.utilities;

import com.flippingutilities.model.TimeseriesPoint;
import com.flippingutilities.model.TimeseriesResponse;
import com.flippingutilities.model.Timestep;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Wiki v2 parsing: prices may exceed 32 bit integers, unseen sides are null, and average
 * prices may include decimal points. Whole gp prices retain long precision, and absent
 * latest prices remain distinguishable from prices available for offer comparisons.
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
        assertEquals(826559L, whip.getHigh());
        assertEquals(805287L, whip.getLow());

        // Over max cash: number form and string form must both survive intact.
        WikiItemMargins phat = request.getData().get(1042);
        assertEquals(2400002345L, phat.getHigh());
        assertEquals(2350000000L, phat.getLow());

        // Consumers can detect an unseen side without unboxing a null price.
        WikiItemMargins unseen = request.getData().get(1234);
        assertFalse(unseen.hasHigh());
        assertTrue(unseen.hasLow());
        assertEquals(0L, unseen.getHigh());
        assertNull(unseen.getHighTime());
    }

    @Test
    public void missingLatestPricesAreSafeForOfferComparisons() {
        WikiItemMargins margins = gson.fromJson("{}", WikiItemMargins.class);

        assertFalse(margins.hasHigh());
        assertFalse(margins.hasLow());
        assertEquals(0L, margins.getHigh());
        assertEquals(0L, margins.getLow());
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
    public void decimalPricesRetainWholeGpPrecisionBeyondDoubleRange() {
        TimeseriesPoint point = gson.fromJson(
            "{\"timestamp\":1,\"avgHighPrice\":9007199254740993.9,"
                + "\"avgLowPrice\":\"9007199254740995.9\"}", TimeseriesPoint.class);

        assertEquals(Long.valueOf(9007199254740993L), point.getAvgHighPrice());
        assertEquals(Long.valueOf(9007199254740995L), point.getAvgLowPrice());
    }

    @Test(expected = JsonSyntaxException.class)
    public void priceOutsideLongRangeDoesNotSilentlyClamp() {
        gson.fromJson("{\"high\":9223372036854775808}", WikiItemMargins.class);
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
