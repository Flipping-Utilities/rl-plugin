package com.flippingutilities.db;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.annotations.Expose;

import java.io.IOException;
import java.nio.file.Files;

/** Writes the historical snapshot shape for migration tests; production only reads this format. */
final class LegacyJsonFixtures {
    private LegacyJsonFixtures() {}

    static void write(TradePersister legacyReader, String accountName, Object data) throws IOException {
        Gson writer = legacyReader.getGson().newBuilder().setExclusionStrategies(new ExclusionStrategy() {
            @Override
            public boolean shouldSkipField(FieldAttributes field) {
                Expose expose = field.getAnnotation(Expose.class);
                return expose != null && !expose.serialize();
            }

            @Override
            public boolean shouldSkipClass(Class<?> type) {
                return false;
            }
        }).create();
        Files.writeString(legacyReader.getAccountDirectory().toPath().resolve(accountName + ".json"),
            writer.toJson(data));
    }
}
