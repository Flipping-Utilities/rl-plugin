package com.flippingutilities.jobs;

import com.flippingutilities.FlippingConfig;
import com.flippingutilities.controller.ApiRequestHandlerTest.ManualClient;
import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.CachedTimeseries;
import com.flippingutilities.model.Timestep;
import com.flippingutilities.model.TimeseriesResponse;
import com.flippingutilities.ui.widgets.OfferGraphChartOverlay.GraphDuration;
import com.google.gson.Gson;
import okhttp3.HttpUrl;
import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class TimeseriesFetcherTest {
    @Test
    public void persistedTimeRangesRequestEquivalentV2Lookbacks() {
        String[] persistedNames = {"FIVE_MINUTES", "ONE_HOUR", "SIX_HOURS", "TWENTY_FOUR_HOURS"};
        String[] lookbacks = {"24h", "7d", "30d", "1y"};
        GraphDuration[] durations = {GraphDuration.ONE_DAY, GraphDuration.ONE_WEEK,
            GraphDuration.ONE_MONTH, GraphDuration.ONE_YEAR};
        ManualClient client = new ManualClient();
        TimeseriesFetcher fetcher = fetcher(client);

        for (int i = 0; i < persistedNames.length; i++) {
            Timestep timestep = Timestep.valueOf(persistedNames[i]);
            assertEquals(durations[i], GraphDuration.fromTimestep(timestep));
            assertEquals(timestep, durations[i].getTimestep());
            assertEquals(durations[i].getMaxTimeRangeSeconds(), timestep.getMaxTimeRangeSeconds());
            fetcher.fetch(4151, timestep, response -> fail("No response has been delivered"));
            HttpUrl url = client.calls.get(i).request().url();
            assertEquals("https", url.scheme());
            assertEquals("prices.runescape.wiki", url.host());
            assertEquals("/api/v2/osrs/timeseries", url.encodedPath());
            assertEquals(lookbacks[i], url.queryParameter("lookback"));
            assertEquals("4151", url.queryParameter("id"));
            assertNull(url.queryParameter("timestep"));
        }
        assertEquals(Timestep.FIVE_MINUTES, new FlippingConfig() {}.priceGraphTimestep());
    }

    @Test
    public void responseMetadataAndLongPricesSurviveCachingByItemAndLookback() throws Exception {
        ManualClient client = new ManualClient();
        TimeseriesFetcher fetcher = fetcher(client);
        List<TimeseriesResponse> received = new ArrayList<>();
        fetcher.fetch(4151, Timestep.FIVE_MINUTES, received::add);
        assertTrue(client.calls.get(0).respond(200,
            "{\"itemId\":4151,\"startTimestamp\":100,\"endTimestamp\":200,\"timestep\":300,"
                + "\"data\":[{\"timestamp\":150,\"avgHighPrice\":5000000001.75,\"avgLowPrice\":null}]}").closed);
        assertEquals(1, received.size());
        TimeseriesResponse response = received.get(0);
        assertEquals(Long.valueOf(4151), response.getItemId());
        assertEquals(Long.valueOf(100), response.getStartTimestamp());
        assertEquals(Long.valueOf(200), response.getEndTimestamp());
        assertEquals(Long.valueOf(300), response.getTimestep());
        assertEquals(Long.valueOf(5_000_000_001L), response.getData().get(0).getAvgHighPrice());
        assertNull(response.getData().get(0).getAvgLowPrice());

        fetcher.fetch(4151, Timestep.FIVE_MINUTES, received::add);
        assertEquals(1, client.calls.size());
        assertSame(response, received.get(1));
        fetcher.fetch(4151, Timestep.ONE_HOUR, received::add);
        fetcher.fetch(4152, Timestep.FIVE_MINUTES, received::add);
        assertEquals(3, client.calls.size());
        assertEquals(2, received.size());
    }

    @Test
    public void failedResponseIsClosedAndRetriedWithoutCaching() throws Exception {
        ManualClient client = new ManualClient();
        TimeseriesFetcher fetcher = fetcher(client);
        List<TimeseriesResponse> received = new ArrayList<>();
        fetcher.fetch(4151, Timestep.FIVE_MINUTES, received::add);
        assertTrue(client.calls.get(0).respond(404, "Not found").closed);
        fetcher.fetch(4151, Timestep.FIVE_MINUTES, received::add);
        assertEquals(2, client.calls.size());
        assertTrue(received.isEmpty());
    }

    @Test
    public void cacheUsesReturnedSpacingAndFallsBackWhenItIsMissingOrInvalid() {
        Instant fetched = Instant.now().minusSeconds(3600);
        // A large server interval avoids sensitivity to the current wall-clock boundary.
        assertFalse(cached(fetched, Long.MAX_VALUE / 2).isStale());
        assertTrue(cached(fetched, 1L).isStale());
        assertTrue(cached(fetched, null).isStale());
        assertTrue(cached(fetched, 0L).isStale());
        assertTrue(cached(fetched, -1L).isStale());
    }

    private CachedTimeseries cached(Instant fetched, Long interval) {
        TimeseriesResponse response = new TimeseriesResponse(Collections.emptyList(), 4151L,
            100L, 200L, interval);
        return new CachedTimeseries(response, fetched, Timestep.FIVE_MINUTES);
    }

    private TimeseriesFetcher fetcher(ManualClient client) {
        FlippingPlugin plugin = new FlippingPlugin();
        plugin.gson = new Gson();
        return new TimeseriesFetcher(client, plugin);
    }
}
