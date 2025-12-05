package com.flippingutilities.model;

import lombok.Getter;

/**
 * represents different time intervals for fetching and displaying timeseries data.
 * each timestep defines the granularity of data points, the display period, and chart configuration.
 */
@Getter
public enum Timestep {
    FIVE_MINUTES("5m", "Last 24 Hours", 5 * 60, 24 * 60 * 60, 8),
    ONE_HOUR("1h", "Last 7 Days", 60 * 60, 7 * 24 * 60 * 60, 7),
    SIX_HOURS("6h", "Last 3 Months", 6 * 60 * 60, 90 * 24 * 60 * 60, 4),
    TWENTY_FOUR_HOURS("24h", "Last Year", 24 * 60 * 60, 365 * 24 * 60 * 60, 6);

    /** api parameter value for this timestep (e.g., "5m", "1h") */
    private final String apiValue;
    /** display name shown to user (e.g., "Last 24 Hours") */
    private final String displayName;
    /** time interval between data points in seconds */
    private final long intervalSeconds;
    /** maximum time range covered by this timestep in seconds */
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
