package com.flippingutilities.ui.statistics.recipes.customrecipes;

import com.flippingutilities.controller.DataHandler;
import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.controller.RecipeHandler;
import com.flippingutilities.utilities.Recipe;
import com.flippingutilities.utilities.RecipeItem;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class CustomRecipeManagerPanel extends JPanel {
    private final FlippingPlugin plugin;
    private JPanel recipeListPanel;
    private String currentSortOption;
    private String searchQuery = "";

    public CustomRecipeManagerPanel(FlippingPlugin plugin) {
        this.plugin = plugin;
        this.currentSortOption = plugin.getConfig().defaultRecipeSort().toString();
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        topPanel.add(createHeaderPanel());
        topPanel.add(createSortPanel());

        add(topPanel, BorderLayout.NORTH);
        add(createRecipeListScrollPane(), BorderLayout.CENTER);
    }

    private JPanel createHeaderPanel() {
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        headerPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JLabel titleLabel = new JLabel("Custom Recipes");
        titleLabel.setFont(FontManager.getRunescapeBoldFont());

        JPanel rightPanel = new JPanel();
        rightPanel.setLayout(new BoxLayout(rightPanel, BoxLayout.Y_AXIS));
        rightPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JButton addButton = new JButton("Add Recipe");
        addButton.setAlignmentX(Component.RIGHT_ALIGNMENT);
        addButton.addActionListener(e -> openRecipeEditorDialog(null));

        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        searchPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        searchPanel.setAlignmentX(Component.RIGHT_ALIGNMENT);

        JLabel searchLabel = new JLabel("Search:");
        searchLabel.setFont(FontManager.getRunescapeFont());

        JLabel helpLabel = new JLabel("?");
        helpLabel.setFont(FontManager.getRunescapeFont());
        helpLabel.setForeground(Color.GRAY);
        helpLabel.setToolTipText("Search by recipe name or ingredient names");

        JTextField searchField = new JTextField(15);
        searchField.setText("Type to search...");
        searchField.setForeground(Color.GRAY);
        searchField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusGained(java.awt.event.FocusEvent e) {
                if (searchField.getText().equals("Type to search...")) {
                    searchField.setText("");
                    searchField.setForeground(Color.WHITE);
                }
            }
            public void focusLost(java.awt.event.FocusEvent e) {
                if (searchField.getText().isEmpty()) {
                    searchField.setText("Type to search...");
                    searchField.setForeground(Color.GRAY);
                }
            }
        });
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateSearch(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateSearch(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateSearch(); }
            private void updateSearch() {
                String text = searchField.getText();
                if (text.equals("Type to search...")) {
                    searchQuery = "";
                } else {
                    searchQuery = text.toLowerCase().trim();
                }
                plugin.getClientThread().invokeLater(() -> {
                    List<Recipe> allRecipes = new ArrayList<>(plugin.getRecipeHandler().getLocalRecipes());
                    List<Recipe> filtered = searchQuery.isEmpty() ? allRecipes : filterRecipes(allRecipes);
                    sortRecipes(filtered);
                    SwingUtilities.invokeLater(() -> displayRecipes(filtered));
                });
            }
        });

        searchPanel.add(searchLabel);
        searchPanel.add(helpLabel);
        searchPanel.add(searchField);

        rightPanel.add(addButton);
        rightPanel.add(Box.createVerticalStrut(5));
        rightPanel.add(searchPanel);

        headerPanel.add(titleLabel, BorderLayout.WEST);
        headerPanel.add(rightPanel, BorderLayout.EAST);

        return headerPanel;
    }

    private JPanel createSortPanel() {
        JPanel sortPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        sortPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel sortLabel = new JLabel("Sort by:");
        sortLabel.setFont(FontManager.getRunescapeFont());

        String[] sortOptions = {"Input Count", "Output Count", "Name"};
        JComboBox<String> sortDropdown = new JComboBox<>(sortOptions);
        sortDropdown.setSelectedItem(currentSortOption);
        sortDropdown.addActionListener(e -> {
            currentSortOption = (String) sortDropdown.getSelectedItem();
            refreshRecipeList();
        });

        sortPanel.add(sortLabel);
        sortPanel.add(sortDropdown);

        return sortPanel;
    }

    private JScrollPane createRecipeListScrollPane() {
        recipeListPanel = new JPanel();
        recipeListPanel.setLayout(new BoxLayout(recipeListPanel, BoxLayout.Y_AXIS));
        recipeListPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        recipeListPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

        refreshRecipeList();

        JScrollPane scrollPane = new JScrollPane(recipeListPanel);
        scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
        scrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(5, 0));
        scrollPane.setBorder(null);

        return scrollPane;
    }

    private void refreshRecipeList() {
        plugin.getClientThread().invokeLater(() -> {
            List<Recipe> allRecipes = new ArrayList<>(plugin.getRecipeHandler().getLocalRecipes());
            List<Recipe> filtered = searchQuery.isEmpty() ? allRecipes : filterRecipes(allRecipes);
            sortRecipes(filtered);
            SwingUtilities.invokeLater(() -> displayRecipes(filtered));
        });
    }

    private void displayRecipes(List<Recipe> recipes) {
        recipeListPanel.removeAll();

        if (recipes.isEmpty()) {
            String message = searchQuery.isEmpty()
                ? "No custom recipes yet. Click 'Add Recipe' to create one."
                : "No recipes found matching '" + searchQuery + "'";
            JLabel emptyLabel = new JLabel(message);
            emptyLabel.setFont(FontManager.getRunescapeFont());
            emptyLabel.setBorder(new EmptyBorder(20, 10, 20, 10));
            recipeListPanel.add(emptyLabel);
        } else {
            for (int i = 0; i < recipes.size(); i++) {
                recipeListPanel.add(createRecipePanel(recipes.get(i)));
                if (i < recipes.size() - 1) {
                    recipeListPanel.add(Box.createVerticalStrut(10));
                }
            }
        }

        recipeListPanel.revalidate();
        recipeListPanel.repaint();
    }

    private List<Recipe> filterRecipes(List<Recipe> recipes) {
        return recipes.stream().filter(recipe -> {
            if (recipe.getName().toLowerCase().contains(searchQuery)) {
                return true;
            }

            for (RecipeItem item : recipe.getInputs()) {
                String itemName = getItemName(item.getId());
                if (itemName.toLowerCase().contains(searchQuery)) {
                    return true;
                }
            }

            for (RecipeItem item : recipe.getOutputs()) {
                String itemName = getItemName(item.getId());
                if (itemName.toLowerCase().contains(searchQuery)) {
                    return true;
                }
            }

            return false;
        }).collect(Collectors.toList());
    }

    private String getItemName(int itemId) {
        try {
            return plugin.getItemManager().getItemComposition(itemId).getName();
        } catch (Exception e) {
            return "";
        }
    }

    private void sortRecipes(List<Recipe> recipes) {
        switch (currentSortOption) {
            case "Input Count":
                recipes.sort(Comparator.comparingInt(r -> r.getInputs().size()));
                break;
            case "Output Count":
                recipes.sort(Comparator.comparingInt(r -> r.getOutputs().size()));
                break;
            case "Name":
                recipes.sort(Comparator.comparing(Recipe::getName, String.CASE_INSENSITIVE_ORDER));
                break;
        }
    }

    private JPanel createRecipePanel(Recipe recipe) {
        JPanel panel = new JPanel(new BorderLayout(10, 5));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(15, 15, 15, 15));

        int maxItems = Math.max(recipe.getInputs().size(), recipe.getOutputs().size());
        int dynamicHeight = 70 + (maxItems * 38); // base + item height per row
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, dynamicHeight));

        JPanel leftPanel = new JPanel();
        leftPanel.setLayout(new BoxLayout(leftPanel, BoxLayout.Y_AXIS));
        leftPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        JLabel nameLabel = new JLabel(recipe.getName());
        nameLabel.setFont(FontManager.getRunescapeBoldFont());
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        leftPanel.add(nameLabel);

        leftPanel.add(Box.createVerticalStrut(5));

        JSeparator separator = new JSeparator(SwingConstants.HORIZONTAL);
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        separator.setForeground(new Color(60, 60, 60));
        leftPanel.add(separator);

        leftPanel.add(Box.createVerticalStrut(5));

        JPanel tablesPanel = new JPanel();
        tablesPanel.setLayout(new BoxLayout(tablesPanel, BoxLayout.X_AXIS));
        tablesPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        tablesPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        tablesPanel.add(createItemsTable("Inputs", recipe.getInputs()));
        tablesPanel.add(Box.createHorizontalStrut(15));

        JLabel arrowLabel = new JLabel("→");
        arrowLabel.setFont(new Font("Arial", Font.BOLD, 24));
        arrowLabel.setAlignmentY(Component.TOP_ALIGNMENT);
        tablesPanel.add(arrowLabel);
        tablesPanel.add(Box.createHorizontalStrut(15));

        tablesPanel.add(createItemsTable("Outputs", recipe.getOutputs()));

        leftPanel.add(tablesPanel);

        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.Y_AXIS));
        buttonPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        JButton editButton = new JButton("Edit");
        editButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        editButton.addActionListener(e -> openRecipeEditorDialog(recipe));

        JButton deleteButton = new JButton("Delete");
        deleteButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        deleteButton.addActionListener(e -> deleteRecipe(recipe));

        buttonPanel.add(Box.createVerticalGlue());
        buttonPanel.add(editButton);
        buttonPanel.add(Box.createVerticalStrut(5));
        buttonPanel.add(deleteButton);
        buttonPanel.add(Box.createVerticalGlue());

        panel.add(leftPanel, BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.EAST);

        return panel;
    }

    private JPanel createItemsTable(String title, List<RecipeItem> items) {
        JPanel container = new JPanel();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
        container.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        container.setAlignmentY(Component.TOP_ALIGNMENT);

        String displayTitle = items.size() == 1 ? title.replaceAll("s$", "") : title;
        JLabel titleLabel = new JLabel(displayTitle + ":");
        titleLabel.setFont(FontManager.getRunescapeBoldFont());
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        container.add(titleLabel);
        container.add(Box.createVerticalStrut(2));

        ItemManager itemManager = plugin.getItemManager();
        for (int i = 0; i < items.size(); i++) {
            RecipeItem item = items.get(i);

            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(250, 36));

            JLabel iconLabel = new JLabel();
            iconLabel.setPreferredSize(new Dimension(32, 32));

            JLabel nameLabel = new JLabel("Loading...");
            nameLabel.setPreferredSize(new Dimension(170, 32));

            JLabel qtyLabel = new JLabel("×" + item.getQuantity());
            qtyLabel.setPreferredSize(new Dimension(50, 32));

            plugin.getClientThread().invoke(() -> {
                net.runelite.api.ItemComposition itemComp = itemManager.getItemComposition(item.getId());
                net.runelite.client.util.AsyncBufferedImage itemImage = itemManager.getImage(item.getId());
                SwingUtilities.invokeLater(() -> {
                    itemImage.addTo(iconLabel);
                    nameLabel.setText(itemComp.getName());
                });
            });

            row.add(iconLabel);
            row.add(nameLabel);
            row.add(qtyLabel);

            container.add(row);

            if (i < items.size() - 1) {
                container.add(Box.createVerticalStrut(2));
            }
        }

        return container;
    }

    private void openRecipeEditorDialog(Recipe existingRecipe) {
        JDialog dialog = new JDialog();
        dialog.setTitle(existingRecipe == null ? "Add Recipe" : "Edit Recipe");
        dialog.setModal(true);

        RecipeEditorPanel editorPanel = new RecipeEditorPanel(existingRecipe, plugin);
        dialog.add(editorPanel);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton saveButton = new JButton("Save");
        saveButton.addActionListener(e -> {
            Recipe recipe = editorPanel.buildRecipe();
            if (recipe == null) {
                JOptionPane.showMessageDialog(dialog, "Please fill all fields correctly. Some fields are missing.");
                return;
            }

            RecipeHandler recipeHandler = plugin.getRecipeHandler();
            if (existingRecipe != null) {
                recipeHandler.updateLocalRecipe(existingRecipe, recipe);
            } else {
                recipeHandler.addLocalRecipe(recipe);
            }

            List<Recipe> fromHandler = recipeHandler.getLocalRecipes();
            DataHandler dataHandler = plugin.getDataHandler();
            List<Recipe> localRecipes = dataHandler.getAccountWideData().getLocalRecipes();
            localRecipes.clear();
            localRecipes.addAll(fromHandler);
            dataHandler.markDataAsHavingChanged(FlippingPlugin.ACCOUNT_WIDE);
            dataHandler.storeData();

            refreshRecipeList();
            dialog.dispose();
        });

        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> dialog.dispose());

        buttonPanel.add(saveButton);
        buttonPanel.add(cancelButton);

        dialog.add(buttonPanel, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(560, dialog.getHeight()));
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void deleteRecipe(Recipe recipe) {
        int confirm = JOptionPane.showConfirmDialog(
                this,
                "Are you sure you want to delete '" + recipe.getName() + "'?",
                "Delete Recipe",
                JOptionPane.YES_NO_OPTION
        );

        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        RecipeHandler recipeHandler = plugin.getRecipeHandler();
        recipeHandler.removeLocalRecipe(recipe);
        List<Recipe> fromHandler = recipeHandler.getLocalRecipes();
        DataHandler dataHandler = plugin.getDataHandler();
        List<Recipe> localRecipes = dataHandler.getAccountWideData().getLocalRecipes();
        localRecipes.clear();
        localRecipes.addAll(fromHandler);
        dataHandler.markDataAsHavingChanged(FlippingPlugin.ACCOUNT_WIDE);
        dataHandler.storeData();

        refreshRecipeList();
    }
}
