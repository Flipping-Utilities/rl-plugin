package com.flippingutilities.utilities;

/**
 * Computes Grand Exchange trade tax for a sale at a given point in time.
 *
 * Tax rules:
 *  - Sell-side only (buy offers pay no tax).
 *  - No tax before {@link Constants#GE_TAX_START}.
 *  - 1% ({@link Constants#OLD_GE_TAX}) before {@link Constants#GE_TAX_INCREASED},
 *    capped at {@link Constants#GE_TAX_CAP} per item, only on items priced at or above
 *    {@link Constants#OLD_MAX_PRICE_FOR_GE_TAX}.
 *  - 2% ({@link Constants#GE_TAX}) on or after {@link Constants#GE_TAX_INCREASED},
 *    capped at {@link Constants#GE_TAX_CAP} per item, only on items priced at or above
 *    {@link Constants#MAX_PRICE_FOR_GE_TAX}.
 *  - Certain items are exempt (see {@link Constants#TAX_EXEMPT_ITEMS} and
 *    {@link Constants#NEW_TAX_EXEMPT_ITEMS}).
 */
public final class TaxCalculator {

    private TaxCalculator() {}

    /**
     * Compute the total tax paid on a trade of {@code qty} items at {@code price} each.
     *
     * @param itemId       the item being traded
     * @param timestampMillis trade timestamp in epoch milliseconds
     * @param qty          number of items traded
     * @param price        pre-tax price per item
     * @return total tax in coins; 0 for buys, pre-tax-era trades, or exempt items
     */
    public static long computeTax(int itemId, long timestampMillis, int qty, int price) {
        long epochSeconds = timestampMillis / 1000L;
        if (epochSeconds < Constants.GE_TAX_START) {
            return 0L;
        }
        if (epochSeconds < Constants.GE_TAX_INCREASED) {
            if (Constants.TAX_EXEMPT_ITEMS.contains(itemId)) {
                return 0L;
            }
            return (long) taxPerItemOld(price) * qty;
        }
        if (Constants.NEW_TAX_EXEMPT_ITEMS.contains(itemId)) {
            return 0L;
        }
        return (long) taxPerItemNew(price) * qty;
    }

    private static int taxPerItemNew(int price) {
        if (price >= Constants.MAX_PRICE_FOR_GE_TAX) {
            return Constants.GE_TAX_CAP;
        }
        return (int) Math.floor(price * Constants.GE_TAX);
    }

    private static int taxPerItemOld(int price) {
        if (price >= Constants.OLD_MAX_PRICE_FOR_GE_TAX) {
            return Constants.GE_TAX_CAP;
        }
        return (int) Math.floor(price * Constants.OLD_GE_TAX);
    }
}
