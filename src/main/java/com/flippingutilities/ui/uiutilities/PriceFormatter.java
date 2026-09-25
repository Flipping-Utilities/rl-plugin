package com.flippingutilities.ui.uiutilities;

/**
 * Formats quantities that may exceed 32 bit integers (wiki v2 prices above max cash).
 * RuneLite's QuantityFormatter only accepts ints, so this mirrors its RS decimal stack
 * style (K/M/B) with long arithmetic.
 */
public final class PriceFormatter {
    private PriceFormatter() {
    }

    public static String quantityToRSDecimalStack(long quantity) {
        return quantityToRSDecimalStack(quantity, false);
    }

    public static String quantityToRSDecimalStack(long quantity, boolean precise) {
        if (precise) {
            return String.format("%,d", quantity);
        }

        long value = Math.abs(quantity);
        String sign = quantity < 0 ? "-" : "";

        if (value < 10_000) {
            return sign + value;
        }
        if (value < 10_000_000) {
            return sign + withOneDecimal(value, 1_000L, "K");
        }
        if (value < 10_000_000_000L) {
            return sign + withOneDecimal(value, 1_000_000L, "M");
        }
        if (value < 10_000_000_000_000L) {
            return sign + withOneDecimal(value, 1_000_000_000L, "B");
        }
        return sign + withOneDecimal(value, 1_000_000_000_000L, "T");
    }

    /**
     * One decimal below ten units of the suffix, rounded above (matches the compact
     * RuneLite display: 2350K, 2.4B).
     */
    private static String withOneDecimal(long value, long unit, String suffix) {
        long whole = value / unit;
        if (whole < 10) {
            long tenths = (value % unit) * 10 / unit;
            return tenths == 0
                ? whole + suffix
                : whole + "." + tenths + suffix;
        }
        return whole + suffix;
    }
}
