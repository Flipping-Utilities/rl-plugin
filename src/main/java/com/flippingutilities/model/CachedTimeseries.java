package com.flippingutilities.model;

import java.time.Duration;
import java.time.Instant;

public final class CachedTimeseries {
    private static final Duration CACHE_DURATION = Duration.ofMinutes(5);

    private final TimeseriesResponse response;
    private final Instant fetchTime;

    public CachedTimeseries(TimeseriesResponse response, Instant fetchTime) {
        this.response = response;
        this.fetchTime = fetchTime;
    }

    public TimeseriesResponse getResponse() {
        return response;
    }

    public boolean isStale() {
        return Duration.between(fetchTime, Instant.now()).compareTo(CACHE_DURATION) > 0;
    }
}
