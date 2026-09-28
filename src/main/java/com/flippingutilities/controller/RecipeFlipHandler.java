package com.flippingutilities.controller;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.utilities.Recipe;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

/** Applies recipe flip changes to the viewed account and marks them for persistence. */
@Slf4j
final class RecipeFlipHandler {
    private final FlippingPlugin plugin;

    RecipeFlipHandler(FlippingPlugin plugin) {
        this.plugin = plugin;
    }

    public void deleteRecipeFlipFromStorage(String recipeKey, RecipeFlip flip) {
        final String account = pluginAccountForRecipeWrites();
        if (account == null || flip == null || flip.getTimeOfCreation() == null) {
            return;
        }
        plugin.getDataHandler().markDataAsHavingChanged(account);
    }

    public void deleteRecipeFlipsSinceFromStorage(String recipeKey, Instant since) {
        final String account = pluginAccountForRecipeWrites();
        if (account == null || recipeKey == null) {
            return;
        }
        plugin.getDataHandler().markDataAsHavingChanged(account);
    }

    /** Recipe deletions target a real account; the account-wide view blocks them. */
    private String pluginAccountForRecipeWrites() {
        String viewed = plugin.getAccountCurrentlyViewed();
        if (viewed == null || viewed.equalsIgnoreCase(FlippingPlugin.ACCOUNT_WIDE)) {
            return null;
        }
        return viewed;
    }

    public void addRecipeFlip(RecipeFlip recipeFlip, Recipe recipe) {
        if (plugin.isAccountWideView()) {
            // The account-wide pseudo view has no AccountData to attach the flip to.
            log.warn("Cannot create a recipe flip from the account-wide view; switch to a specific account first");
            return;
        }
        AccountData account = plugin.getDataHandler().getAccountData(plugin.getAccountCurrentlyViewed());
        plugin.getRecipeHandler().addRecipeFlip(account.getRecipeFlipGroups(), recipeFlip, recipe);
        plugin.setUpdateSinceLastRecipeFlipGroupAccountWideBuild(true);

        plugin.getDataHandler().markDataAsHavingChanged(plugin.getAccountCurrentlyViewed());
    }
}
