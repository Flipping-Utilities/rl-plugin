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

    /**
     * Single-account favorite toggle: unlike the account-wide variant, the panel updates the
     * FlippingItem itself, so this only persists the change to the active backend. Without the
     * upsert, SQLite mode restarts revert the toggle (JSON stays authoritative in memory only).
     */
    public void persistFavoriteOnAccount(String accountName, FlippingItem item) {
        if (accountName == null || item == null) {
            return;
        }
        int itemId = item.getItemId();
        boolean favorite = item.isFavorite();
        String code = item.getFavoriteCode();
        plugin.submitStorageTask(storage -> storage.upsertFavorite(accountName, itemId, favorite, code));
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
        if (plugin.getAccountCurrentlyViewed().equals(FlippingPlugin.ACCOUNT_WIDE)) {
            for (String accountName : plugin.getDataHandler().getCurrentAccounts()) {
                addFavoritedItem(flippingItem, accountName);
            }
        }
        else {
            addFavoritedItem(flippingItem, plugin.getAccountCurrentlyViewed());
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
            plugin.markAccountTradesAsHavingChanged(accountName);
            plugin.setUpdateSinceLastItemAccountWideBuild(true);
        }
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
