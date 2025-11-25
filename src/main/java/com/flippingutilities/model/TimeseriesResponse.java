package com.flippingutilities.model;

import lombok.Getter;
import java.util.List;

@Getter
public final class TimeseriesResponse {
    private final List<TimeseriesPoint> data;

    public TimeseriesResponse(List<TimeseriesPoint> data) {
        this.data = data;
    }
}
