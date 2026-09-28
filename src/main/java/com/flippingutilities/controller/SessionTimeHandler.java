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
        String displayName = plugin.getCurrentlyLoggedInAccount();
        if (displayName == null) {
            return;
        }
        AccountData account = plugin.getDataHandler().viewAccountData(displayName);
        if (account.getLastSessionTimeUpdate() != null) {
            account.setLastSessionTimeUpdate(null);
            plugin.getDataHandler().markSessionTimeChanged(displayName);
        }
    }

    private void updateActiveFlippingSessionTime() {
        String displayName = plugin.getCurrentlyLoggedInAccount();
        AccountData account = plugin.getDataHandler().viewAccountData(displayName);
        Instant now = Instant.now();
        Instant lastUpdate = account.getLastSessionTimeUpdate();
        long additionalTime = lastUpdate == null ? 0 : Duration.between(lastUpdate, now).toMillis();
        long newTotalTime = account.getAccumulatedSessionTimeMillis() + additionalTime;
        if (newTotalTime != account.getAccumulatedSessionTimeMillis() || !now.equals(lastUpdate)) {
            account.setAccumulatedSessionTimeMillis(newTotalTime);
            account.setLastSessionTimeUpdate(now);
            plugin.getDataHandler().markSessionTimeChanged(displayName);
        }

        if (shouldUpdateSessionTimeDisplay()) {
            plugin.getStatPanel().updateSessionTimeDisplay(plugin.viewAccumulatedTimeForCurrentView());
        }
    }

    private boolean shouldUpdateSessionTimeDisplay() {
        return plugin.isAccountInCurrentView(plugin.getCurrentlyLoggedInAccount());
    }
}
