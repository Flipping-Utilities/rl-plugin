package com.flippingutilities.db;

/** The two persisted directions of a recipe's consumed offers. */
enum RecipeComponentTable {
    INPUTS("recipe_flip_inputs"),
    OUTPUTS("recipe_flip_outputs");

    private final String tableName;

    RecipeComponentTable(String tableName) {
        this.tableName = tableName;
    }

    String tableName() {
        return tableName;
    }
}
