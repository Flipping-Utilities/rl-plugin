package com.flippingutilities.db;

import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import org.junit.Test;

import java.sql.SQLException;
import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class OfferJsonCodecTest {
    private static final Instant TIME = Instant.ofEpochMilli(1_600_000_000_123L);

    @Test
    public void inheritsClientSettingsAndOverridesOnlyInstantAdapter() {
        Gson clientGson = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.UPPER_CAMEL_CASE)
            .serializeNulls()
            .registerTypeAdapter(Instant.class, (JsonSerializer<Instant>)
                (value, type, context) -> new JsonPrimitive("client-instant"))
            .create();
        OfferJsonCodec codec = new OfferJsonCodec(clientGson);
        OfferEvent offer = new OfferEvent();
        offer.setUuid("snapshot");
        offer.setTime(TIME);

        String encoded = codec.serializeOffer(offer);
        JsonObject json = clientGson.fromJson(encoded, JsonObject.class);
        assertEquals("snapshot", json.get("Uuid").getAsString());
        assertTrue(json.get("TradeStartedAt").isJsonNull());
        assertTrue(json.getAsJsonPrimitive("t").isNumber());
        assertEquals(TIME.toEpochMilli(), json.get("t").getAsLong());
        OfferEvent restored = codec.deserializeOffer(encoded);
        assertEquals("snapshot", restored.getUuid());
        assertEquals(TIME, restored.getTime());
        assertEquals("\"client-instant\"", clientGson.toJson(TIME));
    }

    @Test
    public void readsCurrentAndHistoricalInstantFormats() {
        OfferJsonCodec codec = new OfferJsonCodec(new Gson());
        String[] timestamps = {
            "1600000000123",
            "\"2020-09-13T12:26:40.123Z\"",
            "{\"seconds\":1600000000,\"nanos\":123000000}",
            "{\"epochSecond\":1600000000,\"nano\":123000000}"
        };
        for (String timestamp : timestamps) {
            OfferEvent offer = codec.deserializeOffer(
                "{\"t\":" + timestamp + ",\"tradeStartedAt\":" + timestamp + "}");
            assertEquals(timestamp, TIME, offer.getTime());
            assertEquals(timestamp, TIME, offer.getTradeStartedAt());
        }
    }

    @Test
    public void preservesNullOffersAndMissingTimestamps() {
        OfferJsonCodec codec = new OfferJsonCodec(new Gson());
        assertEquals("null", codec.serializeOffer(null));
        assertNull(codec.deserializeOffer("null"));
        assertNull(codec.deserializeOffer("{\"t\":null}").getTime());
        assertNull(codec.deserializeOffer("{\"t\":\" \"}").getTime());
    }

    @Test
    public void preservesUnresolvedRecipeReferencesWithoutInventingOffers() throws SQLException {
        OfferJsonCodec codec = new OfferJsonCodec(new Gson());
        PartialOffer component = new PartialOffer("missing-offer", 2);

        assertEquals("null", codec.serializeRecipeOffer(component));
        assertEquals("missing-offer", component.getOfferUuid());
        assertNull(component.getOffer());
    }

    @Test
    public void rejectsRecipeSnapshotsWithoutTimestamps() {
        OfferJsonCodec codec = new OfferJsonCodec(new Gson());
        OfferEvent offer = new OfferEvent();
        offer.setUuid("missing-time");

        try {
            codec.serializeRecipeOffer(new PartialOffer(offer, 2));
            fail("An existing offer without a timestamp must reject the recipe write");
        } catch (SQLException expected) {
            assertTrue(expected.getMessage().contains("missing-time"));
        }
    }
}
