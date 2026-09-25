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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

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
    private final Map<TimeseriesCacheKey, List<Consumer<TimeseriesResponse>>> pending = new HashMap<>();

    @Inject
    public TimeseriesFetcher(OkHttpClient httpClient, FlippingPlugin plugin) {
        this.httpClient = httpClient;
        this.plugin = plugin;
    }

    /**
     * Fetch price history, sharing one HTTP request for each item and timestep.
     * Successful results are delivered once to every caller. Failures are logged and
     * leave the key available for a later retry. Callbacks may run on the calling
     * thread (cache hit) or the HTTP dispatcher and must marshal UI work themselves.
     */
    public void fetch(int itemId, Timestep timestep, Consumer<TimeseriesResponse> callback) {
        Objects.requireNonNull(timestep, "timestep");
        Objects.requireNonNull(callback, "callback");
        TimeseriesCacheKey cacheKey = new TimeseriesCacheKey(itemId, timestep);
        TimeseriesResponse cachedResponse = null;
        synchronized (requestLock) {
            CachedTimeseries cachedData = cache.get(cacheKey);
            if (cachedData != null && !cachedData.isStale()) {
                cachedResponse = cachedData.getResponse();
            } else {
                List<Consumer<TimeseriesResponse>> callbacks = pending.get(cacheKey);
                if (callbacks != null) {
                    callbacks.add(callback);
                    return;
                }
                callbacks = new ArrayList<>();
                callbacks.add(callback);
                pending.put(cacheKey, callbacks);
            }
        }
        if (cachedResponse != null) {
            notifyCallback(callback, cachedResponse);
            return;
        }

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

                    List<Consumer<TimeseriesResponse>> callbacks;
                    synchronized (requestLock) {
                        cache.put(cacheKey, new CachedTimeseries(result, Instant.now(), timestep));
                        callbacks = pending.remove(cacheKey);
                    }
                    // Never run callers while holding the cache/request lock. A callback
                    // can re-enter fetch or fail without blocking the other subscribers.
                    for (Consumer<TimeseriesResponse> consumer : callbacks) {
                        notifyCallback(consumer, result);
                    }
                }
            });
        } catch (RuntimeException e) {
            // Enqueue can fail synchronously, for example after dispatcher shutdown.
            fail(cacheKey, itemId, e);
        }
    }

    private void fail(TimeseriesCacheKey cacheKey, int itemId, Exception error) {
        synchronized (requestLock) {
            pending.remove(cacheKey);
        }
        log.warn("[TimeseriesFetcher] Request failed for item {}: {}", itemId, error.getMessage());
    }

    private void notifyCallback(Consumer<TimeseriesResponse> callback, TimeseriesResponse response) {
        try {
            callback.accept(response);
        } catch (RuntimeException e) {
            log.warn("[TimeseriesFetcher] Price history callback failed", e);
        }
    }
}
