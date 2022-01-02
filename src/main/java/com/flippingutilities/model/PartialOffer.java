package com.flippingutilities.model;

import com.flippingutilities.model.OfferEvent;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
public class PartialOffer {
    public OfferEvent offer;
    public int amountConsumed;

    public PartialOffer(OfferEvent offer, int amountConsumed) {
//        OfferEvent clonedOffer = offer.clone();
//        clonedOffer.se
    }
}


