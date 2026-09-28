package com.flippingutilities.controller;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.google.common.primitives.Shorts;
import net.runelite.api.VarClientStr;
import net.runelite.api.events.GrandExchangeSearched;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Manages favorite items, quick-search codes and GE search results. */
final class FavoriteHandler {
    private final FlippingPlugin plugin;

    FavoriteHandler(FlippingPlugin plugin) {
        this.plugin = plugin;
    }

    public void setFavoriteOnAllAccounts(FlippingItem item, boolean favoriteStatus) {
        for (String accountName : plugin.getDataHandler().getCurrentAccounts()) {
            AccountData account = plugin.getDataHandler().viewAccountData(accountName);
            account.
                    getTrades().
                    stream().
                    filter(accountItem -> accountItem.getItemId() == item.getItemId()).
                    findFirst().
                    ifPresent(accountItem -> {
                        accountItem.setFavorite(favoriteStatus);
                        plugin.markAccountTradesAsHavingChanged(accountName);
                        persistFavoriteOnAccount(accountName, accountItem);
                    });
        }
    }

    public void setFavoriteCodeOnAllAccounts(FlippingItem item, String favoriteCode) {
        for (String accountName : plugin.getDataHandler().getCurrentAccounts()) {
            AccountData account = plugin.getDataHandler().viewAccountData(accountName);
            account.
                    getTrades().
                    stream().
                    filter(accountItem -> accountItem.getItemId() == item.getItemId()).
                    findFirst().
                    ifPresent(accountItem -> {
                        accountItem.setFavoriteCode(favoriteCode);
                        plugin.markAccountTradesAsHavingChanged(accountName);
                        persistFavoriteOnAccount(accountName, accountItem);
                    });
        }
    }

    /** The panel updates the item directly; mark its account for persistence. */
    public void persistFavoriteOnAccount(String accountName, FlippingItem item) {
        if (accountName == null || item == null) {
            return;
        }
        plugin.getDataHandler().markDataAsHavingChanged(accountName);
    }

    /** Single-account quick-search code change; see {@link #persistFavoriteOnAccount}. */
    public void persistFavoriteCodeOnAccount(String accountName, FlippingItem item) {
        persistFavoriteOnAccount(accountName, item);
    }

    /**
     * Adds the dummy item that was favorited to the trades list. Dummy items are created for display purposes
     * when items are searched for/highlighted but they don't actually exist in history. However, if a user then
     * favorites it, we need to add it to the history.
     */
    public void addFavoritedItem(FlippingItem flippingItem) {
        for (String accountName : plugin.getAccountNamesForCurrentView()) {
            addFavoritedItem(flippingItem, accountName);
        }
    }

    private void addFavoritedItem(FlippingItem flippingItem, String accountName) {
        List<FlippingItem> items = plugin.getDataHandler().getAccountData(accountName).getTrades();
        Optional<FlippingItem> existingItem = items.stream().filter(item -> item.getItemId() == flippingItem.getItemId()).findFirst();
        if (existingItem.isPresent()) {
            existingItem.get().setFavorite(true);
        }
        else {
            flippingItem.setFlippedBy(accountName);
            items.add(0, flippingItem);
            plugin.setUpdateSinceLastItemAccountWideBuild(true);
        }
        plugin.markAccountTradesAsHavingChanged(accountName);
    }

    public void onGrandExchangeSearched(GrandExchangeSearched event) {
        final String input = plugin.getClient().getVarcStrValue(VarClientStr.INPUT_TEXT);
        Set<Integer> ids = plugin.getDataHandler().viewAccountData(plugin.getCurrentlyLoggedInAccount()).
                getTrades()
                .stream()
                .filter(item -> item.isFavorite() && input.equals(item.getFavoriteCode()))
                .map(FlippingItem::getItemId)
                .collect(Collectors.toSet());

        if (ids.isEmpty()) {
            return;
        }

        plugin.getClient().setGeSearchResultIndex(0);
        plugin.getClient().setGeSearchResultCount(ids.size());
        plugin.getClient().setGeSearchResultIds(Shorts.toArray(ids));
        event.consume();
    }
}
