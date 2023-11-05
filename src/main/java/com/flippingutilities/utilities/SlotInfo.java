package com.flippingutilities.utilities;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SlotInfo {
    int index;
    SlotPredictedState predictedState;
    int itemId;
    int offerPrice;
    boolean isBuyOffer;
}