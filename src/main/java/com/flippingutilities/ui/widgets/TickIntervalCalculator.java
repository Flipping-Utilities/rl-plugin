package com.flippingutilities.ui.widgets;

/**
 * calculates visually pleasing tick intervals for chart axes.
 * uses standard "nice numbers" to ensure readable tick values.
 */
public final class TickIntervalCalculator {

    /** standard nice numbers for tick intervals */
    private static final long[] NICE_NUMBERS = {1, 2, 5, 10};
    /** target number of ticks to display on chart axis */
    private static final int TARGET_TICK_COUNT = 6;

    public long calculate(long range) {
        long roughInterval = range / TARGET_TICK_COUNT;

        if (roughInterval <= 0) {
            return 1;
        }

        long magnitude = 1;
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
