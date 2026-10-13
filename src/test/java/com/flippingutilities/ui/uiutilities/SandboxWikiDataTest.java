package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.model.TimeseriesResponse;
import com.flippingutilities.model.Timestep;
import com.flippingutilities.utilities.WikiRequest;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;
import org.junit.Assume;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class SandboxWikiDataTest {
    private static final String MAPPING = "[{\"id\":4151,\"name\":\"Abyssal whip\",\"limit\":70,\"icon\":\"Abyssal whip.png\"}]";
    private static final String LATEST = "{\"data\":{\"4151\":{\"high\":100,\"low\":90,\"highTime\":1700000001,\"lowTime\":1700000000}}}";
    private static final String SERIES = "{\"data\":[{\"timestamp\":1700000002,\"avgHighPrice\":102,\"avgLowPrice\":null},"
        + "{\"timestamp\":1700000001,\"avgHighPrice\":100,\"avgLowPrice\":90}]}";

    @Test(timeout = 60000)
    public void optionalLiveWikiSmokeTest() throws Exception {
        Assume.assumeTrue("Set FLIPPING_SANDBOX_WIKI_TEST=true to check the live Wiki endpoints",
            "true".equals(System.getenv("FLIPPING_SANDBOX_WIKI_TEST")));
        try (SandboxWikiData service = new SandboxWikiData()) {
            List<SandboxWikiData.Item> mapping = service.mapping().get(20, TimeUnit.SECONDS);
            SandboxWikiData.Item whip = mapping.stream().filter(item -> item.id == 4151).findFirst().get();
            assertFalse(whip.name.isEmpty());
            assertTrue(whip.icon.toLowerCase(java.util.Locale.ROOT).endsWith(".png"));
            BufferedImage icon = service.icon(4151).get(20, TimeUnit.SECONDS);
            assertTrue(icon.getWidth() > 0);
            assertTrue(icon.getHeight() > 0);
            assertTrue(service.latest().get(20, TimeUnit.SECONDS).getData().containsKey(4151));
            assertFalse(service.timeseries(4151, Timestep.FIVE_MINUTES).get(20, TimeUnit.SECONDS).getData().isEmpty());
        }
    }

    @Test(timeout = 10000)
    public void coalescesMappingAndReusesItWithoutDownloadingIcons() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        AtomicBoolean onEdt = new AtomicBoolean();
        try (SandboxWikiData service = new SandboxWikiData(client(request -> {
            requests.incrementAndGet();
            onEdt.set(SwingUtilities.isEventDispatchThread());
            assertEquals("/api/v1/osrs/mapping", request.url().encodedPath());
            assertEquals(SandboxWikiData.USER_AGENT, request.header("User-Agent"));
            assertNull(request.header("Authorization"));
            assertNull(request.header("Cookie"));
            entered.countDown();
            awaitLatch(release);
            return json(request, MAPPING);
        }))) {
            CompletableFuture<List<SandboxWikiData.Item>> first = service.mapping();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertSame(first, service.mapping());
            release.countDown();
            List<SandboxWikiData.Item> items = await(first);
            assertEquals(4151, items.get(0).id);
            assertEquals("Abyssal whip", items.get(0).name);
            assertEquals(70, items.get(0).limit);
            assertEquals("Abyssal whip.png", items.get(0).icon);
            assertSame(items, await(service.mapping()));
            assertEquals(1, requests.get());
            assertFalse(onEdt.get());
        } finally {
            release.countDown();
        }
    }

    @Test(timeout = 10000)
    public void encodesAndCoalescesIconsAfterLazyMapping() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Request> requests = Collections.synchronizedList(new ArrayList<>());
        byte[] image = png(3, 4);
        try (SandboxWikiData service = new SandboxWikiData(client(request -> {
            requests.add(request);
            if (request.url().host().equals("prices.runescape.wiki")) {
                return json(request, MAPPING.replace("Abyssal whip.png", "Abyssal whip (test)?#.png"));
            }
            assertEquals("https", request.url().scheme());
            assertEquals("oldschool.runescape.wiki", request.url().host());
            assertEquals("Abyssal_whip_(test)?#.png", request.url().pathSegments().get(1));
            assertTrue(request.url().encodedPath().contains("%3F%23"));
            assertNull(request.url().query());
            assertNull(request.url().fragment());
            entered.countDown();
            awaitLatch(release);
            return response(request, 200, image);
        }))) {
            CompletableFuture<BufferedImage> first = service.icon(4151);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertSame(first, service.icon(4151));
            release.countDown();
            BufferedImage result = await(first);
            assertEquals(3, result.getWidth());
            assertEquals(4, result.getHeight());
            assertSame(result, await(service.icon(4151)));
            assertEquals(2, requests.size());
        } finally {
            release.countDown();
        }
    }

    @Test(timeout = 10000)
    public void latestCoalescesAndExpiresAfterSixtySeconds() throws Exception {
        MutableClock clock = new MutableClock();
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (SandboxWikiData service = new SandboxWikiData(client(request -> {
            requests.incrementAndGet();
            entered.countDown();
            awaitLatch(release);
            return json(request, LATEST);
        }), clock)) {
            CompletableFuture<WikiRequest> first = service.latest();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertSame(first, service.latest());
            release.countDown();
            WikiRequest result = await(first);
            assertEquals(100, result.getData().get(4151).getHigh());
            assertEquals(90, result.getData().get(4151).getLow());
            clock.advance(60);
            assertSame(result, await(service.latest()));
            assertEquals(1, requests.get());
            clock.advance(1);
            assertNotSame(result, await(service.latest()));
            assertEquals(2, requests.get());
        } finally {
            release.countDown();
        }
    }

    @Test(timeout = 10000)
    public void timeseriesCoalescesSortsAndUsesTheProductionIntervalBuffer() throws Exception {
        MutableClock clock = new MutableClock();
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (SandboxWikiData service = new SandboxWikiData(client(request -> {
            requests.incrementAndGet();
            assertEquals("/api/v1/osrs/timeseries", request.url().encodedPath());
            assertEquals("4151", request.url().queryParameter("id"));
            assertEquals("5m", request.url().queryParameter("timestep"));
            entered.countDown();
            awaitLatch(release);
            return json(request, SERIES);
        }), clock)) {
            CompletableFuture<TimeseriesResponse> first = service.timeseries(4151, Timestep.FIVE_MINUTES);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertSame(first, service.timeseries(4151, Timestep.FIVE_MINUTES));
            release.countDown();
            TimeseriesResponse result = await(first);
            assertEquals(1700000001, result.getData().get(0).getTimestamp());
            assertNull(result.getData().get(1).getAvgLowPrice());
            long lifetime = 300 - Math.floorMod(clock.instant().getEpochSecond(), 300) + 15;
            clock.advance(lifetime);
            assertSame(result, await(service.timeseries(4151, Timestep.FIVE_MINUTES)));
            clock.advance(1);
            assertNotSame(result, await(service.timeseries(4151, Timestep.FIVE_MINUTES)));
            assertEquals(2, requests.get());
        } finally {
            release.countDown();
        }
    }

    @Test(timeout = 10000)
    public void evictsOldTimeseriesAfterFortyItems() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (SandboxWikiData service = new SandboxWikiData(client(request -> {
            requests.incrementAndGet();
            return json(request, SERIES);
        }), new MutableClock())) {
            for (int id = 1; id <= 41; id++) await(service.timeseries(id, Timestep.ONE_HOUR));
            await(service.timeseries(41, Timestep.ONE_HOUR));
            assertEquals(41, requests.get());
            await(service.timeseries(1, Timestep.ONE_HOUR));
            assertEquals(42, requests.get());
        }
    }

    @Test(timeout = 10000)
    public void evictsOldIconsAfter256Items() throws Exception {
        StringBuilder mapping = new StringBuilder("[");
        for (int id = 1; id <= 257; id++) {
            if (id > 1) mapping.append(',');
            mapping.append("{\"id\":").append(id).append(",\"name\":\"Item ").append(id)
                .append("\",\"icon\":\"Item ").append(id).append(".png\"}");
        }
        mapping.append(']');
        AtomicInteger images = new AtomicInteger();
        byte[] image = png(1, 1);
        try (SandboxWikiData service = new SandboxWikiData(client(request -> {
            if (request.url().host().equals("prices.runescape.wiki")) return json(request, mapping.toString());
            images.incrementAndGet();
            return response(request, 200, image);
        }))) {
            for (int id = 1; id <= 257; id++) await(service.icon(id));
            await(service.icon(257));
            assertEquals(257, images.get());
            await(service.icon(1));
            assertEquals(258, images.get());
        }
    }

    @Test(timeout = 10000)
    public void malformedResponsesAreNotSuccessfulCacheEntriesAndRetryImmediately() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String[] invalid = {"{\"data\":null}", "{\"data\":[null]}", "{\"data\":[{\"timestamp\":1,\"avgHighPrice\":-1}]}",
            "{\"data\":[{\"timestamp\":1,\"avgHighPrice\":2147483648}]}", "{\"data\":[{\"timestamp\":0}]}"};
        try (SandboxWikiData service = new SandboxWikiData(client(request -> {
            int index = calls.getAndIncrement();
            return json(request, index < invalid.length ? invalid[index] : SERIES);
        }))) {
            for (String ignored : invalid) assertTrue(failure(service.timeseries(4151, Timestep.FIVE_MINUTES)).getMessage().contains("Wiki"));
            assertEquals(2, await(service.timeseries(4151, Timestep.FIVE_MINUTES)).getData().size());
            assertEquals(invalid.length + 1, calls.get());
        }

        AtomicInteger latestCalls = new AtomicInteger();
        try (SandboxWikiData service = new SandboxWikiData(client(request -> json(request,
            latestCalls.getAndIncrement() == 0 ? "{\"data\":null}" : LATEST)))) {
            assertTrue(failure(service.latest()).getMessage().contains("no data"));
            assertEquals(100, await(service.latest()).getData().get(4151).getHigh());
            assertEquals(2, latestCalls.get());
        }

        AtomicInteger mappingCalls = new AtomicInteger();
        try (SandboxWikiData service = new SandboxWikiData(client(request -> json(request,
            mappingCalls.getAndIncrement() == 0 ? "null" : MAPPING)))) {
            assertTrue(failure(service.mapping()).getMessage().contains("no JSON data"));
            assertEquals(1, await(service.mapping()).size());
            assertEquals(2, mappingCalls.get());
        }
    }

    @Test(timeout = 10000)
    public void failedIconsRetryAfterSixtySecondsWithoutRepeatingMapping() throws Exception {
        MutableClock clock = new MutableClock();
        AtomicInteger mappingCalls = new AtomicInteger();
        AtomicInteger imageCalls = new AtomicInteger();
        byte[] image = png(2, 2);
        try (SandboxWikiData service = new SandboxWikiData(client(request -> {
            if (request.url().host().equals("prices.runescape.wiki")) {
                mappingCalls.incrementAndGet();
                return json(request, MAPPING);
            }
            return response(request, imageCalls.incrementAndGet() == 1 ? 503 : 200, image);
        }), clock)) {
            assertTrue(failure(service.icon(4151)).getMessage().contains("503"));
            assertTrue(failure(service.icon(4151)).getMessage().contains("503"));
            assertEquals(1, imageCalls.get());
            clock.advance(61);
            assertEquals(2, await(service.icon(4151)).getWidth());
            assertEquals(2, imageCalls.get());
            assertEquals(1, mappingCalls.get());
        }
    }

    @Test(timeout = 10000)
    public void enforcesResponseAndImageSizeLimitsBeforeDecoding() throws Exception {
        try (SandboxWikiData service = new SandboxWikiData(client(request -> response(request, 200, new byte[10 * 1024 * 1024 + 1])))) {
            assertTrue(failure(service.latest()).getMessage().contains("size limit"));
        }
        byte[][] invalidImages = {new byte[1024 * 1024 + 1], "not a PNG".getBytes(StandardCharsets.UTF_8), png(513, 1)};
        String[] messages = {"size limit", "not a PNG", "dimensions"};
        for (int index = 0; index < invalidImages.length; index++) {
            byte[] image = invalidImages[index];
            try (SandboxWikiData service = new SandboxWikiData(client(request -> request.url().host().equals("prices.runescape.wiki")
                ? json(request, MAPPING) : response(request, 200, image)))) {
                assertTrue(failure(service.icon(4151)).getMessage().contains(messages[index]));
            }
        }
    }

    @Test(timeout = 10000)
    public void closeFailsPendingRequestsAndRejectsNewWork() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        SandboxWikiData service = new SandboxWikiData(client(request -> {
            requests.incrementAndGet();
            entered.countDown();
            awaitLatch(release);
            return json(request, MAPPING);
        }));
        try {
            CompletableFuture<BufferedImage> pending = service.icon(4151);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            service.close();
            assertTrue(failure(pending).getMessage().contains("closed"));
            assertTrue(failure(service.mapping()).getMessage().contains("closed"));
            assertTrue(failure(service.latest()).getMessage().contains("closed"));
            assertTrue(failure(service.timeseries(4151, Timestep.FIVE_MINUTES)).getMessage().contains("closed"));
            assertEquals(1, requests.get());
        } finally {
            release.countDown();
            service.close();
        }
    }

    @Test(timeout = 10000)
    public void blocksOtherHostsMethodsPathsCredentialsAndRedirects() throws Exception {
        String[] blocked = {"https://example.com/api/v1/osrs/latest", "http://prices.runescape.wiki/api/v1/osrs/latest",
            "https://prices.runescape.wiki:444/api/v1/osrs/latest", "https://prices.runescape.wiki/api/v1/dmm/latest",
            "https://oldschool.runescape.wiki/w/Main_Page", "https://oldschool.runescape.wiki/images/nested/test.png",
            "https://user:password@prices.runescape.wiki/api/v1/osrs/latest"};
        for (String url : blocked) assertBlocked(new Request.Builder().url(url).build());
        Request allowed = new Request.Builder().url("https://prices.runescape.wiki/api/v1/osrs/latest").build();
        SandboxWikiData.checkRequest(allowed);
        assertBlocked(allowed.newBuilder().header("Authorization", "Bearer test").build());
        assertBlocked(allowed.newBuilder().header("Cookie", "session=test").build());
        assertBlocked(allowed.newBuilder().post(okhttp3.RequestBody.create(MediaType.parse("text/plain"), "test")).build());
        AtomicInteger requests = new AtomicInteger();
        try (SandboxWikiData service = new SandboxWikiData(client(request -> {
            requests.incrementAndGet();
            return response(request, 302, new byte[0]).newBuilder().header("Location", "https://example.com/").build();
        }))) {
            assertTrue(failure(service.latest()).getMessage().contains("302"));
            assertEquals(1, requests.get());
        }
    }

    private static void assertBlocked(Request request) throws Exception {
        try {
            SandboxWikiData.checkRequest(request);
            fail("Expected request to be blocked: " + request.url());
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("sandbox"));
        }
    }

    private static OkHttpClient client(Responder responder) {
        return new OkHttpClient.Builder().addInterceptor(chain -> responder.respond(chain.request())).build();
    }

    private static Response json(Request request, String body) { return response(request, 200, body.getBytes(StandardCharsets.UTF_8)); }

    private static Response response(Request request, int code, byte[] body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(ResponseBody.create(MediaType.parse("application/octet-stream"), body)).build();
    }

    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", output);
        return output.toByteArray();
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(5, TimeUnit.SECONDS); }

    private static Throwable failure(CompletableFuture<?> future) throws Exception {
        try {
            await(future);
            throw new AssertionError("Expected a failed future");
        } catch (ExecutionException expected) {
            return expected.getCause();
        }
    }

    private static void awaitLatch(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("Fixture was not released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Fixture interrupted", interrupted);
        }
    }

    private interface Responder { Response respond(Request request) throws IOException; }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.ofEpochSecond(1700000100);
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
