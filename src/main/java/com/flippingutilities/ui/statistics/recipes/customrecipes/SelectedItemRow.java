package com.flippingutilities.ui.statistics.recipes.customrecipes;

import com.flippingutilities.controller.FlippingPlugin;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.AsyncBufferedImage;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.Consumer;

public class SelectedItemRow extends JPanel {
    private final FlippingPlugin plugin;
    private final int itemId;
    private final JSpinner quantitySpinner;

    public SelectedItemRow(FlippingPlugin plugin, int itemId, int quantity, Consumer<SelectedItemRow> onRemove) {
        this.plugin = plugin;
        this.itemId = itemId;

        setLayout(new BorderLayout(5, 0));
        setBackground(ColorScheme.DARKER_GRAY_COLOR);
        setBorder(new EmptyBorder(3, 5, 3, 5));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

        JPanel itemInfoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        itemInfoPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        JLabel iconLabel = new JLabel();
        iconLabel.setPreferredSize(new Dimension(32, 32));

        JLabel nameLabel = new JLabel();
        nameLabel.setPreferredSize(new Dimension(170, 25));

        JLabel priceLabel = new JLabel();
        priceLabel.setForeground(Color.YELLOW);

        plugin.getClientThread().invoke(() -> {
            ItemManager itemManager = plugin.getItemManager();
            ItemComposition item = itemManager.getItemComposition(itemId);
            int price = itemManager.getItemPrice(itemId);
            SwingUtilities.invokeLater(() -> {
                AsyncBufferedImage itemImage = itemManager.getImage(itemId);
                itemImage.addTo(iconLabel);
                nameLabel.setText(item.getName());
                if (price > 0) {
                    priceLabel.setText(String.format("%,d gp", price));
                }
            });
        });

        itemInfoPanel.add(iconLabel);
        itemInfoPanel.add(nameLabel);
        itemInfoPanel.add(priceLabel);

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        controlPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        quantitySpinner = new JSpinner(new SpinnerNumberModel(quantity, 1, 999999, 1));
        quantitySpinner.setPreferredSize(new Dimension(80, 25));

        JButton removeButton = new JButton("×");
        removeButton.setPreferredSize(new Dimension(30, 25));
        removeButton.setMargin(new Insets(0, 0, 0, 0));
        removeButton.addActionListener(e -> onRemove.accept(this));

        controlPanel.add(new JLabel("Qty:"));
        controlPanel.add(quantitySpinner);
        controlPanel.add(removeButton);

        add(itemInfoPanel, BorderLayout.WEST);
        add(controlPanel, BorderLayout.EAST);
    }

    public int getItemId() {
        return itemId;
    }

    public int getQuantity() {
        return (Integer) quantitySpinner.getValue();
    }
}
