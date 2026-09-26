package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.model.LruLinkedHashMap;
import com.flippingutilities.model.TimeseriesPoint;
import com.flippingutilities.model.TimeseriesResponse;
import com.flippingutilities.model.Timestep;
import com.flippingutilities.utilities.WikiItemMargins;
import com.flippingutilities.utilities.WikiRequest;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.CookieJar;
import okhttp3.Dispatcher;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Public Wiki reads only; never shares the plugin's client, authentication, or disk cache. */
final class SandboxWikiData implements AutoCloseable {
    static final String USER_AGENT = "FlippingUtilities sandbox - https://github.com/Flipping-Utilities/rl-plugin";
    private static final String API = "https://prices.runescape.wiki/api/v1/osrs/";
    private static final int JSON_LIMIT = 10 * 1024 * 1024;
    private static final int IMAGE_LIMIT = 1024 * 1024;
    private final Object lock = new Object();
    private final Clock clock;
    private final ExecutorService workers;
    private final OkHttpClient client;
    private final Map<String, CompletableFuture<?>> pending = new HashMap<>();
    private final Set<Call> calls = new HashSet<>();
    private final Map<String, Cached<TimeseriesResponse>> series = new LruLinkedHashMap<>(40);
    private final Map<Integer, BufferedImage> images = new LruLinkedHashMap<>(256);
    private final Map<Integer, Cached<IOException>> imageFailures = new LruLinkedHashMap<>(256);
    private List<Item> mapping;
    private Map<Integer, Item> items = Collections.emptyMap();
    private Cached<WikiRequest> latest;
    private boolean closed;

    SandboxWikiData() { this(new OkHttpClient(), Clock.systemUTC()); }

    /** The supplied client's interceptors provide an in-memory HTTP boundary for tests. */
    SandboxWikiData(OkHttpClient client) { this(client, Clock.systemUTC()); }

    SandboxWikiData(OkHttpClient supplied, Clock clock) {
        this.clock = clock;
        workers = Executors.newFixedThreadPool(4, task -> {
            Thread thread = new Thread(task, "sandbox-wiki");
            thread.setDaemon(true);
            return thread;
        });
        Dispatcher dispatcher = new Dispatcher(workers);
        dispatcher.setMaxRequests(4);
        dispatcher.setMaxRequestsPerHost(4);
        OkHttpClient.Builder builder = supplied.newBuilder()
            .dispatcher(dispatcher).connectionPool(new ConnectionPool())
            .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE).cache(null)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS);
        builder.interceptors().add(0, chain -> {
            checkRequest(chain.request());
            return chain.proceed(chain.request());
        });
        builder.addNetworkInterceptor(chain -> {
            checkRequest(chain.request());
            return chain.proceed(chain.request());
        });
        client = builder.build();
    }

    CompletableFuture<List<Item>> mapping() {
        synchronized (lock) {
            if (closed) return failed(closedError());
            if (mapping != null) return CompletableFuture.completedFuture(mapping);
            return fetch("mapping", HttpUrl.parse(API + "mapping"), JSON_LIMIT, this::parseMapping, result -> {
                mapping = result;
                Map<Integer, Item> index = new HashMap<>();
                for (Item item : result) index.put(item.id, item);
                items = index;
            });
        }
    }

    CompletableFuture<WikiRequest> latest() {
        synchronized (lock) {
            if (closed) return failed(closedError());
            if (fresh(latest)) return CompletableFuture.completedFuture(latest.value);
            return fetch("latest", HttpUrl.parse(API + "latest"), JSON_LIMIT, this::parseLatest,
                result -> latest = new Cached<>(result, clock.instant().plusSeconds(60)));
        }
    }

    CompletableFuture<TimeseriesResponse> timeseries(int itemId, Timestep step) {
        synchronized (lock) {
            if (closed) return failed(closedError());
            if (itemId <= 0 || step == null) return failed(new IOException("Choose a valid item and chart interval."));
            String key = "series:" + itemId + ":" + step.getApiValue();
            Cached<TimeseriesResponse> value = series.get(key);
            if (fresh(value)) return CompletableFuture.completedFuture(value.value);
            HttpUrl url = HttpUrl.parse(API + "timeseries").newBuilder()
                .addQueryParameter("id", Integer.toString(itemId))
                .addQueryParameter("timestep", step.getApiValue()).build();
            return fetch(key, url, JSON_LIMIT, this::parseTimeseries, result -> {
                Instant fetched = clock.instant();
                // Same expiry as CachedTimeseries: next interval boundary, then a 15-second buffer.
                long untilBoundary = step.getIntervalSeconds() - Math.floorMod(fetched.getEpochSecond(), step.getIntervalSeconds());
                series.put(key, new Cached<>(result, fetched.plusSeconds(untilBoundary + 15)));
            });
        }
    }

    CompletableFuture<BufferedImage> icon(int itemId) {
        synchronized (lock) {
            if (closed) return failed(closedError());
            if (itemId <= 0) return failed(new IOException("Choose a valid item for its icon."));
            if (images.containsKey(itemId)) return CompletableFuture.completedFuture(images.get(itemId));
            Cached<IOException> failure = imageFailures.get(itemId);
            if (fresh(failure)) return failed(failure.value);
            String key = "icon:" + itemId;
            CompletableFuture<BufferedImage> existing = pending(key);
            if (existing != null) return existing;
            CompletableFuture<BufferedImage> future = new CompletableFuture<>();
            pending.put(key, future);
            Consumer<BufferedImage> save = image -> {
                images.put(itemId, image);
                imageFailures.remove(itemId);
            };
            mapping().whenComplete((ignored, error) -> {
                if (error != null) {
                    finish(key, future, null, error, save);
                    return;
                }
                final Item item;
                synchronized (lock) { item = items.get(itemId); }
                if (item == null) {
                    finish(key, future, null, new IOException("The Wiki has no icon mapping for item " + itemId + "."), save);
                    return;
                }
                HttpUrl url = new HttpUrl.Builder().scheme("https").host("oldschool.runescape.wiki")
                    .addPathSegment("images").addPathSegment(item.icon.replace(' ', '_')).build();
                request(key, future, url, IMAGE_LIMIT, SandboxWikiData::parseImage, save);
            });
            return future;
        }
    }

    private <T> CompletableFuture<T> fetch(String key, HttpUrl url, int byteLimit, Decoder<T> decode, Consumer<T> save) {
        CompletableFuture<T> existing = pending(key);
        if (existing != null) return existing;
        CompletableFuture<T> future = new CompletableFuture<>();
        pending.put(key, future);
        request(key, future, url, byteLimit, decode, save);
        return future;
    }

    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> pending(String key) { return (CompletableFuture<T>) pending.get(key); }

    private <T> void request(String key, CompletableFuture<T> future, HttpUrl url, int byteLimit,
                             Decoder<T> decode, Consumer<T> save) {
        final Call call;
        synchronized (lock) {
            if (closed || future.isDone()) return;
            Request request = new Request.Builder().url(url).get().header("User-Agent", USER_AGENT).build();
            call = client.newCall(request);
            calls.add(call);
        }
        try {
            call.enqueue(new Callback() {
                @Override public void onFailure(Call ignored, IOException error) {
                    synchronized (lock) { calls.remove(call); }
                    finish(key, future, null, error, save);
                }

                @Override public void onResponse(Call ignored, Response response) {
                    T result = null;
                    Exception failure = null;
                    try (Response owned = response) {
                        if (!owned.isSuccessful()) throw new IOException("Wiki request failed (HTTP " + owned.code() + ").");
                        if (owned.body() == null) throw new IOException("Wiki response had no body.");
                        result = decode.read(readBody(owned.body(), byteLimit));
                    } catch (Exception error) {
                        failure = error;
                    } finally {
                        synchronized (lock) { calls.remove(call); }
                    }
                    finish(key, future, result, failure, save);
                }
            });
        } catch (RuntimeException error) {
            synchronized (lock) { calls.remove(call); }
            finish(key, future, null, error, save);
        }
    }

    private <T> void finish(String key, CompletableFuture<T> future, T result, Throwable failure, Consumer<T> save) {
        IOException error = null;
        synchronized (lock) {
            if (pending.get(key) != future) return;
            pending.remove(key);
            if (closed) failure = closedError();
            if (failure == null) {
                save.accept(result);
            } else {
                error = failure instanceof IOException ? (IOException) failure
                    : new IOException("Could not read Wiki data: " + failure.getMessage(), failure);
                if (!closed && key.startsWith("icon:")) {
                    imageFailures.put(Integer.parseInt(key.substring(5)), new Cached<>(error, clock.instant().plusSeconds(60)));
                }
            }
        }
        // Subscribers may marshal UI work or initiate another request; never run them under the service lock.
        if (error == null) future.complete(result);
        else future.completeExceptionally(error);
    }

    private boolean fresh(Cached<?> value) { return value != null && !clock.instant().isAfter(value.expires); }

    static void checkRequest(Request request) throws IOException {
        HttpUrl url = request.url();
        boolean allowed = "GET".equals(request.method()) && "https".equals(url.scheme()) && url.port() == 443
            && url.username().isEmpty() && url.password().isEmpty();
        if ("prices.runescape.wiki".equals(url.host())) {
            allowed &= url.encodedPath().equals("/api/v1/osrs/mapping") || url.encodedPath().equals("/api/v1/osrs/latest")
                || url.encodedPath().equals("/api/v1/osrs/timeseries");
        } else if ("oldschool.runescape.wiki".equals(url.host())) {
            allowed &= url.encodedPath().startsWith("/images/") && url.pathSegments().size() == 2
                && url.encodedQuery() == null;
        } else {
            allowed = false;
        }
        if (!allowed) throw new IOException("The sandbox only permits public Wiki GET requests.");
        if (request.header("Authorization") != null || request.header("Cookie") != null
            || request.header("Proxy-Authorization") != null) {
            throw new IOException("Credentials are not permitted on sandbox Wiki requests.");
        }
    }

    private List<Item> parseMapping(byte[] bytes) throws IOException {
        JsonElement parsed = json(bytes);
        if (!parsed.isJsonArray() || parsed.getAsJsonArray().size() == 0) throw new IOException("Wiki item mapping was empty or invalid.");
        List<Item> result = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        for (JsonElement entry : parsed.getAsJsonArray()) {
            if (!entry.isJsonObject()) throw new IOException("Wiki item mapping contained an invalid item.");
            JsonObject value = entry.getAsJsonObject();
            int id = (int) integer(value.get("id"), 1, Integer.MAX_VALUE, "item ID");
            String name = string(value.get("name"), "item name");
            String icon = string(value.get("icon"), "item icon");
            int limit = absent(value.get("limit")) ? 0 : (int) integer(value.get("limit"), 0, Integer.MAX_VALUE, "GE limit");
            if (name.length() > 250 || icon.length() > 250 || !icon.toLowerCase(java.util.Locale.ROOT).endsWith(".png")
                || icon.contains("/") || icon.contains("\\") || icon.chars().anyMatch(Character::isISOControl)) {
                throw new IOException("Wiki item mapping contained an invalid name or PNG icon.");
            }
            if (!ids.add(id)) throw new IOException("Wiki item mapping contained duplicate item " + id + ".");
            result.add(new Item(id, name, limit, icon));
        }
        return Collections.unmodifiableList(result);
    }

    private WikiRequest parseLatest(byte[] bytes) throws IOException {
        JsonElement data = data(bytes);
        if (!data.isJsonObject()) throw new IOException("Wiki latest prices contained invalid data.");
        Map<Integer, WikiItemMargins> prices = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : data.getAsJsonObject().entrySet()) {
            final int id;
            try { id = Integer.parseInt(entry.getKey()); }
            catch (NumberFormatException error) { throw new IOException("Wiki latest prices contained an invalid item ID.", error); }
            if (id <= 0 || !entry.getValue().isJsonObject()) throw new IOException("Wiki latest prices contained an invalid item.");
            JsonObject value = entry.getValue().getAsJsonObject();
            WikiItemMargins margin = new WikiItemMargins();
            margin.setHigh(optionalInt(value.get("high"), "high price"));
            margin.setLow(optionalInt(value.get("low"), "low price"));
            margin.setHighTime(optionalTime(value.get("highTime")));
            margin.setLowTime(optionalTime(value.get("lowTime")));
            prices.put(id, margin);
        }
        WikiRequest result = new WikiRequest();
        result.setData(Collections.unmodifiableMap(prices));
        return result;
    }

    private TimeseriesResponse parseTimeseries(byte[] bytes) throws IOException {
        JsonElement data = data(bytes);
        if (!data.isJsonArray()) throw new IOException("Wiki price history contained invalid data.");
        List<TimeseriesPoint> points = new ArrayList<>();
        for (JsonElement entry : data.getAsJsonArray()) {
            if (!entry.isJsonObject()) throw new IOException("Wiki price history contained an invalid point.");
            JsonObject point = entry.getAsJsonObject();
            long timestamp = integer(point.get("timestamp"), 1, 253402300799L, "history timestamp");
            Integer high = absent(point.get("avgHighPrice")) ? null : optionalInt(point.get("avgHighPrice"), "average high price");
            Integer low = absent(point.get("avgLowPrice")) ? null : optionalInt(point.get("avgLowPrice"), "average low price");
            points.add(new TimeseriesPoint(timestamp, high, low));
        }
        points.sort(java.util.Comparator.comparingLong(TimeseriesPoint::getTimestamp));
        return new TimeseriesResponse(Collections.unmodifiableList(points));
    }

    private static JsonElement json(byte[] bytes) throws IOException {
        try {
            JsonElement value = new Gson().fromJson(new String(bytes, StandardCharsets.UTF_8), JsonElement.class);
            if (value == null || value.isJsonNull()) throw new IOException("Wiki response contained no JSON data.");
            return value;
        } catch (RuntimeException error) {
            throw new IOException("Wiki response contained invalid JSON.", error);
        }
    }

    private static JsonElement data(byte[] bytes) throws IOException {
        JsonElement root = json(bytes);
        if (!root.isJsonObject() || absent(root.getAsJsonObject().get("data"))) throw new IOException("Wiki response contained no data.");
        return root.getAsJsonObject().get("data");
    }

    private static boolean absent(JsonElement value) { return value == null || value.isJsonNull(); }

    private static String string(JsonElement value, String label) throws IOException {
        if (absent(value) || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
            || value.getAsString().trim().isEmpty()) throw new IOException("Wiki response contained an invalid " + label + ".");
        return value.getAsString();
    }

    private static long integer(JsonElement value, long minimum, long maximum, String label) throws IOException {
        try {
            if (absent(value) || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new NumberFormatException();
            long number = value.getAsBigDecimal().longValueExact();
            if (number < minimum || number > maximum) throw new NumberFormatException();
            return number;
        } catch (RuntimeException error) {
            throw new IOException("Wiki response contained an invalid " + label + ".", error);
        }
    }

    private static int optionalInt(JsonElement value, String label) throws IOException {
        return absent(value) ? 0 : (int) integer(value, 0, Integer.MAX_VALUE, label);
    }

    private static long optionalTime(JsonElement value) throws IOException {
        return absent(value) ? 0 : integer(value, 0, 253402300799L, "price timestamp");
    }

    private static byte[] readBody(ResponseBody body, int limit) throws IOException {
        if (body.contentLength() > limit) throw new IOException("Wiki response exceeded the size limit.");
        try (InputStream stream = body.byteStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (output.size() + count > limit) throw new IOException("Wiki response exceeded the size limit.");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static BufferedImage parseImage(byte[] bytes) throws IOException {
        byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        if (bytes.length < signature.length) throw new IOException("Wiki icon was not a PNG image.");
        for (int index = 0; index < signature.length; index++) {
            if (bytes[index] != signature[index]) throw new IOException("Wiki icon was not a PNG image.");
        }
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Wiki icon could not be decoded.");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 512 || height > 512) throw new IOException("Wiki icon dimensions exceeded 512 pixels.");
                BufferedImage image = reader.read(0);
                if (image == null) throw new IOException("Wiki icon could not be decoded.");
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    private static <T> CompletableFuture<T> failed(Throwable failure) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(failure);
        return future;
    }

    private static IOException closedError() { return new IOException("The Wiki data service is closed."); }

    @Override public void close() {
        List<Call> cancel;
        List<CompletableFuture<?>> fail;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            cancel = new ArrayList<>(calls);
            fail = new ArrayList<>(pending.values());
            pending.clear();
            calls.clear();
            mapping = null;
            items = Collections.emptyMap();
            latest = null;
            series.clear();
            images.clear();
            imageFailures.clear();
        }
        for (Call call : cancel) call.cancel();
        for (CompletableFuture<?> future : fail) future.completeExceptionally(closedError());
        client.dispatcher().cancelAll();
        workers.shutdownNow();
        client.connectionPool().evictAll();
    }

    static final class Item {
        final int id;
        final String name;
        final int limit;
        final String icon;
        Item(int id, String name, int limit, String icon) {
            this.id = id;
            this.name = name;
            this.limit = limit;
            this.icon = icon;
        }
        int getId() { return id; }
        String getName() { return name; }
        int getLimit() { return limit; }
        String getIcon() { return icon; }
    }

    private interface Decoder<T> { T read(byte[] bytes) throws IOException; }

    private static final class Cached<T> {
        final T value;
        final Instant expires;
        Cached(T value, Instant expires) { this.value = value; this.expires = expires; }
    }
}
