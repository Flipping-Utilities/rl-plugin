package com.flippingutilities.controller;

import com.flippingutilities.db.TradePersister;
import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.RecipeFlipGroup;
import net.runelite.client.game.ItemStats;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Edits trade history, item visibility and exports for the selected accounts. */
final class TradeHistoryHandler {
    private final FlippingPlugin plugin;

    TradeHistoryHandler(FlippingPlugin plugin) {
        this.plugin = plugin;
    }

    public void truncateTradeList() {
        for (String name : plugin.getAccountNamesForCurrentView()) {
            AccountData account = plugin.getDataHandler().getAccountData(name);
            int previousSize = account.getTrades().size();
            plugin.getFlippingItemHandler().deleteRemovedItems(account.getTrades());
            if (account.getTrades().size() != previousSize) plugin.getDataHandler().markDataAsHavingChanged(name);
        }
    }

    public void addSelectedGeTabOffers(List<OfferEvent> selectedOffers) {
        for (OfferEvent offerEvent : selectedOffers) {
            addSelectedGeTabOffer(offerEvent);
        }

        //have to add a delay before rebuilding as item limit and name may not have been set yet in addSelectedGeTabOffer due to
        //clientThread being async and not offering a future to wait on when you submit a runnable...
        plugin.getExecutor().schedule(() -> {
            plugin.getFlippingPanel().rebuild(plugin.viewItemsForCurrentView());
            plugin.getStatPanel().rebuildItemsDisplay(plugin.viewItemsForCurrentView());
        }, 500, TimeUnit.MILLISECONDS);
    }

    private void addSelectedGeTabOffer(OfferEvent selectedOffer) {
        String account = plugin.getCurrentlyLoggedInAccount();
        if (account == null) {
            return;
        }
        Optional<FlippingItem> flippingItem = plugin.getDataHandler().getAccountData(account).getTrades().stream().filter(item -> item.getItemId() == selectedOffer.getItemId()).findFirst();
        if (flippingItem.isPresent()) {
            flippingItem.get().updateHistory(selectedOffer);
            flippingItem.get().updateLatestProperties(selectedOffer);
            //incase it was set to false before
            flippingItem.get().setValidFlippingPanelItem(true);
        } else {
            int tradeItemId = selectedOffer.getItemId();
            FlippingItem item = new FlippingItem(tradeItemId, "", -1, account);
            item.setValidFlippingPanelItem(true);
            item.updateLatestProperties(selectedOffer);
            item.updateHistory(selectedOffer);
            plugin.getDataHandler().getAccountData(account).getTrades().add(0, item);

            //itemmanager can only be used on the client thread.
            //i can't put everything in the runnable given to the client thread cause then it executes async and if there
            //are multiple offers for the same flipping item that doesn't yet exist in trades list, it might create multiple
            //of them.
            plugin.getClientThread().invokeLater(() -> {
                String itemName = plugin.getItemManager().getItemComposition(tradeItemId).getName();
                ItemStats itemStats = plugin.getItemManager().getItemStats(tradeItemId);
                int geLimit = itemStats != null ? itemStats.getGeLimit() : 0;
                item.setItemName(itemName);
                item.setTotalGELimit(geLimit);
                plugin.getDataHandler().markDataAsHavingChanged(account);
            });
        }
        recordTrade(account, selectedOffer);
    }

    /** Mark the account after its in-memory history has changed. */
    public void recordTrade(String account, OfferEvent offer) {
        if (account != null && offer != null) {
            plugin.getDataHandler().markDataAsHavingChanged(account);
        }
    }

    public void setItemVisible(FlippingItem item, boolean visible) {
        // Account-wide items are merged copies; update each underlying account too.
        for (String account : plugin.getAccountNamesForCurrentView()) {
            for (FlippingItem stored : plugin.getDataHandler().getAccountData(account).getTrades()) {
                if (stored.getItemId() == item.getItemId()) {
                    setItemVisible(account, stored, visible);
                }
            }
        }
        item.setValidFlippingPanelItem(visible);
        plugin.setUpdateSinceLastItemAccountWideBuild(true);
    }

    private void setItemVisible(String account, FlippingItem item, boolean visible) {
        item.setValidFlippingPanelItem(visible);
        plugin.getDataHandler().markDataAsHavingChanged(account);
    }

    public List<OfferEvent> findOfferMatches(OfferEvent offerEvent, int limit) {
        Optional<FlippingItem> flippingItem = plugin.getDataHandler().viewAccountData(plugin.getCurrentlyLoggedInAccount()).getTrades().stream().filter(item -> item.getItemId() == offerEvent.getItemId()).findFirst();
        if (!flippingItem.isPresent()) {
            return new ArrayList<>();
        }
        return flippingItem.get().getOfferMatches(offerEvent, limit);
    }

    /**
     * Used by the stats panel to invalidate all offers for a certain interval when a user hits the reset button.
     */
    public void deleteOffers(Instant startOfInterval) {
        for (String name : plugin.getAccountNamesForCurrentView()) {
            AccountData account = plugin.getDataHandler().getAccountData(name);
            account.getTrades().forEach(item ->
                deleteOffers(name, item.getIntervalHistory(startOfInterval), account.getRecipeFlipGroups(), item));
        }

        plugin.setUpdateSinceLastItemAccountWideBuild(true);
        plugin.setUpdateSinceLastRecipeFlipGroupAccountWideBuild(true);
        truncateTradeList();
    }

    public void deleteOffers(List<OfferEvent> offers, FlippingItem item) {
        deleteOffers(plugin.getAccountCurrentlyViewed(), offers, plugin.viewRecipeFlipGroupsForCurrentView(), item);
    }

    private void deleteOffers(String account, List<OfferEvent> offers, List<RecipeFlipGroup> recipeFlipGroups, FlippingItem item) {
        item.deleteOffers(offers);
        plugin.getRecipeHandler().deleteInvalidRecipeFlips(offers, recipeFlipGroups);
        plugin.markAccountTradesAsHavingChanged(account);
        plugin.setUpdateSinceLastItemAccountWideBuild(true);
        plugin.setUpdateSinceLastRecipeFlipGroupAccountWideBuild(true);
    }

    /**
     * Used by the flipping panel to hide all items (set the validfFippingItem property to false) when a user hits the
     * reset button
     */
    public void setAllFlippingItemsAsHidden() {
        for (String account : plugin.getAccountNamesForCurrentView()) {
            plugin.getDataHandler().getAccountData(account).getTrades().forEach(item -> setItemVisible(account, item, false));
        }
        plugin.setUpdateSinceLastItemAccountWideBuild(true);
        truncateTradeList();
    }

    public void exportToCsv(File parentDirectory, Instant startOfInterval, String startOfIntervalName) throws IOException {
        if (parentDirectory.equals(TradePersister.PARENT_DIRECTORY)) {
            throw new RuntimeException("Cannot save csv file in the flipping directory, pick another directory");
        }
        //create new flipping item list with only history from that interval
        List<FlippingItem> items = new ArrayList<>();
        for (FlippingItem item : plugin.viewItemsForCurrentView()) {
            List<OfferEvent> offersInInterval = item.getIntervalHistory(startOfInterval);
            if (offersInInterval.isEmpty()) {
                continue;
            }
            FlippingItem itemWithOnlySelectedIntervalHistory = new FlippingItem(item.getItemId(), item.getItemName(), item.getTotalGELimit(), item.getFlippedBy());
            itemWithOnlySelectedIntervalHistory.getHistory().setCompressedOfferEvents(offersInInterval);
            items.add(itemWithOnlySelectedIntervalHistory);
        }

        TradePersister.exportToCsv(new File(parentDirectory, plugin.getAccountCurrentlyViewed() + ".csv"), items, startOfIntervalName);
    }
}
