package com.flippingutilities.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Used as the data structure that references OfferEvents in RecipeFlips. Its only purpose is to
 * show how much of an offer event was consumed bc when creating recipe flips you can
 * specify that you only want some of the offer to be used.
 * 
 * Stores only the offerUuid instead of the full OfferEvent to reduce JSON file size.
 * The full OfferEvent is resolved on load via hydrateOffer().
 */
@Data
@NoArgsConstructor
public class PartialOffer {
    @Expose
    private String offerUuid;
    
    @SerializedName("offer")
    @Expose(serialize = false, deserialize = true)
    private OfferEvent offer;
    
    @Expose
    public int amountConsumed;

    public PartialOffer(OfferEvent offer, int amountConsumed) {
        this.offer = offer;
        this.offerUuid = offer != null ? offer.getUuid() : null;
        this.amountConsumed = amountConsumed;
    }
    
    public PartialOffer(String offerUuid, int amountConsumed) {
        this.offerUuid = offerUuid;
        this.amountConsumed = amountConsumed;
    }

    public PartialOffer clone() {
        return new PartialOffer(
                offer != null ? offer.clone() : null,
                amountConsumed
        );
    }

    public void hydrateOffer(Map<String, OfferEvent> offersByUuid) {
        if (offerUuid != null && offer == null) {
            offer = offersByUuid.get(offerUuid);
        } else if (offer != null && offerUuid == null) {
            offerUuid = offer.getUuid();
        }
    }
    
    public void hydrateUnderlyingOfferEvent(String madeBy, String itemName) {
        if (offer != null) {
            offer.setMadeBy(madeBy);
            offer.setItemName(itemName);
        }
    }

    public OfferEvent toRemainingOfferEvent() {
        if (offer == null) {
            return null;
        }
        int remainingAmount = offer.getCurrentQuantityInTrade() - amountConsumed;
        OfferEvent adjustedOfferEvent = offer.clone();
        adjustedOfferEvent.setCurrentQuantityInTrade(remainingAmount);
        return adjustedOfferEvent;
    }
}


