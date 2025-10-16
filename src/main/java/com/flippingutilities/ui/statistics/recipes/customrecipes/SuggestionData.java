package com.flippingutilities.ui.statistics.recipes.customrecipes;

import net.runelite.client.util.AsyncBufferedImage;

class SuggestionData {
    final int itemId;
    final String name;
    final int price;
    final AsyncBufferedImage image;

    SuggestionData(int itemId, String name, int price, AsyncBufferedImage image) {
        this.itemId = itemId;
        this.name = name;
        this.price = price;
        this.image = image;
    }
}
