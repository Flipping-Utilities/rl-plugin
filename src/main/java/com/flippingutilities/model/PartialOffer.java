package com.flippingutilities.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PartialOffer {
    public OfferEvent offer;
    public int amountConsumed;

    public PartialOffer clone() {
        return new PartialOffer(
                offer.clone(),
                amountConsumed
        );
    }

    /**
     * Returns an offer event that represents this partial offer.
     */
    public OfferEvent toAdjustedOfferEvent() {
        int remainingAmount = offer.getCurrentQuantityInTrade() - amountConsumed;
        OfferEvent adjustedOfferEvent = offer.clone();
        adjustedOfferEvent.setCurrentQuantityInTrade(remainingAmount);
        return adjustedOfferEvent;
    }
}


