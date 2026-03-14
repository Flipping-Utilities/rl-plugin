package com.flippingutilities.db;

import com.flippingutilities.model.*;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOfferState;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Converts GE history data from runelite.net into the format used by Flipping Utilities.
 *
 * This is the Java equivalent of the merge_ge_with_fu.ps1 PowerShell script from the
 * Runescape_FU_DataRecovery project. It takes raw GE transactions (buy/sell records)
 * and converts them into OfferEvent objects that the plugin can display and track profits from.
 *
 * Key behaviors:
 * - Deduplication: Won't import trades you already have (checked by timestamp and trade details)
 * - Merging: Adds new trades to existing items, doesn't create duplicate items
 * - Safe defaults: Uses slot=-1 so imported trades don't interfere with live GE limit tracking
 */
@Slf4j
public class GeHistoryImporter {

    /**
     * Represents a single transaction from the runelite.net GE history export.
     * This is the raw format: just buy/sell, item ID, quantity, price, and timestamp.
     */
    @Data
    public static class GeTransaction {
        boolean buy;
        int itemId;
        int quantity;
        int price;
        long time; // epoch milliseconds
    }

    /**
     * Results of an import operation, so the user knows what happened.
     */
    @Data
    public static class ImportResult {
        int totalTransactions;
        int newOffersAdded;
        int duplicatesSkipped;
        int newItemsCreated;
        List<String> errors = new ArrayList<>();
    }

    /**
     * Parses the GE history JSON (either from a file or from RuneLite's config).
     * The format is a simple JSON array of transaction objects.
     */
    public List<GeTransaction> parseGeJson(String json, Gson gson) {
        List<GeTransaction> transactions = new ArrayList<>();
        try {
            JsonArray array = gson.fromJson(json, JsonArray.class);
            for (JsonElement element : array) {
                JsonObject obj = element.getAsJsonObject();
                GeTransaction tx = new GeTransaction();
                tx.buy = obj.get("buy").getAsBoolean();
                tx.itemId = obj.get("itemId").getAsInt();
                tx.quantity = obj.get("quantity").getAsInt();
                tx.price = obj.get("price").getAsInt();
                tx.time = obj.get("time").getAsLong();
                transactions.add(tx);
            }
        } catch (Exception e) {
            log.error("Failed to parse GE history JSON: {}", e.getMessage());
        }
        return transactions;
    }

    /**
     * Returns the set of unique item IDs in the transactions.
     * Used to pre-resolve item names before importing.
     */
    public Set<Integer> getUniqueItemIds(List<GeTransaction> transactions) {
        return transactions.stream()
            .map(tx -> tx.itemId)
            .collect(Collectors.toSet());
    }

    /**
     * Converts a single GE transaction into a Flipping Utilities OfferEvent.
     *
     * Field mapping (matching merge_ge_with_fu.ps1 logic):
     * - slot = -1: Signals "manually added" — prevents the HistoryManager from
     *   running GE limit tracking or deleting previous offers (see HistoryManager.updateHistory line 85)
     * - ticksSinceFirstOffer = 10: Prevents the offer from being classified as a margin check
     *   (OfferEvent.isMarginCheck() requires ticksSinceFirstOffer <= 2)
     * - state = BOUGHT or SOLD: These are completed trades from history
     * - tradeStartedAt = time - 1 second: Approximate, since we don't have the real start time
     */
    public OfferEvent convertToOfferEvent(GeTransaction tx) {
        GrandExchangeOfferState state = tx.buy ? GrandExchangeOfferState.BOUGHT : GrandExchangeOfferState.SOLD;
        Instant time = Instant.ofEpochMilli(tx.time);
        Instant tradeStartedAt = Instant.ofEpochMilli(tx.time - 1000);

        return new OfferEvent(
            UUID.randomUUID().toString(),  // uuid - unique ID for this offer
            tx.buy,                         // buy
            tx.itemId,                      // itemId
            tx.quantity,                    // currentQuantityInTrade
            tx.price,                       // price (per item, pre-tax)
            time,                           // time
            -1,                             // slot (-1 = manually added, skips GE limit tracking)
            state,                          // state (BOUGHT or SOLD)
            -1,                             // tickArrivedAt (unknown for imported data)
            10,                             // ticksSinceFirstOffer (>2 so NOT a margin check)
            tx.quantity,                    // totalQuantityInTrade
            tradeStartedAt,                 // tradeStartedAt
            false,                          // beforeLogin
            null,                           // madeBy (set later)
            null,                           // itemName (set later)
            0,                              // listedPrice
            0                               // spent
        );
    }

    /**
     * Checks if an offer is a duplicate of something already in the item's history.
     * Uses timestamp matching (same approach as the PowerShell script).
     * Also does a fuzzy check: same price + quantity + buy/sell within 5 seconds.
     */
    public boolean isDuplicate(OfferEvent newOffer, List<OfferEvent> existingOffers) {
        long newTime = newOffer.getTime().toEpochMilli();

        for (OfferEvent existing : existingOffers) {
            long existingTime = existing.getTime().toEpochMilli();

            // Exact timestamp match (primary dedup, same as PS1 script)
            if (existingTime == newTime) {
                return true;
            }

            // Fuzzy match: same trade details within 5 seconds
            if (Math.abs(existingTime - newTime) < 5000
                && existing.isBuy() == newOffer.isBuy()
                && existing.getCurrentQuantityInTrade() == newOffer.getCurrentQuantityInTrade()
                && existing.getPreTaxPrice() == newOffer.getPreTaxPrice()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Imports GE transactions into an account's data, handling deduplication and merging.
     *
     * This is the main entry point. It:
     * 1. Groups transactions by item ID
     * 2. For each item, converts transactions to OfferEvents
     * 3. Checks for duplicates against existing data
     * 4. Adds new offers to existing FlippingItems or creates new ones
     *
     * @param transactions the raw GE transactions to import
     * @param accountData  the account to import into
     * @param accountName  the display name of the account
     * @param itemNames    map of itemId -> item name (from OsrsItemMapper)
     * @param itemLimits   map of itemId -> GE limit (from OsrsItemMapper)
     * @return an ImportResult describing what happened
     */
    public ImportResult importIntoAccount(
            List<GeTransaction> transactions,
            AccountData accountData,
            String accountName,
            Map<Integer, String> itemNames,
            Map<Integer, Integer> itemLimits) {

        ImportResult result = new ImportResult();
        result.totalTransactions = transactions.size();

        // Group transactions by item ID (just like the PS1 script does)
        Map<Integer, List<GeTransaction>> byItem = transactions.stream()
            .collect(Collectors.groupingBy(tx -> tx.itemId));

        // Build a quick lookup of existing FlippingItems by item ID
        Map<Integer, FlippingItem> existingItems = new HashMap<>();
        for (FlippingItem item : accountData.getTrades()) {
            existingItems.put(item.getItemId(), item);
        }

        for (Map.Entry<Integer, List<GeTransaction>> entry : byItem.entrySet()) {
            int itemId = entry.getKey();
            List<GeTransaction> txList = entry.getValue();

            FlippingItem flippingItem = existingItems.get(itemId);

            // Get existing offers for deduplication
            List<OfferEvent> existingOffers = flippingItem != null
                ? flippingItem.getHistory().getCompressedOfferEvents()
                : new ArrayList<>();

            for (GeTransaction tx : txList) {
                try {
                    OfferEvent offer = convertToOfferEvent(tx);
                    offer.setMadeBy(accountName);

                    // Check for duplicates
                    if (isDuplicate(offer, existingOffers)) {
                        result.duplicatesSkipped++;
                        continue;
                    }

                    if (flippingItem == null) {
                        // Create a new FlippingItem for this item
                        String name = itemNames.getOrDefault(itemId, "Unknown Item " + itemId);
                        int geLimit = itemLimits.getOrDefault(itemId, 0);

                        flippingItem = new FlippingItem(itemId, name, geLimit, accountName);
                        flippingItem.setValidFlippingPanelItem(true);
                        accountData.getTrades().add(0, flippingItem);
                        existingItems.put(itemId, flippingItem);
                        result.newItemsCreated++;
                    }

                    // Add the offer directly to the compressed offer events list.
                    // We do NOT use updateHistory() here because we're adding to the list directly
                    // and the slot=-1 convention would skip GE limit tracking anyway.
                    offer.setItemName(flippingItem.getItemName());
                    flippingItem.getHistory().getCompressedOfferEvents().add(offer);
                    result.newOffersAdded++;

                } catch (Exception e) {
                    String error = "Error importing transaction for item " + itemId + ": " + e.getMessage();
                    result.errors.add(error);
                    log.warn(error, e);
                }
            }

            // Sort offers by time after adding new ones (keeps history chronological)
            if (flippingItem != null) {
                flippingItem.getHistory().getCompressedOfferEvents()
                    .sort(Comparator.comparing(OfferEvent::getTime));
            }
        }

        log.info("Import complete: {} total, {} new, {} duplicates skipped, {} new items, {} errors",
            result.totalTransactions, result.newOffersAdded, result.duplicatesSkipped,
            result.newItemsCreated, result.errors.size());

        return result;
    }
}
