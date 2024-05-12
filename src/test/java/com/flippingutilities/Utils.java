package com.flippingutilities;

import com.flippingutilities.model.OfferEvent;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;

import java.time.Instant;
import java.util.UUID;

public class Utils
{
	//constructs an OfferEvent when you don't care about tick specific info.
	public static OfferEvent offer(boolean isBuy, int currentQuantityInTrade, int price, Instant time, int slot, GrandExchangeOfferState state, int totalQuantityInTrade)
	{
		return new OfferEvent(UUID.randomUUID().toString(), isBuy, 1, currentQuantityInTrade, price, time, slot, state, 0, 10, totalQuantityInTrade, null, false, "gooby", null,0,0, new GrandExchangeOfferChanged());
	}

	public static OfferEvent offer(boolean isBuy, int currentQuantityInTrade, int price, Instant time, int slot, GrandExchangeOfferState state, int totalQuantityInTrade, int tickSinceFirstOffer)
	{
		return new OfferEvent(UUID.randomUUID().toString(), isBuy, 1, currentQuantityInTrade, price, time, slot, state, 0, tickSinceFirstOffer, totalQuantityInTrade, null,false, "gooby", null,0,0, new GrandExchangeOfferChanged());
	}

	public static OfferEvent offer(boolean isBuy, int currentQuantityInTrade, int price, Instant time, int slot, GrandExchangeOfferState state, int tickArrivedAt, int tickSinceFirstOffer, int totalQuantityInTrade) {
		return new OfferEvent(UUID.randomUUID().toString(), isBuy, 1, currentQuantityInTrade, price, time, slot, state, tickArrivedAt, tickSinceFirstOffer, totalQuantityInTrade, null, false,"gooby", null,0,0, new GrandExchangeOfferChanged());
	}
}
