package com.flippingutilities.model;

import lombok.Getter;

/**
 * Lookback window for the wiki v2 timeseries endpoint (/osrs/timeseries?lookback=...).
 *
 * v2 replaced v1's granularity parameter ("timestep=5m") with lookback periods; the API
 * now decides the point spacing and reports it in the response. intervalSeconds holds the
 * spacing the API currently returns for each lookback (used for cache expiry and axis
 * labels until the response's authoritative timestep is read).
 */
@Getter
public enum Timestep {
    SIX_HOURS("6h", "Last 6 Hours", 5 * 60, 6 * 60 * 60, 7),
    TWENTY_FOUR_HOURS("24h", "Last 24 Hours", 5 * 60, 24 * 60 * 60, 8),
    SEVEN_DAYS("7d", "Last 7 Days", 60 * 60, 7 * 24 * 60 * 60, 7),
    THIRTY_DAYS("30d", "Last 30 Days", 6 * 60 * 60, 30 * 24 * 60 * 60, 6),
    SIX_MONTHS("6m", "Last 6 Months", 24 * 60 * 60, 182 * 24 * 60 * 60, 6),
    ONE_YEAR("1y", "Last Year", 24 * 60 * 60, 365 * 24 * 60 * 60, 6);

    /** api lookback parameter value (e.g., "6h", "7d") */
    private final String apiValue;
    /** display name shown to user (e.g., "Last 6 Hours") */
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
