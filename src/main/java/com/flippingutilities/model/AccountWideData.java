package com.flippingutilities.model;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Data
@Slf4j
public class AccountWideData {
    List<Option> options = new ArrayList<>();
    List<Section> sections = new ArrayList<>();
    Map<String, Set<Integer>> favoriteItemLists = new HashMap<>();
    boolean shouldMakeNewAdditions = true;
    boolean enhancedSlots = true;
    String jwt;

    public boolean setDefaults() {
        boolean didChangeData = changeOldPropertyNames();

        if (options.isEmpty()) {
            setDefaultOptions();
            shouldMakeNewAdditions = false;
            didChangeData = true;
        }
        //adding wiki options to users' existing options only once and making sure that its not added again by setting shouldMakeNewAdditions.
        //i need to use that flag so i don't add it multiple times in case a user deletes those options.
        boolean alreadyHasWikiOptions = options.stream().anyMatch(o -> o.getProperty().equals(Option.WIKI_BUY) || o.getProperty().equals(Option.WIKI_SELL));
        if (shouldMakeNewAdditions && !alreadyHasWikiOptions) {
            options.add(0, new Option("n", Option.WIKI_SELL, "+0", false));
            options.add(0, new Option("j", Option.WIKI_BUY, "+0", false));
            shouldMakeNewAdditions = false;
            didChangeData = true;
        }

        if (sections.isEmpty()) {
            didChangeData = true;
            setDefaultFlippingItemPanelSections();
        }

        if (favoriteItemLists.isEmpty()) {
            didChangeData = true;
            favoriteItemLists.put("DEFAULT", new HashSet<>());
        }

        return didChangeData;
    }

    private boolean changeOldPropertyNames() {
        boolean changedOldNames = false;
        for (Option o : options) {
            if (o.getProperty().equals("marg sell")) {
                o.setProperty(Option.INSTA_BUY);
                changedOldNames = true;
            }
            if (o.getProperty().equals("marg buy")) {
                o.setProperty(Option.INSTA_SELL);
                changedOldNames = true;
            }
        }

        return changedOldNames;
    }

    private void setDefaultOptions() {
        options.add(new Option("n", Option.WIKI_SELL, "+0", false));
        options.add(new Option("j", Option.WIKI_BUY, "+0", false));
        options.add(new Option("p", Option.INSTA_BUY, "+0", false));
        options.add(new Option("l", Option.INSTA_SELL, "+0", false));
        options.add(new Option("o", Option.LAST_BUY, "+0", false));
        options.add(new Option("u", Option.LAST_SELL, "+0", false));

        options.add(new Option("p", Option.GE_LIMIT, "+0", true));
        options.add(new Option("l", Option.REMAINING_LIMIT, "+0", true));
        options.add(new Option("o", Option.CASHSTACK, "+0", true));
    }

    private void setDefaultFlippingItemPanelSections() {
        Section importantSection = new Section("Important");
        Section otherSection = new Section("Other");

        importantSection.defaultExpanded = true;
        importantSection.showLabel(Section.WIKI_BUY_PRICE, true);
        importantSection.showLabel(Section.WIKI_SELL_PRICE, true);
        importantSection.showLabel(Section.LAST_BUY_PRICE, true);
        importantSection.showLabel(Section.LAST_SELL_PRICE, true);
        importantSection.showLabel(Section.LAST_INSTA_SELL_PRICE, true);
        importantSection.showLabel(Section.LAST_INSTA_BUY_PRICE, true);

        otherSection.showLabel(Section.WIKI_PROFIT_EACH, true);
        otherSection.showLabel(Section.MARGIN_CHECK_PROFIT_EACH, true);
        otherSection.showLabel(Section.POTENTIAL_PROFIT, true);
        otherSection.showLabel(Section.REMAINING_GE_LIMIT, true);
        otherSection.showLabel(Section.ROI, true);
        otherSection.showLabel(Section.GE_LIMIT_REFRESH_TIMER, true);

        sections.add(importantSection);
        sections.add(otherSection);
    }

    public Set<Integer> getFavoriteListData(String listName) {
        return favoriteItemLists.get(listName);
    }

    public Set<String> getAllListNames () {
        return favoriteItemLists.keySet();
    }

    public boolean addNewFavoriteList (String listName){
        if (!favoriteItemLists.containsKey(listName)){
            favoriteItemLists.put(listName,new HashSet<>());
            return true;
        }
        else{
            return false;
        }
    }

    public void addItemToFavoriteList(String listName, int itemId){
        Set<Integer> list = favoriteItemLists.get(listName);
        list.add(itemId);
    }

    public void removeItemFromList(String listName, int itemId) {
        Set<Integer> set = favoriteItemLists.get(listName);
        set.remove(itemId);
    }

    public void deleteItemList(String listName){
        favoriteItemLists.remove(listName);
    }
}
