package com.flippingutilities.utilities;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class RemoteAccountSlots {
    String rsn;
    List<RemoteSlot> slots;
}
