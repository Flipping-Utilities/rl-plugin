package com.flippingutilities.utilities;

import com.flippingutilities.model.OfferEvent;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

import java.time.Instant;
import java.util.Date;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SlotState {
    public static final transient String DATE_FORMAT = "YYYY-MM-dd'T'HH:mm:ss.sssZ";
    private Boolean isBuyOffer;
    private Integer itemId;
    private Integer filledQty;
    private Date lastFilledTimestamp;
    private int index;
    private GrandExchangeOfferState state;
    private Integer offerQty;
    private Integer offerPrice;
    private Integer filledPrice;
    private Date createdAt;

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
                offerEvent.getTradeStartedAt() != null? Date.from(offerEvent.getTradeStartedAt()): null
        );
    }

    /**
     * GSON will exclude null fields when seralizing objects so
     */
    public static SlotState createEmptySlot(int index) {
        SlotState emptySlot = new SlotState();
        emptySlot.index = index;
        return emptySlot;
    }
}
