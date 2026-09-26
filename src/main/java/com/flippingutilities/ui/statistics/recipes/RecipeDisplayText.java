package com.flippingutilities.ui.statistics.recipes;

/** Shared labels for unavailable recipe counts and financial values. */
public final class RecipeDisplayText {
    public static final String UNKNOWN = "Unknown";
    public static final String UNKNOWN_COUNT = UNKNOWN + " count";
    public static final String UNKNOWN_PROFIT_EACH = " (" + UNKNOWN + " gp ea)";
    public static final String MISSING_QUANTITIES = "The original recipe quantities are unavailable.";
    public static final String MISSING_QUANTITIES_PROFIT_EACH =
        "The original recipe quantities are unavailable; profit per execution is unknown.";
    public static final String MISSING_OFFERS = "Original offer details are missing.";
    public static final String MISSING_OFFERS_PROFIT = "Original offer details are missing; profit is unavailable.";
    public static final String MISSING_OFFERS_TOTALS =
        "Original offer details are missing; recipe financial totals are unavailable.";
    public static final String MISSING_RECIPE_OFFER_TOTALS =
        "Original recipe offer details are missing; financial totals are unavailable.";
    public static final String ACCOUNT_WIDE_DELETE_UNAVAILABLE =
        "You cannot delete recipe flips in the Accountwide view";

    private RecipeDisplayText() {
    }
}
