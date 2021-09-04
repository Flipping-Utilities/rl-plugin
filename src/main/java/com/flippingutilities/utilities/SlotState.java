package com.flippingutilities.utilities;

import com.flippingutilities.model.OfferEvent;
import lombok.AllArgsConstructor;
import lombok.Data;
import net.runelite.api.GrandExchangeOfferState;

import java.time.Instant;
import java.util.Date;

@Data
@AllArgsConstructor
public class SlotState {
    private boolean isBuyOffer;
    private int itemId;
    private int filledQty;
    private Date lastFilledTimestamp;
    private int index;
    private GrandExchangeOfferState state;
    private int offerQty;
    private int offerPrice;
    private int filledPrice;
    private Date createdAt;
    private String rsn;

    public static SlotState fromOfferEvent(OfferEvent offerEvent) {
        return new SlotState(
                offerEvent.isBuy(),
                offerEvent.getItemId(),
                offerEvent.getCurrentQuantityInTrade(),
                Date.from(offerEvent.getTime()),
                offerEvent.getSlot(),
                offerEvent.getState(),
                offerEvent.getTotalQuantityInTrade(),
                offerEvent.getListedPrice(),
                offerEvent.getPrice(),
                Date.from(offerEvent.getTradeStartedAt()),
                offerEvent.getMadeBy()
        );
    }
}
//
//@Data
//class EmptySlotStatel