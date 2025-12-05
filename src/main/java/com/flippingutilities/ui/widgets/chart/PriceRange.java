package com.flippingutilities.ui.widgets.chart;

/**
 * represents a price range with minimum and maximum values.
 * used for calculating chart bounds and scaling price data for visualization.
 */
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
