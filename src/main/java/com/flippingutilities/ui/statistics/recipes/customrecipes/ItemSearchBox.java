package com.flippingutilities.ui.statistics.recipes.customrecipes;

import com.flippingutilities.FlippingConfig;
import com.flippingutilities.controller.FlippingPlugin;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.http.api.item.ItemPrice;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

public class ItemSearchBox extends JPanel {
    private final FlippingPlugin plugin;
    private final JTextField searchField;
    private final JSpinner quantitySpinner;
    private final JPopupMenu suggestionPopup;
    private final BiConsumer<Integer, Integer> onItemSelected;

    public ItemSearchBox(FlippingPlugin plugin, BiConsumer<Integer, Integer> onItemSelected) {
        this.plugin = plugin;
        this.onItemSelected = onItemSelected;

        setLayout(new FlowLayout(FlowLayout.LEFT, 5, 0));
        setBackground(ColorScheme.DARKER_GRAY_COLOR);
        setBorder(new EmptyBorder(5, 5, 5, 5));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

        add(new JLabel("Search:"));

        searchField = new JTextField(15);
        searchField.setToolTipText("Item name...");
        add(searchField);

        add(new JLabel("Qty:"));

        quantitySpinner = new JSpinner(new SpinnerNumberModel(1, 1, 999999, 1));
        quantitySpinner.setPreferredSize(new Dimension(80, 25));
        add(quantitySpinner);

        suggestionPopup = new JPopupMenu();
        suggestionPopup.setFocusable(false);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateSuggestions();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateSuggestions();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateSuggestions();
            }
        });
    }

    private void updateSuggestions() {
        String query = searchField.getText().toLowerCase().trim();
        if (query.length() < 2) {
            suggestionPopup.setVisible(false);
            return;
        }

        plugin.getClientThread().invoke(() -> {
            List<ItemComposition> matches = findMatchingItems(query);
            List<SuggestionData> suggestionData = new ArrayList<>();

            ItemManager itemManager = plugin.getItemManager();
            for (ItemComposition item : matches.subList(0, Math.min(10, matches.size()))) {
                int price = itemManager.getItemPrice(item.getId());
                AsyncBufferedImage image = itemManager.getImage(item.getId());
                suggestionData.add(new SuggestionData(item.getId(), item.getName(), price, image));
            }

            SwingUtilities.invokeLater(() -> {
                suggestionPopup.removeAll();

                if (suggestionData.isEmpty()) {
                    suggestionPopup.setVisible(false);
                    return;
                }

                for (SuggestionData data : suggestionData) {
                    JMenuItem menuItem = createSuggestionItem(data);
                    suggestionPopup.add(menuItem);
                }

                suggestionPopup.pack();
                suggestionPopup.show(searchField, 0, searchField.getHeight());
            });
        });
    }

    private JMenuItem createSuggestionItem(SuggestionData data) {
        String priceText = data.price > 0 ? String.format(" (%,d gp)", data.price) : " (untradeable)";
        JMenuItem menuItem = new JMenuItem(data.name + priceText);
        menuItem.setPreferredSize(new Dimension(280, 30));
        data.image.onLoaded(() -> menuItem.setIcon(new ImageIcon(data.image)));

        menuItem.addActionListener(e -> {
            int quantity = (Integer) quantitySpinner.getValue();
            onItemSelected.accept(data.itemId, quantity);
            searchField.setText("");
            quantitySpinner.setValue(1);
            suggestionPopup.setVisible(false);
        });

        return menuItem;
    }

    private List<ItemComposition> findMatchingItems(String query) {
        FlippingConfig pluginConfig = plugin.getConfig();
        boolean includeNoted = pluginConfig.includeNotedItems();
        boolean includeUntradeable = pluginConfig.includeUntradeableItems();

        // itemmanager.search() is 30-40x faster but only returns tradeable items
        // if user wants untradeable/noted items, we fall back to the "slow" 30k loop
        return (includeUntradeable || includeNoted)
            ? findMatchingItemsFullScan(query, includeNoted, includeUntradeable)
            : findMatchingItemsOptimized(query);
    }

    private List<ItemComposition> findMatchingItemsOptimized(String query) {
        List<ItemComposition> matches = new ArrayList<>();
        ItemManager itemManager = plugin.getItemManager();

        for (ItemPrice itemInfo : itemManager.search(query)) {
            ItemComposition item = itemManager.getItemComposition(itemInfo.getId());
            if (isInvalidItem(item)) continue;

            matches.add(item);
            if (matches.size() >= 10) break;
        }
        return matches;
    }

    private List<ItemComposition> findMatchingItemsFullScan(String query, boolean includeNoted, boolean includeUntradeable) {
        List<ItemComposition> matches = new ArrayList<>();
        ItemManager itemManager = plugin.getItemManager();

        int maximumAmountOfItems = plugin.getClient().getItemCount();
        for (int i = 0; i < maximumAmountOfItems; i++) {
            ItemComposition item = itemManager.getItemComposition(i);
            if (isInvalidItem(item)) continue;
            if (!item.getName().toLowerCase().contains(query)) continue;
            if (!passesFilters(item, includeNoted, includeUntradeable, itemManager)) continue;

            matches.add(item);
            if (matches.size() >= 10) break;
        }
        return matches;
    }

    private boolean isInvalidItem(ItemComposition item) {
        return item == null || item.getName() == null || item.getName().equals("null");
    }

    private boolean passesFilters(ItemComposition item, boolean includeNoted, boolean includeUntradeable, ItemManager itemManager) {
        int price = itemManager.getItemPrice(item.getId());
        if (!includeUntradeable && price == 0) return false;

        return includeNoted || item.getNote() == -1;
    }
}
