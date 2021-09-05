package com.flippingutilities.utilities;
import java.util.List;

public class SlotsRequest {
    public final String rsn;
    public final List<SlotState> slots;

    public SlotsRequest(String rsn, List<SlotState> slots) {
        this.rsn = rsn;
        this.slots = slots;
    }
}
