package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.Section;
import com.flippingutilities.ui.widgets.SlotActivityTimer;
import com.flippingutilities.utilities.Recipe;
import com.flippingutilities.utilities.RecipeItem;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.InstanceCreator;

/** Avoid Gson's reflective Unsafe allocation, which is unavailable reliably in CheerpJ. */
public final class BrowserGson {
    private BrowserGson() {}

    public static Gson create() {
        // Match Unsafe's zero-initialized objects; Gson still hydrates every persisted field
        // using its normal reflective adapters. TradePersister adds its legacy Instant adapter.
        return new GsonBuilder()
            .registerTypeAdapter(SlotActivityTimer.class,
                (InstanceCreator<SlotActivityTimer>) type -> new SlotActivityTimer(null, null, 0))
            .registerTypeAdapter(RecipeFlip.class,
                (InstanceCreator<RecipeFlip>) type -> new RecipeFlip(null, null, null, 0L))
            .registerTypeAdapter(Recipe.class,
                (InstanceCreator<Recipe>) type -> new Recipe(null, null, null))
            .registerTypeAdapter(RecipeItem.class,
                (InstanceCreator<RecipeItem>) type -> new RecipeItem(0, 0))
            .registerTypeAdapter(Section.class, (InstanceCreator<Section>) type -> {
                Section section = new Section(null);
                section.setLabels(null);
                return section;
            })
            .create();
    }
}
