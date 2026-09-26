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
import com.flippingutilities.model.RecipeFlipGroup;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Selects account data for the sidebar and caches the merged account-wide views. */
final class AccountViewHandler {
    private final FlippingPlugin plugin;
    private final Instant startUpTime = Instant.now();
    private boolean updateSinceLastItemAccountWideBuild = true;
    private boolean updateSinceLastRecipeFlipGroupAccountWideBuild = true;
    private List<FlippingItem> prevBuiltAccountWideItemList;
    private List<RecipeFlipGroup> prevBuildAccountWideRecipeFlipGroup;

    AccountViewHandler(FlippingPlugin plugin) {
        this.plugin = plugin;
    }

    void setItemsChanged(boolean changed) {
        updateSinceLastItemAccountWideBuild = changed;
    }

    void setRecipesChanged(boolean changed) {
        updateSinceLastRecipeFlipGroupAccountWideBuild = changed;
    }

    public List<FlippingItem> getItemsForCurrentView() {
        return plugin.getAccountCurrentlyViewed().equals(FlippingPlugin.ACCOUNT_WIDE) ? createAccountWideFlippingItemList() : plugin.getDataHandler().getAccountData(plugin.getAccountCurrentlyViewed()).getTrades();
    }

    public List<FlippingItem> viewItemsForCurrentView() {
        return plugin.getAccountCurrentlyViewed().equals(FlippingPlugin.ACCOUNT_WIDE) ? createAccountWideFlippingItemList() : plugin.getDataHandler().viewAccountData(plugin.getAccountCurrentlyViewed()).getTrades();
    }

    public List<RecipeFlipGroup> viewRecipeFlipGroupsForCurrentView() {
        return plugin.getAccountCurrentlyViewed().equals(FlippingPlugin.ACCOUNT_WIDE) ? createAccountWideRecipeFlipGroupList() : plugin.getDataHandler().viewAccountData(plugin.getAccountCurrentlyViewed()).getRecipeFlipGroups();
    }

    public Duration viewAccumulatedTimeForCurrentView() {
        if (plugin.getAccountCurrentlyViewed().equals(FlippingPlugin.ACCOUNT_WIDE)) {
            long millis = plugin.getDataHandler().viewAllAccountData().stream().map(AccountData::getAccumulatedSessionTimeMillis).reduce(0L, (d1, d2) -> d1 + d2);
            return Duration.of(millis, ChronoUnit.MILLIS);
        } else {
            long millis = plugin.getDataHandler().viewAccountData(plugin.getAccountCurrentlyViewed()).getAccumulatedSessionTimeMillis();
            return Duration.of(millis, ChronoUnit.MILLIS);
        }
    }

    public Instant viewStartOfSessionForCurrentView() {
        if (plugin.getAccountCurrentlyViewed().equals(FlippingPlugin.ACCOUNT_WIDE)) {
            return startUpTime;
        } else {
            return plugin.getDataHandler().viewAccountData(plugin.getAccountCurrentlyViewed()).getSessionStartTime();
        }
    }

    private List<RecipeFlipGroup> createAccountWideRecipeFlipGroupList() {
        if (!updateSinceLastRecipeFlipGroupAccountWideBuild) {
            return prevBuildAccountWideRecipeFlipGroup;
        }
        if (plugin.getDataHandler().getCurrentAccounts().size() == 0) {
            return new ArrayList<>();
        }
        List<RecipeFlipGroup> built = plugin.getRecipeHandler().createAccountWideRecipeFlipGroupList(plugin.getDataHandler().viewAllAccountData());
        // Assign the cache BEFORE clearing the dirty flag: background readers (the stats
        // totals run on the executor) checking the flag would otherwise see "not dirty"
        // with a null cache during the very first build and NPE.
        prevBuildAccountWideRecipeFlipGroup = built;
        updateSinceLastRecipeFlipGroupAccountWideBuild = false;
        return built;
    }

    private List<FlippingItem> createAccountWideFlippingItemList() {
        //since this is an expensive operation, cache its results and only recompute it if there has been an update
        //to one of the account's tradelists, (updateSinceLastAccountWideBuild is set in onGrandExchangeOfferChanged)
        if (!updateSinceLastItemAccountWideBuild) {
            return prevBuiltAccountWideItemList;
        }

        if (plugin.getDataHandler().getCurrentAccounts().size() == 0) {
            return new ArrayList<>();
        }

        List<FlippingItem> built = plugin.getFlippingItemHandler().createAccountWideFlippingItemList(plugin.getDataHandler().viewAllAccountData());
        // Assign the cache BEFORE clearing the dirty flag (see createAccountWideRecipeFlipGroupList).
        prevBuiltAccountWideItemList = built;
        updateSinceLastItemAccountWideBuild = false;
        return built;
    }
}
