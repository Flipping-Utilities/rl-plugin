package com.flippingutilities.utilities;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import net.runelite.api.GrandExchangeOfferState;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RemoteSlot {
    int index;
    SlotPredictedState predictedState;
    int timeInRange;
    int itemId;
    int offerPrice;
    boolean isBuyOffer;
    String state;
    long latestPredictedFilledTimestamp;
}