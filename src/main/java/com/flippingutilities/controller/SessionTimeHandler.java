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

package com.flippingutilities.controller;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.OfferEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;

final class SessionTimeHandler {
    private final FlippingPlugin plugin;

    SessionTimeHandler(FlippingPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Decides whether the user is currently flipping or not. To be flipping a user has to be logged in
     * and have at least one incomplete offer in the GE
     *
     * @return whether the user if currently flipping or not
     */
    private boolean currentlyFlipping() {
        if (plugin.getCurrentlyLoggedInAccount() == null) {
            return false;
        }

        Collection<OfferEvent> lastOffers = plugin.getDataHandler().viewAccountData(plugin.getCurrentlyLoggedInAccount()).getLastOffers().values();
        return lastOffers.stream().anyMatch(offerInfo -> !offerInfo.isComplete());
    }

    /**
     * Calculates and updates the session time display in the statistics tab when a user is viewing
     * the "Session" time interval.
     */
    void updateSessionTime() {
        if (!currentlyFlipping()) {
            handleNotFlipping();
            return;
        }

        updateActiveFlippingSessionTime();
    }

    private void handleNotFlipping() {
        if (plugin.getCurrentlyLoggedInAccount() == null) {
            return;
        }
        plugin.getDataHandler().getAccountData(plugin.getCurrentlyLoggedInAccount()).setLastSessionTimeUpdate(null);
    }

    private void updateActiveFlippingSessionTime() {
        AccountData account = plugin.getDataHandler().viewAccountData(plugin.getCurrentlyLoggedInAccount());
        Instant lastUpdate = account.getLastSessionTimeUpdate();

        if (lastUpdate == null) {
            lastUpdate = Instant.now();
        }

        long additionalTime = Duration.between(lastUpdate, Instant.now()).toMillis();
        long newTotalTime = account.getAccumulatedSessionTimeMillis() + additionalTime;

        plugin.getDataHandler().getAccountData(plugin.getCurrentlyLoggedInAccount()).setAccumulatedSessionTimeMillis(newTotalTime);
        plugin.getDataHandler().getAccountData(plugin.getCurrentlyLoggedInAccount()).setLastSessionTimeUpdate(Instant.now());

        // Persist session time to SQLite if active
        String accountName = plugin.getCurrentlyLoggedInAccount();
        plugin.submitStorageTask(storage -> storage.updateAccountSessionTime(accountName, newTotalTime));

        if (shouldUpdateSessionTimeDisplay()) {
            plugin.getStatPanel().updateSessionTimeDisplay(plugin.viewAccumulatedTimeForCurrentView());
        }
    }

    private boolean shouldUpdateSessionTimeDisplay() {
        return plugin.getAccountCurrentlyViewed().equals(FlippingPlugin.ACCOUNT_WIDE)
            || plugin.getAccountCurrentlyViewed().equals(plugin.getCurrentlyLoggedInAccount());
    }
}
