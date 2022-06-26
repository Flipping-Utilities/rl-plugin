package com.flippingutilities.utilities;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class AccountSlotsUpdate {
    public final String rsn;
    public final List<SlotState> slots;
}
