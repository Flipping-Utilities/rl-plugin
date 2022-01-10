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
}


