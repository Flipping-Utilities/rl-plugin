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
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Edits trade history, item visibility and exports for the selected accounts. */
final class TradeHistoryHandler {
    private final FlippingPlugin plugin;

    TradeHistoryHandler(FlippingPlugin plugin) {
        this.plugin = plugin;
    }

    public void truncateTradeList() {
        if (plugin.getAccountCurrentlyViewed().equals(FlippingPlugin.ACCOUNT_WIDE)) {
            plugin.getDataHandler().getAllAccountData().forEach(accountData -> plugin.getFlippingItemHandler().deleteRemovedItems(accountData.getTrades()));
        } else {
            plugin.getFlippingItemHandler().deleteRemovedItems(plugin.getItemsForCurrentView());
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
        if (plugin.getCurrentlyLoggedInAccount() == null) {
            return;
        }
        Optional<FlippingItem> flippingItem = plugin.getDataHandler().getAccountData(plugin.getCurrentlyLoggedInAccount()).getTrades().stream().filter(item -> item.getItemId() == selectedOffer.getItemId()).findFirst();
        if (flippingItem.isPresent()) {
            flippingItem.get().updateHistory(selectedOffer);
            flippingItem.get().updateLatestProperties(selectedOffer);
            //incase it was set to false before
            flippingItem.get().setValidFlippingPanelItem(true);
        } else {
            int tradeItemId = selectedOffer.getItemId();
            FlippingItem item = new FlippingItem(tradeItemId, "", -1, plugin.getCurrentlyLoggedInAccount());
            item.setValidFlippingPanelItem(true);
            item.updateLatestProperties(selectedOffer);
            item.updateHistory(selectedOffer);
            plugin.getDataHandler().getAccountData(plugin.getCurrentlyLoggedInAccount()).getTrades().add(0, item);

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
            });
        }
        recordTrade(plugin.getCurrentlyLoggedInAccount(), selectedOffer);
    }

    /** Snapshot the offer before handing it to the ordered storage queue. */
    public void recordTrade(String account, OfferEvent offer) {
        OfferEvent snapshot = offer.clone();
        plugin.submitStorageTask(storage -> {
            // Partial quantities are cumulative and restored from the active slot instead.
            if (snapshot.isComplete() && snapshot.getCurrentQuantityInTrade() > 0) {
                storage.recordTrade(account, snapshot);
            }
            storage.upsertItemVisibility(account, snapshot.getItemId(), true);
        });
    }

    public void setItemVisible(FlippingItem item, boolean visible) {
        // Account-wide items are merged copies; update each underlying account too.
        for (String account : accountsInCurrentView()) {
            for (FlippingItem stored : plugin.getDataHandler().getAccountData(account).getTrades()) {
                if (stored.getItemId() == item.getItemId()) {
                    setItemVisible(account, stored, visible);
                }
            }
        }
        item.setValidFlippingPanelItem(visible);
        plugin.setUpdateSinceLastItemAccountWideBuild(true);
    }

    private Collection<String> accountsInCurrentView() {
        return FlippingPlugin.ACCOUNT_WIDE.equals(plugin.getAccountCurrentlyViewed())
            ? new ArrayList<>(plugin.getDataHandler().getCurrentAccounts())
            : Collections.singletonList(plugin.getAccountCurrentlyViewed());
    }

    private void setItemVisible(String account, FlippingItem item, boolean visible) {
        item.setValidFlippingPanelItem(visible);
        int itemId = item.getItemId();
        plugin.submitStorageTask(storage -> storage.upsertItemVisibility(account, itemId, visible));
    }

    public List<OfferEvent> findOfferMatches(OfferEvent offerEvent, int limit) {
        Optional<FlippingItem> flippingItem = plugin.getDataHandler().getAccountData(plugin.getCurrentlyLoggedInAccount()).getTrades().stream().filter(item -> item.getItemId() == offerEvent.getItemId()).findFirst();
        if (!flippingItem.isPresent()) {
            return new ArrayList<>();
        }
        return flippingItem.get().getOfferMatches(offerEvent, limit);
    }

    /**
     * Used by the stats panel to invalidate all offers for a certain interval when a user hits the reset button.
     */
    public void deleteOffers(Instant startOfInterval) {
        if (plugin.getAccountCurrentlyViewed().equals(FlippingPlugin.ACCOUNT_WIDE)) {
            for (AccountData accountData : plugin.getDataHandler().getAllAccountData()) {
                accountData.getTrades().forEach(item -> {
                    deleteOffers(item.getIntervalHistory(startOfInterval), item);
                });
            }
        } else {
            plugin.getItemsForCurrentView().forEach(item -> {
                deleteOffers(item.getIntervalHistory(startOfInterval), item);
            });
        }

        plugin.setUpdateSinceLastItemAccountWideBuild(true);
        plugin.setUpdateSinceLastRecipeFlipGroupAccountWideBuild(true);
        truncateTradeList();
    }

    public void deleteOffers(List<OfferEvent> offers, FlippingItem item) {
        deleteOffers(offers, plugin.viewRecipeFlipGroupsForCurrentView(), item);
    }

    private void deleteOffers(List<OfferEvent> offers, List<RecipeFlipGroup> recipeFlipGroups, FlippingItem item) {
        item.deleteOffers(offers);
        plugin.getRecipeHandler().deleteInvalidRecipeFlips(offers, recipeFlipGroups);
        plugin.markAccountTradesAsHavingChanged(plugin.getAccountCurrentlyViewed());
        plugin.setUpdateSinceLastItemAccountWideBuild(true);
        plugin.setUpdateSinceLastRecipeFlipGroupAccountWideBuild(true);

        // Mirror the deletion into SQLite off-thread. The offer uuids identify
        // the exact trade rows; recipes referencing those offers are deleted with them.
        if (plugin.getSqliteStorage() != null) {
            List<String> uuids = offers.stream()
                .map(OfferEvent::getUuid)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
            if (!uuids.isEmpty()) {
                String accountName = item.getFlippedBy() != null ? item.getFlippedBy() : plugin.getAccountCurrentlyViewed();
                plugin.submitStorageTask(storage -> storage.deleteTradesByUuid(accountName, uuids));
            }
        }
    }

    /**
     * Used by the flipping panel to hide all items (set the validfFippingItem property to false) when a user hits the
     * reset button
     */
    public void setAllFlippingItemsAsHidden() {
        for (String account : accountsInCurrentView()) {
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
