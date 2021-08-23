package com.flippingutilities.utilities;

import com.flippingutilities.model.OfferEvent;
import lombok.AllArgsConstructor;
import lombok.Data;
import net.runelite.api.GrandExchangeOfferState;

import java.time.Instant;

@Data
@AllArgsConstructor
public class SlotState {
    private boolean buy;
    private int itemId;
    private int currentQuantityInTrade;
    private Instant time;
    private int slot;
    private GrandExchangeOfferState state;
    private int totalQuantityInTrade;
    private int listedPrice;

    public static SlotState fromOfferEvent(OfferEvent offerEvent) {
        return new SlotState(
                offerEvent.isBuy(),
                offerEvent.getItemId(),
                offerEvent.getCurrentQuantityInTrade(),
                offerEvent.getTime(),
                offerEvent.getSlot(),
                offerEvent.getState(),
                offerEvent.getTotalQuantityInTrade(),
                offerEvent.getListedPrice()
        );
    }
}