package com.flippingutilities.jobs;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.TimeseriesResponse;
import com.flippingutilities.model.Timestep;
import com.google.gson.Gson;
import okhttp3.*;
import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;
import okio.Timeout;
import org.junit.Test;

import java.io.IOException;
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
                        assertEquals(Integer.valueOf(42), result.getData().get(0).getAvgHighPrice());
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
            assertEquals("1h", client.calls.get(0).request().url().queryParameter("timestep"));
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
        TrackingBody unreadable = new TrackingBody(HISTORY) {
            @Override public BufferedSource source() {
                return Okio.buffer(new Source() {
                    @Override public long read(Buffer sink, long byteCount) throws IOException {
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

    private TimeseriesFetcher fetcher(OkHttpClient client) {
        FlippingPlugin plugin = new FlippingPlugin();
        plugin.gson = new Gson();
        return new TimeseriesFetcher(client, plugin);
    }

    private static class ManualClient extends OkHttpClient {
        private final List<ManualCall> calls = Collections.synchronizedList(new ArrayList<>());
        private boolean rejectEnqueue;
        @Override public Call newCall(Request request) {
            ManualCall call = new ManualCall(request, rejectEnqueue);
            calls.add(call);
            return call;
        }
    }

    private static class ManualCall implements Call {
        private final Request request;
        private final boolean rejectEnqueue;
        private Callback callback;
        private boolean canceled;
        ManualCall(Request request, boolean rejectEnqueue) {
            this.request = request;
            this.rejectEnqueue = rejectEnqueue;
        }
        TrackingBody respond(int status, String text) throws IOException {
            TrackingBody body = new TrackingBody(text);
            respond(status, body);
            return body;
        }
        void respond(int status, ResponseBody body) throws IOException {
            callback.onResponse(this, new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("test response").body(body).build());
        }
        void fail() { callback.onFailure(this, new IOException("offline")); }
        @Override public Request request() { return request; }
        @Override public Response execute() { throw new AssertionError("Unexpected synchronous HTTP call"); }
        @Override public void enqueue(Callback callback) {
            if (rejectEnqueue) { throw new java.util.concurrent.RejectedExecutionException(); }
            this.callback = callback;
        }
        @Override public void cancel() { canceled = true; }
        @Override public boolean isExecuted() { return callback != null; }
        @Override public boolean isCanceled() { return canceled; }
        @Override public Timeout timeout() { return new Timeout(); }
        @Override public Call clone() { return new ManualCall(request, rejectEnqueue); }
    }

    private static class TrackingBody extends ResponseBody {
        private final Buffer buffer = new Buffer();
        private boolean closed;
        TrackingBody(String text) { buffer.writeUtf8(text); }
        @Override public MediaType contentType() { return MediaType.parse("application/json"); }
        @Override public long contentLength() { return -1; }
        @Override public BufferedSource source() { return buffer; }
        @Override public void close() { closed = true; super.close(); }
    }
}
