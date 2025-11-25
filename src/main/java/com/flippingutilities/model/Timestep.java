package com.flippingutilities.model;

import lombok.Getter;

@Getter
public enum Timestep {
    FIVE_MINUTES("5m", "Last 24 Hours", 5 * 60, 24 * 60 * 60, 8),
    ONE_HOUR("1h", "Last 7 Days", 60 * 60, 7 * 24 * 60 * 60, 7),
    SIX_HOURS("6h", "Last 3 Months", 6 * 60 * 60, 90 * 24 * 60 * 60, 4),
    TWENTY_FOUR_HOURS("24h", "Last Year", 24 * 60 * 60, 365 * 24 * 60 * 60, 6);

    private final String apiValue;
    private final String displayName;
    private final long intervalSeconds;
    private final long maxTimeRangeSeconds;
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
