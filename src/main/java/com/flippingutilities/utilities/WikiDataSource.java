package com.flippingutilities.utilities;

import net.runelite.api.WorldType;

import java.util.Collection;

/**
 * The separate markets the wiki tracks prices for. Each market has its own price API base, as
 * items trade at different prices on Deadman and Hardcore worlds than in the main game.
 */
public enum WikiDataSource {
    // Both markets use v2. The Deadman endpoint may return 404 outside active events.
    DMM("https://prices.runescape.wiki/api/v2/dmm"),
    HARDCORE("https://prices.runescape.wiki/api/v2/osrs-hc"),
    REGULAR("https://prices.runescape.wiki/api/v2/osrs");

    private final String apiBase;

    WikiDataSource(String apiBase) {
        this.apiBase = apiBase;
    }

    public String getLatestUrl() {
        return apiBase + "/latest";
    }

    public String getTimeseriesUrl() {
        return apiBase + "/timeseries";
    }

    /** The market whose prices apply to a world of the given type. */
    public static WikiDataSource fromWorldType(Collection<WorldType> worldType) {
        if (worldType.contains(WorldType.DEADMAN)) {
            return DMM;
        }
        if (isHardcoreWorld(worldType)) {
            return HARDCORE;
        }
        return REGULAR;
    }

    public static boolean isHardcoreWorld(Collection<WorldType> worldType) {
        return worldType.contains(WorldType.HARDCORE_MAIN);
    }
}
