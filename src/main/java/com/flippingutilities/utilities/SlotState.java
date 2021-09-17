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
    private String state;
    private Integer offerQty;
    private Integer offerPrice;
    private Integer filledPrice;
    private Date offerCreatedAt;

    public static SlotState fromOfferEvent(OfferEvent offerEvent) {
        return new SlotState(
                offerEvent.isBuy(),
                offerEvent.getItemId(),
                offerEvent.getCurrentQuantityInTrade(),
                offerEvent.isBeforeLogin()? null: Date.from(offerEvent.getTime()), //if the offer came before login, we don't know when it was actually filled
                offerEvent.getSlot(),
                convertStateEnum(offerEvent.getState()),
                offerEvent.getTotalQuantityInTrade(),
                offerEvent.getListedPrice(),
                offerEvent.getSpent(),
                offerEvent.getTradeStartedAt() != null? Date.from(offerEvent.getTradeStartedAt()): null
        );
    }

    public static String convertStateEnum(GrandExchangeOfferState grandExchangeOfferState) {
        switch (grandExchangeOfferState) {
            case SOLD:
                return "FILLED_SELL";
            case BOUGHT:
                return "FILLED_BUY";
            case BUYING:
                return "ACTIVE_BUY";
            case SELLING:
                return "ACTIVE_SELL";
            case CANCELLED_BUY:
                return "CANCELLED_BUY";
            case CANCELLED_SELL:
                return "CANCELLED_SELL";
            default:
                return "EMPTY";
        }
    }

    /**
     * GSON will exclude null fields when seralizing objects
     */
    public static SlotState createEmptySlot(int index) {
        SlotState emptySlot = new SlotState();
        emptySlot.index = index;
        return emptySlot;
    }
}


