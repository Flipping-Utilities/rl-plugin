package com.flippingutilities.utilities;
import lombok.Data;

import java.util.List;

@Data
public class SlotsUpdate {
    public final String rsn;
    public final List<SlotState> slots;

    public SlotsUpdate(String rsn, List<SlotState> slots) {
        this.rsn = rsn;
        this.slots = slots;
    }
}
