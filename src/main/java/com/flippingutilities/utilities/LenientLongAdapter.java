package com.flippingutilities.utilities;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

/**
 * Reads wiki API price/timestamp fields leniently:
 * - plain numbers (the common case),
 * - decimal numbers (the API docs state average prices may include decimal points),
 * - numbers encoded as JSON strings,
 * - null (latest prices are null for sides the wiki has never seen traded).
 *
 * Wiki v2 prices may exceed what a 32 bit integer can hold (over max cash), so the model
 * fields are Long and no information is lost on parse; consumers that need an int use the
 * saturating capped accessors.
 */
public class LenientLongAdapter extends TypeAdapter<Long> {
    @Override
    public void write(JsonWriter out, Long value) throws IOException {
        if (value == null) {
            out.nullValue();
        } else {
            out.value(value);
        }
    }

    @Override
    public Long read(JsonReader in) throws IOException {
        JsonToken token = in.peek();
        if (token == JsonToken.NULL) {
            in.nextNull();
            return null;
        }
        if (token == JsonToken.STRING) {
            String s = in.nextString();
            if (s == null || s.trim().isEmpty()) {
                return null;
            }
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                try {
                    return (long) Double.parseDouble(s.trim());
                } catch (NumberFormatException e) {
                    throw new com.google.gson.JsonSyntaxException("Unparseable price value: " + s, e);
                }
            }
        }
        if (token == JsonToken.NUMBER) {
            try {
                return in.nextLong();
            } catch (NumberFormatException decimal) {
                // Decimal average price: truncate toward zero (sub-gp precision is noise).
                return (long) in.nextDouble();
            }
        }
        in.skipValue();
        return null;
    }
}
