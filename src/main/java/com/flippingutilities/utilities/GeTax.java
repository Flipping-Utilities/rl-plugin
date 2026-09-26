package com.flippingutilities.utilities;

public class GeTax {
    public static long getPostTaxPrice(long price) {
        if (price >= Constants.MAX_PRICE_FOR_GE_TAX) {
            return price - Constants.GE_TAX_CAP;
        }
        long tax = (long)Math.floor(price * Constants.GE_TAX);
        return price - tax;
    }

	// Get post tax price for transactions which occurred before the rate increase to 2%
	public static long getOldPostTaxPrice(long price) {
		if (price >= Constants.OLD_MAX_PRICE_FOR_GE_TAX) {
			return price - Constants.GE_TAX_CAP;
		}
		long tax = (long)Math.floor(price * Constants.OLD_GE_TAX);
		return price - tax;
	}
}
