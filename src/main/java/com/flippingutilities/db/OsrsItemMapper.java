package com.flippingutilities.db;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import okhttp3.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Resolves item IDs to names and GE limits using two sources:
 *
 * 1. OSRS Wiki mapping API (primary) — covers ~4,000+ tradeable items with names + GE limits.
 *    Fetched once over HTTP and cached for the session.
 *
 * 2. RuneLite's ItemManager (fallback) — covers ALL items in the game since it reads directly
 *    from the game cache. Always up-to-date, but only available when the client is running.
 *
 * Why two sources? The Wiki API occasionally lags behind game updates (new items may not appear
 * for a few days). In the Runescape_FU_DataRecovery project, 15 out of ~200+ items were
 * "Unknown" because the Wiki didn't have them yet (e.g.Lead bar, Camphor plank).
 * The ItemManager fallback eliminates this problem.
 */
@Slf4j
public class OsrsItemMapper {

    private static final String MAPPING_URL = "https://prices.runescape.wiki/api/v1/osrs/mapping";

    @Getter
    private Map<Integer, String> itemNames = new HashMap<>();
    @Getter
    private Map<Integer, Integer> itemLimits = new HashMap<>();
    private boolean wikiLoaded = false;

    /**
     * Fetches the full item mapping from OSRS Wiki. Call this before importing GE history.
     * The Wiki API requires a descriptive User-Agent header (their policy for fair use).
     */
    public void fetchMapping(OkHttpClient client, Gson gson) throws IOException {
        if (wikiLoaded) {
            return; // Already cached
        }

        log.info("Fetching OSRS item mapping from Wiki API...");

        Request request = new Request.Builder()
            .url(MAPPING_URL)
            .header("User-Agent", "Flipping Utilities RuneLite Plugin - GE History Import")
            .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Failed to fetch item mapping: HTTP " + response.code());
            }

            String body = response.body().string();
            JsonArray items = gson.fromJson(body, JsonArray.class);

            for (JsonElement element : items) {
                JsonObject item = element.getAsJsonObject();

                int id = item.get("id").getAsInt();
                String name = item.has("name") ? item.get("name").getAsString() : null;

                if (name != null) {
                    itemNames.put(id, name);
                }

                if (item.has("limit") && !item.get("limit").isJsonNull()) {
                    itemLimits.put(id, item.get("limit").getAsInt());
                }
            }

            wikiLoaded = true;
            log.info("Loaded {} item names and {} GE limits from OSRS Wiki", itemNames.size(), itemLimits.size());
        }
    }

    /**
     * Fills in any gaps using RuneLite's ItemManager (reads from the game cache).
     * This catches new items that the Wiki API doesn't know about yet.
     *
     * In the Runescape_FU_DataRecovery project, items like Oathplate chest (30753),
     * Lead bar (32889), and Camphor plank (31432) were missing from the Wiki API.
     * RuneLite's ItemManager always has them because it reads the live game data.
     *
     * @param itemIds     the set of item IDs we need names for
     * @param itemManager RuneLite's item lookup service (reads from game cache)
     */
    public void fillGapsFromItemManager(Set<Integer> itemIds, ItemManager itemManager) {
        int filled = 0;
        for (int itemId : itemIds) {
            if (!itemNames.containsKey(itemId)) {
                try {
                    String name = itemManager.getItemComposition(itemId).getName();
                    if (name != null && !name.equals("null")) {
                        itemNames.put(itemId, name);
                        filled++;
                    }
                } catch (Exception e) {
                    log.debug("ItemManager couldn't resolve item {}: {}", itemId, e.getMessage());
                }
            }
            if (!itemLimits.containsKey(itemId)) {
                try {
                    ItemStats stats = itemManager.getItemStats(itemId);
                    if (stats != null && stats.getGeLimit() > 0) {
                        itemLimits.put(itemId, stats.getGeLimit());
                    }
                } catch (Exception e) {
                    // Not critical — GE limit defaults to 0
                }
            }
        }
        if (filled > 0) {
            log.info("Resolved {} additional item names via RuneLite ItemManager (Wiki gaps filled)", filled);
        }
    }

    public String getItemName(int itemId) {
        return itemNames.getOrDefault(itemId, "Unknown Item " + itemId);
    }

    public int getItemLimit(int itemId) {
        return itemLimits.getOrDefault(itemId, 0);
    }

    public boolean isWikiLoaded() {
        return wikiLoaded;
    }
}
