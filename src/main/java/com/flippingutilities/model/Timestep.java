package com.flippingutilities.model;

import lombok.Getter;

/**
 * Lookback window for the wiki v2 timeseries endpoint (/osrs/timeseries?lookback=...).
 *
 * v2 replaced v1's granularity parameter ("timestep=5m") with lookback periods; the API
 * now decides the point spacing and reports it in the response. intervalSeconds holds the
 * spacing the API currently returns for each lookback, as a cache-expiry fallback.
 * Enum names are persisted in RuneLite configuration, so the original granularity names
 * retain their original display windows when requesting v2 lookbacks.
 */
@Getter
public enum Timestep {
    FIVE_MINUTES("24h", "Last 24 Hours", 5 * 60, 24 * 60 * 60, 8),
    ONE_HOUR("7d", "Last 7 Days", 60 * 60, 7 * 24 * 60 * 60, 7),
    SIX_HOURS("30d", "Last 1 Month", 6 * 60 * 60, 30 * 24 * 60 * 60, 4),
    TWENTY_FOUR_HOURS("1y", "Last Year", 24 * 60 * 60, 365 * 24 * 60 * 60, 6);

    /** api lookback parameter value (e.g., "24h", "7d") */
    private final String apiValue;
    /** display name shown to user (e.g., "Last 24 Hours") */
    private final String displayName;
    /** point spacing the API currently returns for this lookback, in seconds */
    private final long intervalSeconds;
    /** lookback span in seconds */
    private final long maxTimeRangeSeconds;
    /** number of labels to display on chart axis */
    private final int labelCount;

    Timestep(String apiValue, String displayName, long intervalSeconds, long maxTimeRangeSeconds, int labelCount) {
        this.apiValue = apiValue;
        this.displayName = displayName;
        this.intervalSeconds = intervalSeconds;
        this.maxTimeRangeSeconds = maxTimeRangeSeconds;
        this.labelCount = labelCount;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
