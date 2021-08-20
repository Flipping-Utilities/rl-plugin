package com.flippingutilities.controller;

import com.flippingutilities.model.OfferEvent;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SlotStateHandler {
    DataHandler dataHandler;

    SlotStateHandler(FlippingPlugin plugin) {
        this.dataHandler  = plugin.getDataHandler();
    }

    public void update(OfferEvent newOfferEvent) {


    }
}
