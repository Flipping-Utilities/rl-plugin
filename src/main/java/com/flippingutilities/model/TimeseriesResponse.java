package com.flippingutilities.model;

import lombok.Getter;
import java.util.List;

/**
 * Wiki v2 timeseries response: the points plus the lookback window the API actually
 * returned (itemId, window bounds, and the point spacing in seconds — the spacing is
 * decided by the API and may change without warning, so it is read from the response
 * rather than assumed).
 */
@Getter
public final class TimeseriesResponse {
    private final List<TimeseriesPoint> data;
    private final Long itemId;
    private final Long startTimestamp;
    private final Long endTimestamp;
    private final Long timestep;

    public TimeseriesResponse(List<TimeseriesPoint> data) {
        this(data, null, null, null, null);
    }

    public TimeseriesResponse(List<TimeseriesPoint> data, Long itemId, Long startTimestamp,
                              Long endTimestamp, Long timestep) {
        this.data = data;
        this.itemId = itemId;
        this.startTimestamp = startTimestamp;
        this.endTimestamp = endTimestamp;
        this.timestep = timestep;
    }
}
