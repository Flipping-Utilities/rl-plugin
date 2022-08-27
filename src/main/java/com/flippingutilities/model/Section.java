package com.flippingutilities.model;

import lombok.Data;

import java.util.*;

/**
 * Represents one of the sections in the FlippingItemPanel. Since a user can heavily customize what a FlippingItemPanel
 * looks like (its sections), we have to store what their customizations are. This stores the customizations to a section.
 */
@Data
public class Section {
    String name;
    public static final String WIKI_BUY_PRICE = "wiki buy price";
    public static final String WIKI_SELL_PRICE = "wiki sell price";
    public static final String LAST_INSTA_SELL_PRICE = "price check buy price";
    public static final String LAST_INSTA_BUY_PRICE = "price check sell price";
    public static final String LAST_BUY_PRICE = "latest buy price";
    public static final String LAST_SELL_PRICE = "latest sell price";
    public static final String WIKI_PROFIT_EACH = "profit each";
    public static final String POTENTIAL_PROFIT = "potential profit";
    public static final String ROI = "roi";
    public static final String REMAINING_GE_LIMIT = "remaining ge limit";
    public static final String GE_LIMIT_REFRESH_TIMER = "ge limit refresh timer";
    public static final String MARGIN_CHECK_PROFIT_EACH = "margin check profit each";
    public static final List<String> possibleLabels = Arrays.asList(WIKI_BUY_PRICE, WIKI_SELL_PRICE, LAST_INSTA_SELL_PRICE, LAST_INSTA_BUY_PRICE, LAST_BUY_PRICE,
        LAST_SELL_PRICE, WIKI_PROFIT_EACH, POTENTIAL_PROFIT, ROI, REMAINING_GE_LIMIT, GE_LIMIT_REFRESH_TIMER, MARGIN_CHECK_PROFIT_EACH);
    Map<String, Boolean> labels;
    boolean defaultExpanded;

    public Section(String name) {
        this.name = name;
        this.labels = new LinkedHashMap<>();
        for (String label:Section.possibleLabels) {
            this.labels.put(label, false);
        }
    }

    public boolean isShowingLabel(String labelName) {
        if (labels.containsKey(labelName)) {
            return labels.get(labelName);
        }
        return false;
    }

    public void showLabel(String labelName, boolean shouldShow) {
            labels.put(labelName, shouldShow);
    }
}
