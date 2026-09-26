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
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.utilities.Recipe;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

/** Applies recipe flip changes to the viewed account and queues their persistence. */
@Slf4j
final class RecipeFlipHandler {
    private final FlippingPlugin plugin;

    RecipeFlipHandler(FlippingPlugin plugin) {
        this.plugin = plugin;
    }

    public void deleteRecipeFlipFromStorage(String recipeKey, RecipeFlip flip) {
        if (plugin.getSqliteStorage() == null) {
            return;
        }
        final String account = pluginAccountForRecipeWrites();
        if (account == null || flip == null || flip.getTimeOfCreation() == null) {
            return;
        }
        final String key = recipeKey;
        final Instant created = flip.getTimeOfCreation();
        plugin.submitStorageTask(storage -> storage.deleteRecipeFlip(account, key, created));
    }

    public void deleteRecipeFlipsSinceFromStorage(String recipeKey, Instant since) {
        if (plugin.getSqliteStorage() == null) {
            return;
        }
        final String account = pluginAccountForRecipeWrites();
        if (account == null || recipeKey == null) {
            return;
        }
        final String key = recipeKey;
        final Instant start = since;
        plugin.submitStorageTask(storage -> storage.deleteRecipeFlipsSince(account, key, start));
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
        if (FlippingPlugin.ACCOUNT_WIDE.equals(plugin.getAccountCurrentlyViewed())) {
            // The account-wide pseudo view has no AccountData to attach the flip to (and
            // persisting it would create a bogus "Accountwide" account in the DB).
            log.warn("Cannot create a recipe flip from the account-wide view; switch to a specific account first");
            return;
        }
        AccountData account = plugin.getDataHandler().getAccountData(plugin.getAccountCurrentlyViewed());
        plugin.getRecipeHandler().addRecipeFlip(account.getRecipeFlipGroups(), recipeFlip, recipe);
        plugin.setUpdateSinceLastRecipeFlipGroupAccountWideBuild(true);

        // Persist live-created recipe flips to SQLite, otherwise they are lost on the next
        // reload in SQLite mode (they only lived in memory/JSON). Best-effort on the executor.
        if (plugin.getSqliteStorage() != null) {
            final String accountName = plugin.getAccountCurrentlyViewed();
            final String recipeKey = RecipeHandler.createRecipeKey(recipe);
            final RecipeFlip snapshot = recipeFlip.clone();
            plugin.submitStorageTask(storage -> storage.insertRecipeFlip(accountName, recipeKey, snapshot));
        }
    }
}
