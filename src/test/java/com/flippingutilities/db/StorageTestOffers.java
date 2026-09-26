package com.flippingutilities.db;

import com.flippingutilities.model.OfferEvent;
import net.runelite.api.GrandExchangeOfferState;

import java.time.Instant;

final class StorageTestOffers {
    private StorageTestOffers() {}

    static OfferEvent complete(String account, int itemId, String uuid, long time,
                               int quantity, int price, boolean buy) {
        return new OfferEvent(uuid, buy, itemId, quantity, price, Instant.ofEpochMilli(time),
            0, buy ? GrandExchangeOfferState.BOUGHT : GrandExchangeOfferState.SOLD,
            0, 10, quantity, null, false, account, "Item " + itemId, price, price * quantity);
    }
}
