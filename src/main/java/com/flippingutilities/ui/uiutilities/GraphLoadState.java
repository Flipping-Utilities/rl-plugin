package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.model.TimeseriesResponse;

public enum GraphLoadState {
    LOADING, READY, EMPTY, FAILED;

    public static GraphLoadState fromResponse(TimeseriesResponse response) {
        return response.getData().stream().anyMatch(point ->
            point.getAvgHighPrice() != null || point.getAvgLowPrice() != null) ? READY : EMPTY;
    }
}
