package com.flippingutilities.utilities;

import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Reads wiki API price/timestamp fields leniently:
 * - plain numbers (the common case),
 * - decimal numbers (the API docs state average prices may include decimal points),
 * - numbers encoded as JSON strings,
 * - null (latest prices are null for sides the wiki has never seen traded).
 *
 * Integer prices retain their full long precision. Fractional average prices truncate
 * toward zero because the plugin displays whole gp.
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
        if (token == JsonToken.STRING || token == JsonToken.NUMBER) {
            String value = in.nextString().trim();
            if (value.isEmpty()) {
                return null;
            }
            try {
                return new BigDecimal(value).setScale(0, RoundingMode.DOWN).longValueExact();
            } catch (NumberFormatException | ArithmeticException e) {
                throw new JsonSyntaxException("Unparseable price value: " + value, e);
            }
        }
        in.skipValue();
        return null;
    }
}
