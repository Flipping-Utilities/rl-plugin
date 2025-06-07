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

        hydratePartialOffers(hydratedOffers);
        hydrateSlotTimers(plugin);
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
    }

    private void hydratePartialOffers(Map<String, OfferEvent> hydratedOffers) {
        List<PartialOffer> partialOffers = recipeFlipGroups.stream().flatMap(rfg -> rfg.getPartialOffers().stream()).collect(Collectors.toList());
        partialOffers.forEach(po -> {
            OfferEvent o = hydratedOffers.get(po.offer.getUuid());
            if (o == null) {
                log.warn("partial offer references deleted offer event, this should not happen!");
                return;
            }
            po.hydrateUnderlyingOfferEvent(o.getMadeBy(), o.getItemName());
        });
    }

    /**
     * When a user is an f2p world and recieves events for members items (cause they were already in the GE),
     * the item manager retrieves the item's name as "Members object". The item manager returns the correct
     * name when the user is on a member's world or logged out. As such, this method is called when the plugin starts
     * and whenever the user logs into a members world to clean up any "Members object" item names.
     */
    public void fixIncorrectItemNames(ItemManager itemManager) {
        trades.forEach(item -> {
            if (item.getItemName().equals("Members object")) {
                String actualName = itemManager.getItemComposition(item.getItemId()).getName();
                item.setItemName(actualName);
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
