package com.flippingutilities.jobs;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.CachedTimeseries;
import com.flippingutilities.model.TimeseriesResponse;
import com.google.gson.JsonSyntaxException;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Slf4j
@Singleton
public class TimeseriesFetcher {
    private static final String TIMESERIES_API_URL = "https://prices.runescape.wiki/api/v1/osrs/timeseries";
    private static final String QUERY_PARAM_TIMESTEP = "timestep";
    private static final String QUERY_PARAM_ID = "id";
    private static final String TIMESTEP_VALUE = "5m";
    private static final String USER_AGENT_HEADER = "User-Agent";
    private static final String USER_AGENT_VALUE = "FlippingUtilities";

    private final OkHttpClient httpClient;
    private final FlippingPlugin plugin;
    private final Map<Integer, CachedTimeseries> cache = new ConcurrentHashMap<>();

    @Inject
    public TimeseriesFetcher(OkHttpClient httpClient, FlippingPlugin plugin) {
        this.httpClient = httpClient;
        this.plugin = plugin;
    }

    public void fetch(int itemId, Consumer<TimeseriesResponse> callback) {
        CachedTimeseries cachedData = cache.get(itemId);
        if (cachedData != null && !cachedData.isStale()) {
            callback.accept(cachedData.getResponse());
            return;
        }

        HttpUrl url = HttpUrl
                .parse(TIMESERIES_API_URL)
                .newBuilder()
                .addQueryParameter(QUERY_PARAM_TIMESTEP, TIMESTEP_VALUE)
                .addQueryParameter(QUERY_PARAM_ID, String.valueOf(itemId))
                .build();

        Request request = new Request.Builder()
                .header(USER_AGENT_HEADER, USER_AGENT_VALUE)
                .url(url)
                .build();

        httpClient
                .newCall(request)
                .enqueue(
                        new Callback() {
                            @Override
                            public void onFailure(Call call, IOException e) {
                                log.warn("Failed to fetch timeseries for item {}", itemId, e);
                            }

                            @Override
                            public void onResponse(Call call, Response response) throws IOException {
                                try (ResponseBody body = response.body()) {
                                    if (!response.isSuccessful()) {
                                        return;
                                    }
                                    try {
                                        TimeseriesResponse tsResponse = plugin.gson.fromJson(
                                                body.string(),
                                                TimeseriesResponse.class
                                        );
                                        cache.put(
                                                itemId,
                                                new CachedTimeseries(tsResponse, Instant.now())
                                        );
                                        callback.accept(tsResponse);
                                    } catch (JsonSyntaxException e) {
                                        log.warn("Failed to parse timeseries response", e);
                                    }
                                }
                            }
                        }
                );
    }
}
