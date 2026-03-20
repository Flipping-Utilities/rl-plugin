package com.flippingutilities.ui.widgets.graph.chart;

/**
 * represents a price range with minimum and maximum values.
 * used for calculating chart bounds and scaling price data for visualization.
 */
public final class PriceRange {
    public final int min;
    public final int max;

    public PriceRange(int min, int max) {
        this.min = min;
        this.max = max;
    }

    public int getRange() {
        return max - min;
    }
}
