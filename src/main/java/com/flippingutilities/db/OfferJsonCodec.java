package com.flippingutilities.db;

import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.sql.SQLException;
import java.time.Instant;

/** Offer snapshots shared by live SQLite persistence and JSON migration. */
final class OfferJsonCodec {
    private static final Gson GSON = new GsonBuilder()
        .registerTypeAdapter(Instant.class, new TypeAdapter<Instant>() {
            @Override
            public void write(JsonWriter out, Instant value) throws java.io.IOException {
                if (value == null) {
                    out.nullValue();
                    return;
                }
                out.value(value.toEpochMilli());
            }

            @Override
            public Instant read(JsonReader in) throws java.io.IOException {
                JsonToken token = in.peek();
                if (token == JsonToken.NULL) {
                    in.nextNull();
                    return null;
                }
                if (token == JsonToken.NUMBER) {
                    return Instant.ofEpochMilli(in.nextLong());
                }
                if (token == JsonToken.STRING) {
                    String s = in.nextString();
                    if (s == null || s.trim().isEmpty()) {
                        return null;
                    }
                    return Instant.parse(s);
                }
                if (token == JsonToken.BEGIN_OBJECT) {
                    // Historical reflective encoding ({"seconds":X,"nanos":Y}) from builds
                    // whose Gson had no Instant adapter; see TradePersister.LEGACY_INSTANT.
                    long seconds = 0;
                    int nanos = 0;
                    in.beginObject();
                    while (in.hasNext()) {
                        String name = in.nextName();
                        if (name.equals("seconds") || name.equals("epochSecond")) {
                            seconds = in.nextLong();
                        } else if (name.equals("nanos") || name.equals("nano")) {
                            nanos = (int) in.nextLong();
                        } else {
                            in.skipValue();
                        }
                    }
                    in.endObject();
                    return Instant.ofEpochSecond(seconds, nanos);
                }
                in.skipValue();
                return null;
            }
        })
        .create();

    static String serializeOffer(OfferEvent offer) {
        return GSON.toJson(offer);
    }

    static String serializeRecipeOffer(PartialOffer component) throws SQLException {
        if (component.getOffer() != null && component.getOffer().getTime() == null) {
            throw new SQLException("Cannot persist recipe: missing offer timestamp " + component.getOfferUuid());
        }
        // Preserve unresolved UUID references as JSON null; an unknown price is not zero.
        return serializeOffer(component.getOffer());
    }

    static OfferEvent deserializeOffer(String json) {
        return GSON.fromJson(json, OfferEvent.class);
    }

    private OfferJsonCodec() {
    }
}
