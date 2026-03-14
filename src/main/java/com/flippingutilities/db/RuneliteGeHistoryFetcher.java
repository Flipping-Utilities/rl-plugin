package com.flippingutilities.db;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Fetches GE trade history from RuneLite's config system.
 *
 * The runelite.net website's "Export Grand Exchange" button doesn't call a separate API —
 * it reads from RuneLite's config storage. The GE trade history is stored as a config value:
 *   - Config group: "grandexchange"
 *   - Key: "tradeHistory"
 *   - Scope: per-account (rsprofile-scoped)
 *
 * Since RuneLite plugins have access to ConfigManager, we can read this directly.
 * This is what enables the one-click "Sync GE History" feature — no manual download needed.
 *
 * If the user isn't logged into runelite.net (no RuneLite account linked), this returns null
 * and the plugin falls back to the manual file import option.
 */
@Slf4j
public class RuneliteGeHistoryFetcher {

    private static final String GE_CONFIG_GROUP = "grandexchange";
    private static final String TRADE_HISTORY_KEY = "tradeHistory";

    private final ConfigManager configManager;

    public RuneliteGeHistoryFetcher(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * Attempts to fetch the GE trade history JSON from RuneLite's config.
     *
     * @return the raw JSON string of trade history, or null if not available
     *         (user not logged into runelite.net, no history synced, etc.)
     */
    public String fetchTradeHistory() {
        try {
            // Try reading from the rsprofile-scoped config (per-account data)
            String history = configManager.getRSProfileConfiguration(GE_CONFIG_GROUP, TRADE_HISTORY_KEY);

            if (history != null && !history.isEmpty()) {
                log.info("Successfully fetched GE trade history from RuneLite config ({} chars)", history.length());
                return history;
            }

            // If rsprofile config is empty, try the regular config
            // (some versions of RuneLite may store it differently)
            history = configManager.getConfiguration(GE_CONFIG_GROUP, TRADE_HISTORY_KEY);

            if (history != null && !history.isEmpty()) {
                log.info("Fetched GE trade history from RuneLite config (non-profile) ({} chars)", history.length());
                return history;
            }

            log.info("No GE trade history found in RuneLite config. " +
                "User may not be logged into runelite.net or may not have any synced history.");
            return null;

        } catch (Exception e) {
            log.warn("Failed to fetch GE trade history from RuneLite config: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Checks whether GE trade history is available without actually fetching it.
     * Useful for enabling/disabling the Sync button in the UI.
     */
    public boolean isTradeHistoryAvailable() {
        try {
            String history = configManager.getRSProfileConfiguration(GE_CONFIG_GROUP, TRADE_HISTORY_KEY);
            if (history != null && !history.isEmpty()) {
                return true;
            }
            history = configManager.getConfiguration(GE_CONFIG_GROUP, TRADE_HISTORY_KEY);
            return history != null && !history.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
