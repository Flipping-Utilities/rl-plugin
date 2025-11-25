package com.flippingutilities.model;

import lombok.Getter;

@Getter
public final class TimeseriesPoint {
    private final long timestamp;
    private final Integer avgHighPrice;
    private final Integer avgLowPrice;

    public TimeseriesPoint(long timestamp, Integer avgHighPrice, Integer avgLowPrice) {
        this.timestamp = timestamp;
        this.avgHighPrice = avgHighPrice;
        this.avgLowPrice = avgLowPrice;
    }
}
