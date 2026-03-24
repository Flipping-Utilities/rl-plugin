package com.flippingutilities.ui.widgets.graph;

/**
 * calculates visually pleasing tick intervals for chart axes.
 * uses standard "nice numbers" to ensure readable tick values.
 */
public final class TickIntervalCalculator {

    /** target number of ticks to display on chart axis */
    private static final int TARGET_TICK_COUNT = 6;

    public int calculate(int range) {
        int roughInterval = range / TARGET_TICK_COUNT;

        if (roughInterval <= 0) {
            return 1;
        }

        int magnitude = 1;
        while (magnitude * 10 <= roughInterval) {
            magnitude *= 10;
        }

        if (roughInterval <= magnitude) {
            return magnitude;
        } else if (roughInterval <= 2 * magnitude) {
            return 2 * magnitude;
        } else if (roughInterval <= 2.5 * magnitude) {
            return (25 * magnitude) / 10;
        } else if (roughInterval <= 5 * magnitude) {
            return 5 * magnitude;
        } else {
            return 10 * magnitude;
        }
    }
}
