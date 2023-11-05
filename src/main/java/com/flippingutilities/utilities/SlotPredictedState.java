package com.flippingutilities.utilities;

public enum SlotPredictedState {
    UNKNOWN,
    IN_RANGE,
    OUT_OF_RANGE,
    BETTER_THAN_WIKI;

    public static SlotPredictedState getPredictedState(boolean buy, int listedPrice, int instaSell, int instaBuy) {
        boolean isBetterThanWiki = buy ? listedPrice > Math.max(instaBuy, instaSell) : listedPrice < Math.min(instaSell, instaBuy);
        boolean isInRange = buy ? listedPrice >= Math.min(instaSell, instaBuy) : listedPrice <= Math.max(instaBuy, instaSell);

        if (isBetterThanWiki) {
            return SlotPredictedState.BETTER_THAN_WIKI;
        } else if (isInRange) {
            return SlotPredictedState.IN_RANGE;
        } else {
            return SlotPredictedState.OUT_OF_RANGE;
        }
    }
}