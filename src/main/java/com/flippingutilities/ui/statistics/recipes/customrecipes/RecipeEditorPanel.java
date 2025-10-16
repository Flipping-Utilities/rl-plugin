package com.flippingutilities.ui.statistics.recipes.customrecipes;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.utilities.Recipe;
import com.flippingutilities.utilities.RecipeItem;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class RecipeEditorPanel extends JPanel {
    private final FlippingPlugin plugin;
    private final JTextField nameField;
    private final JPanel inputItemsContainer;
    private final JPanel outputItemsContainer;
    private final List<SelectedItemRow> inputRows = new ArrayList<>();
    private final List<SelectedItemRow> outputRows = new ArrayList<>();

    public RecipeEditorPanel(Recipe existingRecipe, FlippingPlugin plugin) {
        this.plugin = plugin;
        setLayout(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));

        JPanel namePanel = new JPanel(new BorderLayout(5, 0));
        namePanel.add(new JLabel("Recipe Name:"), BorderLayout.WEST);
        nameField = new JTextField(existingRecipe != null ? existingRecipe.getName() : "");
        nameField.setPreferredSize(new Dimension(300, 25));
        namePanel.add(nameField, BorderLayout.CENTER);
        mainPanel.add(namePanel);

        mainPanel.add(Box.createVerticalStrut(15));

        inputItemsContainer = new JPanel();
        inputItemsContainer.setLayout(new BoxLayout(inputItemsContainer, BoxLayout.Y_AXIS));
        mainPanel.add(createItemSection("Inputs", inputItemsContainer, inputRows, existingRecipe != null ? existingRecipe.getInputs() : null));

        mainPanel.add(Box.createVerticalStrut(15));

        outputItemsContainer = new JPanel();
        outputItemsContainer.setLayout(new BoxLayout(outputItemsContainer, BoxLayout.Y_AXIS));
        mainPanel.add(createItemSection("Outputs", outputItemsContainer, outputRows, existingRecipe != null ? existingRecipe.getOutputs() : null));

        add(mainPanel, BorderLayout.CENTER);
    }

    private JPanel createItemSection(String title, JPanel itemsContainer, List<SelectedItemRow> rows, List<RecipeItem> existingItems) {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setBorder(BorderFactory.createTitledBorder(title));
        section.setBackground(ColorScheme.DARK_GRAY_COLOR);

        if (existingItems != null && !existingItems.isEmpty()) {
            existingItems.forEach(item -> addItemRow(itemsContainer, rows, item.getId(), item.getQuantity()));
        }

        JScrollPane scrollPane = new JScrollPane(itemsContainer);
        scrollPane.setPreferredSize(new Dimension(450, 120));
        scrollPane.setBorder(new EmptyBorder(5, 5, 5, 5));
        section.add(scrollPane);

        ItemSearchBox searchBox = new ItemSearchBox(plugin, (itemId, quantity) -> {
            addItemRow(itemsContainer, rows, itemId, quantity);
        });
        section.add(searchBox);

        return section;
    }

    private void addItemRow(JPanel container, List<SelectedItemRow> rows, int itemId, int quantity) {
        SelectedItemRow row = new SelectedItemRow(plugin, itemId, quantity, (r) -> removeItemRow(container, rows, r));
        rows.add(row);
        container.add(row);
        container.revalidate();
        container.repaint();
    }

    private void removeItemRow(JPanel container, List<SelectedItemRow> rows, SelectedItemRow row) {
        rows.remove(row);
        container.remove(row);
        container.revalidate();
        container.repaint();
    }

    public Recipe buildRecipe() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            return null;
        }

        List<RecipeItem> inputs = new ArrayList<>();
        for (SelectedItemRow row : inputRows) {
            inputs.add(new RecipeItem(row.getItemId(), row.getQuantity()));
        }

        List<RecipeItem> outputs = new ArrayList<>();
        for (SelectedItemRow row : outputRows) {
            outputs.add(new RecipeItem(row.getItemId(), row.getQuantity()));
        }

        if (inputs.isEmpty() || outputs.isEmpty()) {
            return null;
        }

        return new Recipe(inputs, outputs, name);
    }
}
