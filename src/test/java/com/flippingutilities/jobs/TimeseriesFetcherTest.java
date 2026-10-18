package com.flippingutilities.jobs;

import com.flippingutilities.FlippingConfig;
import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.CachedTimeseries;
import com.flippingutilities.model.Timestep;
import com.flippingutilities.model.TimeseriesResponse;
import com.flippingutilities.ui.widgets.OfferGraphChartOverlay.GraphDuration;
import com.google.gson.Gson;
import okhttp3.HttpUrl;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;
import okio.Timeout;
import org.junit.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class TimeseriesFetcherTest {
    private static final String HISTORY = "{\"data\":[{\"timestamp\":100,\"avgHighPrice\":42,\"avgLowPrice\":40}]}";

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

    @Test
    public void simultaneousConsumersShareOneRequestAndThenUseCache() throws Exception {
        ManualClient client = new ManualClient();
        TimeseriesFetcher fetcher = fetcher(client);
        AtomicInteger callbacks = new AtomicInteger();
        ExecutorService threads = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> requests = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                requests.add(threads.submit(() -> {
                    start.await();
                    fetcher.fetch(4151, Timestep.ONE_HOUR, result -> {
                        assertEquals(Long.valueOf(42L), result.getData().get(0).getAvgHighPrice());
                        callbacks.incrementAndGet();
                    });
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> request : requests) {
                request.get(5, TimeUnit.SECONDS);
            }
            assertEquals(1, client.calls.size());
            assertEquals(0, callbacks.get());
            assertTrue(client.calls.get(0).respond(200, HISTORY).closed);
            assertEquals(20, callbacks.get());
            fetcher.fetch(4151, Timestep.ONE_HOUR, result -> callbacks.incrementAndGet());
            assertEquals(21, callbacks.get());
            assertEquals(1, client.calls.size());
            assertEquals("4151", client.calls.get(0).request().url().queryParameter("id"));
            assertEquals("7d", client.calls.get(0).request().url().queryParameter("lookback"));
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    public void differentItemsAndTimestepsDoNotShareResponses() throws Exception {
        ManualClient client = new ManualClient();
        TimeseriesFetcher fetcher = fetcher(client);
        List<TimeseriesResponse> first = new ArrayList<>();
        List<TimeseriesResponse> second = new ArrayList<>();
        List<TimeseriesResponse> third = new ArrayList<>();
        fetcher.fetch(4151, Timestep.ONE_HOUR, first::add);
        fetcher.fetch(4151, Timestep.FIVE_MINUTES, second::add);
        fetcher.fetch(2, Timestep.ONE_HOUR, third::add);
        assertEquals(3, client.calls.size());
        client.calls.get(1).respond(200, HISTORY);
        assertTrue(first.isEmpty());
        assertEquals(1, second.size());
        assertTrue(third.isEmpty());
    }

    @Test
    public void invalidAndUnsuccessfulResponsesCloseAndAllowRetry() throws Exception {
        for (String json : new String[]{"{ malformed", "null", "{}", "{\"data\":null}", "{\"data\":[null]}"}) {
            ManualClient client = new ManualClient();
            TimeseriesFetcher fetcher = fetcher(client);
            List<TimeseriesResponse> responses = new ArrayList<>();
            fetcher.fetch(4151, Timestep.ONE_HOUR, responses::add);
            assertTrue(client.calls.get(0).respond(200, json).closed);
            assertTrue("Invalid response must not reach callers: " + json, responses.isEmpty());
            fetcher.fetch(4151, Timestep.ONE_HOUR, responses::add);
            assertEquals(2, client.calls.size());
            client.calls.get(1).respond(200, HISTORY);
            assertEquals(1, responses.size());
        }

        ManualClient client = new ManualClient();
        TimeseriesFetcher fetcher = fetcher(client);
        List<TimeseriesResponse> responses = new ArrayList<>();
        fetcher.fetch(4151, Timestep.ONE_HOUR, responses::add);
        assertTrue(client.calls.get(0).respond(503, HISTORY).closed);
        fetcher.fetch(4151, Timestep.ONE_HOUR, responses::add);
        client.calls.get(1).respond(200, HISTORY);
        assertEquals(1, responses.size());
    }

    @Test
    public void networkAndBodyFailuresReleaseRequestForRetry() throws Exception {
        ManualClient client = new ManualClient();
        TimeseriesFetcher fetcher = fetcher(client);
        List<TimeseriesResponse> responses = new ArrayList<>();
        fetcher.fetch(4151, Timestep.ONE_HOUR, responses::add);
        client.calls.get(0).fail();
        fetcher.fetch(4151, Timestep.ONE_HOUR, responses::add);
        client.calls.get(1).respond(200, (ResponseBody) null);
        fetcher.fetch(4151, Timestep.ONE_HOUR, responses::add);
        ManualClient.TrackingBody unreadable = new ManualClient.TrackingBody(HISTORY) {
            @Override public BufferedSource source() {
                return Okio.buffer(new Source() {
                    @Override public long read(okio.Buffer sink, long byteCount) throws IOException {
                        throw new IOException("response interrupted");
                    }
                    @Override public Timeout timeout() { return new Timeout(); }
                    @Override public void close() { }
                });
            }
        };
        client.calls.get(2).respond(200, unreadable);
        assertTrue(unreadable.closed);
        fetcher.fetch(4151, Timestep.ONE_HOUR, responses::add);
        client.calls.get(3).respond(200, HISTORY);
        assertEquals(1, responses.size());
    }

    @Test
    public void synchronousEnqueueFailureDoesNotStrandTheKey() throws Exception {
        ManualClient client = new ManualClient();
        client.rejectEnqueue = true;
        TimeseriesFetcher fetcher = fetcher(client);
        List<TimeseriesResponse> responses = new ArrayList<>();
        fetcher.fetch(4151, Timestep.ONE_HOUR, responses::add);
        client.rejectEnqueue = false;
        fetcher.fetch(4151, Timestep.ONE_HOUR, responses::add);
        client.calls.get(1).respond(200, HISTORY);
        assertEquals(1, responses.size());
    }

    @Test
    public void callbackFailureCannotStarveOtherSubscribersOrReentrantCacheReads() throws Exception {
        ManualClient client = new ManualClient();
        TimeseriesFetcher fetcher = fetcher(client);
        List<TimeseriesResponse> responses = new ArrayList<>();
        fetcher.fetch(4151, Timestep.ONE_HOUR, result -> { throw new IllegalStateException("consumer failed"); });
        fetcher.fetch(4151, Timestep.ONE_HOUR, result ->
            fetcher.fetch(4151, Timestep.ONE_HOUR, responses::add));
        fetcher.fetch(4151, Timestep.ONE_HOUR, responses::add);
        client.calls.get(0).respond(200, HISTORY);
        assertEquals(2, responses.size());
        assertSame(responses.get(0), responses.get(1));
        assertEquals(1, client.calls.size());
    }

    @Test
    public void emptyHistoryIsValidAndCacheKeepsOnlyFortyMostRecentlyUsedEntries() throws Exception {
        ManualClient client = new ManualClient();
        TimeseriesFetcher fetcher = fetcher(client);
        List<TimeseriesResponse> responses = new ArrayList<>();
        for (int id = 1; id <= 40; id++) {
            fetcher.fetch(id, Timestep.TWENTY_FOUR_HOURS, responses::add);
            client.calls.get(id - 1).respond(200, "{\"data\":[]}");
        }
        fetcher.fetch(1, Timestep.TWENTY_FOUR_HOURS, responses::add);
        assertEquals(40, client.calls.size());
        assertTrue(responses.get(40).getData().isEmpty());
        fetcher.fetch(41, Timestep.TWENTY_FOUR_HOURS, responses::add);
        client.calls.get(40).respond(200, HISTORY);
        fetcher.fetch(1, Timestep.TWENTY_FOUR_HOURS, responses::add);
        assertEquals(41, client.calls.size());
        fetcher.fetch(2, Timestep.TWENTY_FOUR_HOURS, responses::add);
        assertEquals(42, client.calls.size());
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
