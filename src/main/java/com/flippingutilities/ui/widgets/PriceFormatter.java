package com.flippingutilities.ui.widgets;

public final class PriceFormatter {

    private static final long BILLION = 1_000_000_000L;
    private static final long MILLION = 1_000_000L;
    private static final long HUNDRED_THOUSAND = 100_000L;
    private static final long THOUSAND = 1_000L;
    private static final long HUNDRED = 100L;

    public String format(long price) {
        if (price < THOUSAND) {
            return String.valueOf(price);
        }

        if (price >= BILLION) {
            return formatBillion(price);
        }

        if (price >= MILLION) {
            return formatMillion(price);
        }

        return formatThousand(price);
    }

    private String formatBillion(long price) {
        if (price % BILLION == 0) {
            return String.format("%dB", price / BILLION);
        }

        return String.format("%.1fB", price / (double) BILLION);
    }

    private String formatMillion(long price) {
        if (price % MILLION == 0) {
            return String.format("%dM", price / MILLION);
        }

        if (price % HUNDRED_THOUSAND == 0) {
            return String.format("%.1fM", price / (double) MILLION);
        }

        return String.format("%.2fM", price / (double) MILLION);
    }

    private String formatThousand(long price) {
        if (price % THOUSAND == 0) {
            return String.format("%dK", price / THOUSAND);
        }

        if (price % HUNDRED == 0) {
            return String.format("%.1fK", price / (double) THOUSAND);
        }

        return String.format("%.2fK", price / (double) THOUSAND);
    }
}
