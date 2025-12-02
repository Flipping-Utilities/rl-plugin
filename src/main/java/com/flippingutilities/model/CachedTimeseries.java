package com.flippingutilities.model;

import java.time.Duration;
import java.time.Instant;

public final class CachedTimeseries {
    private static final long BUFFER_SECONDS = 15;

    private final TimeseriesResponse response;
    private final Instant fetchTime;
    private final Timestep timestep;

    public CachedTimeseries(TimeseriesResponse response, Instant fetchTime, Timestep timestep) {
        this.response = response;
        this.fetchTime = fetchTime;
        this.timestep = timestep;
    }

    public TimeseriesResponse getResponse() {
        return response;
    }

    public boolean isStale() {
        long cacheExpirationSeconds = calculateCacheExpiration();
        Duration cacheExpiration = Duration.ofSeconds(cacheExpirationSeconds);
        return Duration.between(fetchTime, Instant.now()).compareTo(cacheExpiration) > 0;
    }

    private long calculateCacheExpiration() {
        long currentSeconds = fetchTime.getEpochSecond();
        long intervalSeconds = timestep.getIntervalSeconds();
        long secondsSinceLastInterval = currentSeconds % intervalSeconds;
        long secondsUntilNextInterval = intervalSeconds - secondsSinceLastInterval;
        return secondsUntilNextInterval + BUFFER_SECONDS;
    }
}
