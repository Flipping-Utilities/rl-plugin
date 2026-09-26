package com.flippingutilities.model;

import lombok.Getter;

@Getter
public final class TimeseriesPoint {
    private final long timestamp;
    private final Long avgHighPrice;
    private final Long avgLowPrice;

    public TimeseriesPoint(long timestamp, Long avgHighPrice, Long avgLowPrice) {
        this.timestamp = timestamp;
        this.avgHighPrice = avgHighPrice;
        this.avgLowPrice = avgLowPrice;
    }
}
