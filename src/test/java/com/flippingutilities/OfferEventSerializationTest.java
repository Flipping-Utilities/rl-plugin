/*
 * Copyright (c) 2020, Belieal <https://github.com/Belieal>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.flippingutilities;

import com.flippingutilities.model.OfferEvent;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.Assert.*;

public class OfferEventSerializationTest
{
	private final Gson gson = RuneLiteAPI.GSON;

	@Test
	public void listedPriceSurvivesRoundTrip()
	{
		OfferEvent offer = new OfferEvent(
			UUID.randomUUID().toString(),
			false,       // selling
			2283,        // item id
			0,           // currentQuantityInTrade (nothing filled)
			0,           // price (no fills yet)
			Instant.parse("2024-01-01T00:00:00Z"),
			1,           // slot
			GrandExchangeOfferState.SELLING,
			0,           // tickArrivedAt
			0,           // ticksSinceFirstOffer
			1560,        // totalQuantityInTrade
			null,        // tradeStartedAt
			false,       // beforeLogin
			null,        // madeBy (transient)
			null,        // itemName (transient)
			136,         // listedPrice — the offer price we want persisted
			0);          // spent (transient)

		String json = gson.toJson(offer);

		// Verify "lp" key is present in output
		JsonObject obj = new JsonParser().parse(json).getAsJsonObject();
		assertTrue("JSON should contain 'lp' key", obj.has("lp"));
		assertEquals(136, obj.get("lp").getAsInt());

		// Round-trip: deserialize and verify listedPrice survived
		OfferEvent restored = gson.fromJson(json, OfferEvent.class);
		assertEquals(136, restored.getListedPrice());
		assertEquals(2283, restored.getItemId());
		assertEquals(0, restored.getPreTaxPrice());
		assertEquals(1560, restored.getTotalQuantityInTrade());
		assertEquals(GrandExchangeOfferState.SELLING, restored.getState());
	}

	@Test
	public void oldJsonWithoutListedPriceDefaultsToZero()
	{
		// Simulate JSON from before this change — no "lp" field
		String oldJson = "{\"uuid\":\"abc\",\"b\":true,\"id\":4151,\"cQIT\":10,\"p\":1500,"
			+ "\"t\":{\"seconds\":1704067200,\"nanos\":0},"
			+ "\"s\":3,\"st\":\"BUYING\",\"tAA\":0,\"tSFO\":5,\"tQIT\":100}";

		OfferEvent restored = gson.fromJson(oldJson, OfferEvent.class);
		assertEquals(0, restored.getListedPrice());
		assertEquals(4151, restored.getItemId());
		assertEquals(100, restored.getTotalQuantityInTrade());
	}
}
