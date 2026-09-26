package com.flippingutilities.ui.statistics.recipes.customrecipes;

import net.runelite.client.util.AsyncBufferedImage;

class SuggestionData {
    final int itemId;
    final String name;
    // 64-bit since the max cash update
    final long price;
    final AsyncBufferedImage image;

    SuggestionData(int itemId, String name, long price, AsyncBufferedImage image) {
        this.itemId = itemId;
        this.name = name;
        this.price = price;
        this.image = image;
    }
}
