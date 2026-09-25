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

package com.flippingutilities.model;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.ui.widgets.SlotActivityTimer;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
@Slf4j
@Data
public class AccountData {
    /**
     * Current version of the AccountData format.
     * Increment this when making breaking changes to the data format.
     */
    public static final int CURRENT_VERSION = 1;
    
    private Integer version;
    private Map<Integer, OfferEvent> lastOffers = new HashMap<>();
    private List<FlippingItem> trades = new ArrayList<>();
    private Instant sessionStartTime = Instant.now();
    private long accumulatedSessionTimeMillis = 0;
    private Instant lastSessionTimeUpdate;
    private List<SlotActivityTimer> slotTimers;
    private List<RecipeFlipGroup> recipeFlipGroups = new ArrayList<>();
    private Instant lastStoredAt = Instant.EPOCH;
    private Instant lastModifiedAt = Instant.now();
    /**
     * Returns true if this AccountData needs to be migrated.
     * Old files don't have a version field (null), or have an older version.
     * Only migrates if there's actual data to migrate (fails silently for empty/failed loads).
     */
    public boolean needsMigration() {
        // Don't migrate if no data - could be a failed load (e.g., file too large)
        // This prevents overwriting large files with empty data
        if (trades.isEmpty() && recipeFlipGroups.isEmpty()) {
            log.debug("No migration needed: no data (trades={}, recipeFlips={})", trades.size(), recipeFlipGroups.size());
            return false;
        }
        boolean needed = version == null || version < CURRENT_VERSION;
        log.debug("Migration check: version={}, CURRENT_VERSION={}, needed={}", version, CURRENT_VERSION, needed);
        return needed;
    }
    
    /**
     * Marks this AccountData as migrated by setting the version to current.
     * Also cleans up any redundant data.
     */
    public void markMigrated() {
        version = CURRENT_VERSION;
        cleanup();
    }
    
    /**
     * Cleans up redundant data to reduce file size.
     * Removes PartialOffers with amountConsumed == 0.
     */
    public void cleanup() {
        recipeFlipGroups.forEach(RecipeFlipGroup::cleanup);
    }

    /**
     * Resets all session related data associated with an account. This is called when the plugin first starts
     * as that's when a new session is "started" and when a user wants to start a new session for an account.
     */
    public void startNewSession() {
        sessionStartTime = Instant.now();
        accumulatedSessionTimeMillis = 0;
        lastSessionTimeUpdate = null;
    }

    /**
     * Over time as we delete/add fields, we need to make sure the fields are set properly the first time the user
     * loads their trades after the new update. This method serves as a way to sanitize the data. It also ensures
     * that the FlippingItems have their non persisted fields set from history.
     */
    public void prepareForUse(FlippingPlugin plugin) {
        fixIncorrectItemNames(plugin.getItemManager());

        Map<String, OfferEvent> hydratedOffers = new HashMap<>();

        for (FlippingItem item : trades) {
            //in case ge limits have been updated
            int tradeItemId = item.getItemId();
            ItemStats itemStats = plugin.getItemManager().getItemStats(tradeItemId);
            int geLimit = itemStats != null ? itemStats.getGeLimit() : 0;

            item.hydrate(geLimit);
            item.getHistory().getCompressedOfferEvents().forEach(o -> hydratedOffers.put(o.getUuid(), o));
        }

        hydrateRecipeFlipGroups(plugin);
        hydratePartialOffers(hydratedOffers, plugin.getItemManager());
        hydrateSlotTimers(plugin);
    }

    private void hydrateRecipeFlipGroups(FlippingPlugin plugin) {
        for (RecipeFlipGroup group : recipeFlipGroups) {
            group.hydrateRecipe(plugin.getRecipeHandler());
            if (group.getRecipe() == null) {
                group.synthesizeRecipe(plugin.getItemManager());
            }
        }
    }

    private void hydrateSlotTimers(FlippingPlugin plugin) {
        if (slotTimers == null) {
            slotTimers = setupSlotTimers(plugin);
        } else {
            slotTimers.forEach(timer -> {
                timer.setClient(plugin.getClient());
                timer.setPlugin(plugin);
            });
        }

        // Restore timer state from the persisted last offers. The JSON backend serializes the
        // timers themselves, but the SQLite backend only persists the offers — without this
        // wiring the GE slot timers are blank after every restart in SQLite mode. Timers that
        // already carry an offer (JSON path) are left untouched.
        if (lastOffers != null) {
            for (Map.Entry<Integer, OfferEvent> entry : lastOffers.entrySet()) {
                OfferEvent offer = entry.getValue();
                if (offer == null || offer.isComplete() || offer.isCausedByEmptySlot()) {
                    continue;
                }
                int slotIndex = entry.getKey();
                if (slotIndex < 0 || slotIndex >= slotTimers.size()) {
                    continue;
                }
                SlotActivityTimer timer = slotTimers.get(slotIndex);
                if (timer.currentOffer == null) {
                    timer.currentOffer = offer;
                    timer.tradeStartTime = offer.getTradeStartedAt() != null
                        ? offer.getTradeStartedAt()
                        : offer.getTime();
                    // lastUpdate must be non-null or createFormattedTimeString() returns null
                    // (blank timer) and isSlotStagnant() NPEs. The offer's time is the last
                    // known activity, so the elapsed display continues across the restart.
                    timer.setLastUpdate(offer.getTime() != null ? offer.getTime() : Instant.now());
                    timer.offerOccurredAtUnknownTime = offer.isBeforeLogin();
                }
            }
        }
    }

    private void hydratePartialOffers(Map<String, OfferEvent> hydratedOffers, ItemManager itemManager) {
        for (RecipeFlipGroup rfg : recipeFlipGroups) {
            for (RecipeFlip flip : rfg.getRecipeFlips()) {
                hydrateComponentOffers(flip.getInputs(), hydratedOffers, itemManager);
                hydrateComponentOffers(flip.getOutputs(), hydratedOffers, itemManager);
            }
        }
    }

    private void hydrateComponentOffers(Map<Integer, Map<String, PartialOffer>> components,
                                        Map<String, OfferEvent> hydratedOffers, ItemManager itemManager) {
        if (components == null) {
            return;
        }
        components.forEach((itemId, offerMap) -> {
            if (offerMap == null) {
                return;
            }
            offerMap.values().forEach(po -> {
                po.hydrateOffer(hydratedOffers);
                if (po.getOffer() == null) {
                    // Preserve an unresolved reference so persistence can detect missing data.
                    // A fabricated zero-price offer would be saved as if it were the real trade.
                    log.warn("Recipe references missing offer uuid={} for item {}",
                        po.getOfferUuid(), itemId);
                    return;
                }
                OfferEvent o = hydratedOffers.get(po.getOfferUuid());
                if (o != null) {
                    po.hydrateUnderlyingOfferEvent(o.getMadeBy(), o.getItemName());
                } else {
                    po.getOffer().setItemName(resolveItemName(itemManager, itemId));
                }
            });
        });
    }

    private String resolveItemName(ItemManager itemManager, int itemId) {
        try {
            if (itemManager != null && itemManager.getItemComposition(itemId) != null) {
                String name = itemManager.getItemComposition(itemId).getName();
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Exception ignored) {
        }
        return "Item " + itemId;
    }

    /**
     * When a user is an f2p world and recieves events for members items (cause they were already in the GE),
     * the item manager retrieves the item's name as "Members object". The item manager returns the correct
     * name when the user is on a member's world or logged out. As such, this method is called when the plugin starts
     * and whenever the user logs into a members world to clean up any "Members object" item names.
     * Also fixes placeholder names like "Item 12345" from SQLite migration.
     */
    public void fixIncorrectItemNames(ItemManager itemManager) {
        trades.forEach(item -> {
            String currentName = item.getItemName();
            // Match "Members object" (f2p world artifact) or SQLite placeholder "Item <id>"
            if (currentName.equals("Members object") || currentName.matches("Item \\d+")) {
                try {
                    String actualName = itemManager.getItemComposition(item.getItemId()).getName();
                    item.setItemName(actualName);
                } catch (RuntimeException e) {
                    // Item composition not loaded yet; keep current name and retry on next login
                }
            }
        });
    }

    private List<SlotActivityTimer> setupSlotTimers(FlippingPlugin plugin) {
        ArrayList<SlotActivityTimer> slotTimers = new ArrayList<>();
        for (int slotIndex = 0; slotIndex < 8; slotIndex++) {
            slotTimers.add(new SlotActivityTimer(plugin, plugin.getClient(), slotIndex));
        }
        return slotTimers;
    }
}
