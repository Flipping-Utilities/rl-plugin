package com.flippingutilities.utilities;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class RemoteSlot {
    Integer index;
    SlotPredictedState predictedState;
    Integer timeInRange;
}


