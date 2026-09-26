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
