package com.flippingutilities.controller;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.RecipeFlipGroup;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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

    boolean isAccountWideView() {
        return FlippingPlugin.ACCOUNT_WIDE.equals(plugin.getAccountCurrentlyViewed());
    }

    boolean isAccountInCurrentView(String accountName) {
        return isAccountWideView() || plugin.getAccountCurrentlyViewed().equals(accountName);
    }

    /** Snapshot the selected names without marking their data for saving. */
    List<String> getAccountNamesForCurrentView() {
        return isAccountWideView()
            ? new ArrayList<>(plugin.getDataHandler().getCurrentAccounts())
            : Collections.singletonList(plugin.getAccountCurrentlyViewed());
    }

    /** Access the underlying accounts for mutation, marking each for saving. */
    Collection<AccountData> getAccountsForCurrentView() {
        return isAccountWideView()
            ? plugin.getDataHandler().getAllAccountData()
            : Collections.singletonList(plugin.getDataHandler().getAccountData(plugin.getAccountCurrentlyViewed()));
    }

    public List<FlippingItem> getItemsForCurrentView() {
        return isAccountWideView() ? createAccountWideFlippingItemList() : plugin.getDataHandler().getAccountData(plugin.getAccountCurrentlyViewed()).getTrades();
    }

    public List<FlippingItem> viewItemsForCurrentView() {
        return isAccountWideView() ? createAccountWideFlippingItemList() : plugin.getDataHandler().viewAccountData(plugin.getAccountCurrentlyViewed()).getTrades();
    }

    public List<RecipeFlipGroup> viewRecipeFlipGroupsForCurrentView() {
        return isAccountWideView() ? createAccountWideRecipeFlipGroupList() : plugin.getDataHandler().viewAccountData(plugin.getAccountCurrentlyViewed()).getRecipeFlipGroups();
    }

    public Duration viewAccumulatedTimeForCurrentView() {
        if (isAccountWideView()) {
            long millis = plugin.getDataHandler().viewAllAccountData().stream().map(AccountData::getAccumulatedSessionTimeMillis).reduce(0L, (d1, d2) -> d1 + d2);
            return Duration.of(millis, ChronoUnit.MILLIS);
        } else {
            long millis = plugin.getDataHandler().viewAccountData(plugin.getAccountCurrentlyViewed()).getAccumulatedSessionTimeMillis();
            return Duration.of(millis, ChronoUnit.MILLIS);
        }
    }

    public Instant viewStartOfSessionForCurrentView() {
        if (isAccountWideView()) {
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
