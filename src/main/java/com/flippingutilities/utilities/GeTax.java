package com.flippingutilities.utilities;

public class GeTax {
    public static int getPostTaxPrice(int price) {
        if (price >= Constants.MAX_PRICE_FOR_GE_TAX) {
            return price - Constants.GE_TAX_CAP;
        }
        int tax = (int)Math.floor(price * Constants.GE_TAX);
        return price - tax;
    }

	// Get post tax price for transactions which occurred before the rate increase to 2%
	public static int getOldPostTaxPrice(int price) {
		if (price >= Constants.OLD_MAX_PRICE_FOR_GE_TAX) {
			return price - Constants.GE_TAX_CAP;
		}
		int tax = (int)Math.floor(price * Constants.OLD_GE_TAX);
		return price - tax;
	}
}
