package com.flippingutilities.jobs;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.CachedTimeseries;
import com.flippingutilities.model.LruLinkedHashMap;
import com.flippingutilities.model.TimeseriesCacheKey;
import com.flippingutilities.model.Timestep;
import com.flippingutilities.model.TimeseriesResponse;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Singleton
public class TimeseriesFetcher {
    private static final String TIMESERIES_API_URL = "https://prices.runescape.wiki/api/v1/osrs/timeseries";
    private static final String QUERY_PARAM_TIMESTEP = "timestep";
    private static final String QUERY_PARAM_ID = "id";
    private static final String USER_AGENT_HEADER = "User-Agent";
    private static final String USER_AGENT_VALUE = "FlippingUtilities - discord.gg/flipping";
    private static final int MAX_CACHE_SIZE = 40;

    private final OkHttpClient httpClient;
    private final FlippingPlugin plugin;
    // Cache lookup and request registration must be atomic: multiple UI consumers can
    // request the same graph before its first response reaches the cache.
    private final Object requestLock = new Object();
    private final Map<TimeseriesCacheKey, CachedTimeseries> cache = new LruLinkedHashMap<>(MAX_CACHE_SIZE);
    private final Map<TimeseriesCacheKey, CompletableFuture<TimeseriesResponse>> pending = new HashMap<>();

    @Inject
    public TimeseriesFetcher(OkHttpClient httpClient, FlippingPlugin plugin) {
        this.httpClient = httpClient;
        this.plugin = plugin;
    }

    /** Compatibility entry point for callers that only need successful responses. */
    public void fetch(int itemId, Timestep timestep, Consumer<TimeseriesResponse> callback) {
        Objects.requireNonNull(callback, "callback");
        fetch(itemId, timestep).thenAccept(response -> notifyCallback(callback, response));
    }

    /**
     * Share one HTTP request per item and timestep, completing every caller on
     * success or failure. Each caller owns its future: cancelling one consumer
     * does not cancel the shared request or prevent another view from receiving it.
     * Completions can run on the calling thread (cache hit) or HTTP dispatcher.
     */
    public CompletableFuture<TimeseriesResponse> fetch(int itemId, Timestep timestep) {
        Objects.requireNonNull(timestep, "timestep");
        TimeseriesCacheKey cacheKey = new TimeseriesCacheKey(itemId, timestep);
        CompletableFuture<TimeseriesResponse> shared;
        synchronized (requestLock) {
            CachedTimeseries cachedData = cache.get(cacheKey);
            if (cachedData != null && !cachedData.isStale()) {
                return CompletableFuture.completedFuture(cachedData.getResponse());
            }
            shared = pending.get(cacheKey);
            if (shared != null) {
                return shared.thenApply(response -> response);
            }
            shared = new CompletableFuture<>();
            pending.put(cacheKey, shared);
        }
        CompletableFuture<TimeseriesResponse> caller = shared.thenApply(response -> response);

        try {
            HttpUrl url = HttpUrl
                    .parse(TIMESERIES_API_URL)
                    .newBuilder()
                    .addQueryParameter(QUERY_PARAM_TIMESTEP, timestep.getApiValue())
                    .addQueryParameter(QUERY_PARAM_ID, String.valueOf(itemId))
                    .build();

            Request request = new Request.Builder()
                    .header(USER_AGENT_HEADER, USER_AGENT_VALUE)
                    .url(url)
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    fail(cacheKey, itemId, e);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    TimeseriesResponse result;
                    try (ResponseBody body = response.body()) {
                        if (!response.isSuccessful()) {
                            throw new IOException("HTTP " + response.code());
                        }
                        if (body == null) {
                            throw new IOException("Response has no body");
                        }
                        result = plugin.gson.fromJson(body.string(), TimeseriesResponse.class);
                        if (result == null || result.getData() == null || result.getData().contains(null)) {
                            throw new IOException("Response has no valid price history list");
                        }
                    } catch (IOException | RuntimeException e) {
                        fail(cacheKey, itemId, e);
                        return;
                    }

                    CompletableFuture<TimeseriesResponse> completion;
                    synchronized (requestLock) {
                        cache.put(cacheKey, new CachedTimeseries(result, Instant.now(), timestep));
                        completion = pending.remove(cacheKey);
                    }
                    // Never run callers while holding the cache/request lock. A callback
                    // can re-enter fetch or fail without blocking the other subscribers.
                    completion.complete(result);
                }
            });
        } catch (RuntimeException e) {
            // Enqueue can fail synchronously, for example after dispatcher shutdown.
            fail(cacheKey, itemId, e);
        }
        return caller;
    }

    private void fail(TimeseriesCacheKey cacheKey, int itemId, Exception error) {
        CompletableFuture<TimeseriesResponse> completion;
        synchronized (requestLock) {
            completion = pending.remove(cacheKey);
        }
        log.warn("[TimeseriesFetcher] Request failed for item {}: {}", itemId, error.getMessage());
        if (completion != null) {
            completion.completeExceptionally(error);
        }
    }

    private void notifyCallback(Consumer<TimeseriesResponse> callback, TimeseriesResponse response) {
        try {
            callback.accept(response);
        } catch (RuntimeException e) {
            log.warn("[TimeseriesFetcher] Price history callback failed", e);
        }
    }
}
