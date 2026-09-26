package com.flippingutilities.model;

import com.flippingutilities.utilities.LenientLongAdapter;
import com.google.gson.annotations.JsonAdapter;
import lombok.Getter;

/**
 * One point of a wiki v2 timeseries. Prices may exceed 32 bit integers and averages may
 * include decimal points (see LenientLongAdapter); null means no trades of that side in
 * the interval.
 */
@Getter
public final class TimeseriesPoint {
    private final long timestamp;

    @JsonAdapter(LenientLongAdapter.class)
    private final Long avgHighPrice;

    @JsonAdapter(LenientLongAdapter.class)
    private final Long avgLowPrice;

    public TimeseriesPoint(long timestamp, Long avgHighPrice, Long avgLowPrice) {
        this.timestamp = timestamp;
        this.avgHighPrice = avgHighPrice;
        this.avgLowPrice = avgLowPrice;
    }
}
