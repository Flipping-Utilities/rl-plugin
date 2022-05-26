package com.flippingutilities.utilities;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RemoteSlot {
    int index;
    SlotPredictedState predictedState;
    int timeInRange;
}