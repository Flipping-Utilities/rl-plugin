package com.flippingutilities.ui.widgets.chart;

public final class PriceRange {
    public final long min;
    public final long max;

    public PriceRange(long min, long max) {
        this.min = min;
        this.max = max;
    }

    public long getRange() {
        return max - min;
    }
}
