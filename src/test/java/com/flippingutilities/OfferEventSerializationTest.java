package com.flippingutilities;

import com.flippingutilities.model.OfferEvent;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OfferEventSerializationTest
{
	private final Gson gson = new Gson();

	/**
	 * Parsed structurally rather than matched as a substring. `json.contains("\"p\":91")`
	 * asserts nothing about the document's shape: it is sensitive to formatting, it cannot
	 * tell a top-level key from one nested somewhere else, and it can be satisfied by a
	 * longer key that happens to end in the one being looked for. Reading the field off a
	 * parsed object tests what the assertion is actually meant to claim.
	 */
	private JsonObject serialize(OfferEvent offer)
	{
		return new JsonParser().parse(gson.toJson(offer)).getAsJsonObject();
	}

	/**
	 * The core guarantee: the two prices survive a round trip as separate fields. `p` remains
	 * the average execution price and `lp` carries the exact price the offer was listed at, so
	 * a live offer that has filled nothing still persists a usable price.
	 */
	@Test
	public void listedPriceIsPersistedSeparatelyFromExecutionPrice()
	{
		OfferEvent offer = new OfferEvent();
		offer.setPrice(91);
		offer.setListedPrice(94);

		JsonObject json = serialize(offer);
		assertTrue(json.has("p"));
		assertTrue(json.has("lp"));
		assertEquals(91, json.get("p").getAsInt());
		assertEquals(94, json.get("lp").getAsInt());

		OfferEvent restored = gson.fromJson(json, OfferEvent.class);
		assertEquals(91, restored.getPreTaxPrice());
		assertEquals(94, restored.getListedPrice());
	}

	/**
	 * The equal-price case is the one a substring assertion cannot distinguish at all:
	 * with both fields at 91 there is only one distinct value in the document, so a
	 * `contains` check passes even if only one of the two keys was written.
	 */
	@Test
	public void listedPriceIsWrittenEvenWhenItEqualsExecutionPrice()
	{
		OfferEvent offer = new OfferEvent();
		offer.setPrice(91);
		offer.setListedPrice(91);

		JsonObject json = serialize(offer);
		assertEquals(91, json.get("p").getAsInt());
		assertEquals(91, json.get("lp").getAsInt());

		OfferEvent restored = gson.fromJson(json, OfferEvent.class);
		assertEquals(91, restored.getPreTaxPrice());
		assertEquals(91, restored.getListedPrice());
	}

	/**
	 * Backward compatibility: account files written before `lp` existed simply omit the key,
	 * and must keep deserializing. The field reads 0, which is the sentinel for "no listed
	 * price recorded" — the same value an offer carried under the old transient behaviour.
	 */
	@Test
	public void oldAccountFilesDefaultListedPriceToUnknown()
	{
		JsonObject legacy = new JsonParser().parse("{\"p\":91}").getAsJsonObject();
		assertFalse(legacy.has("lp"));

		OfferEvent restored = gson.fromJson(legacy, OfferEvent.class);
		assertEquals(91, restored.getPreTaxPrice());
		assertEquals(0, restored.getListedPrice());
	}
}
