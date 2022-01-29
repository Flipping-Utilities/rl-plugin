package com.flippingutilities.ui.statistics.recipes;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.*;
import com.flippingutilities.ui.statistics.StatsPanel;
import com.flippingutilities.ui.statistics.items.FlipPanel;
import com.flippingutilities.ui.statistics.items.OfferPanel;
import com.flippingutilities.ui.uiutilities.CustomColors;
import com.flippingutilities.ui.uiutilities.Icons;
import com.flippingutilities.ui.uiutilities.Paginator;
import com.flippingutilities.ui.uiutilities.UIUtilities;
import lombok.Getter;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.QuantityFormatter;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class RecipeFlipGroupPanel extends JPanel {

    private FlippingPlugin plugin;
    @Getter
    private RecipeFlipGroup recipeFlipGroup;

    private StatsPanel statsPanel;

    private JLabel recipeProfitAndQuantityLabel = new JLabel();
    private JPanel itemIconTitlePanel = new JPanel(new BorderLayout());
    //Label that controls the collapse function of the item panel.
    private JLabel collapseIconTitleLabel = new JLabel();

    private JLabel totalProfitValLabel = new JLabel("", SwingConstants.RIGHT);
    private JLabel profitEachValLabel = new JLabel("", SwingConstants.RIGHT);
    private JLabel quantityFlipped = new JLabel("", SwingConstants.RIGHT);
    private JLabel roiValLabel = new JLabel("", SwingConstants.RIGHT);


    private List<RecipeFlipPanel> recipeFlipPanels = new ArrayList<>();
    private Paginator recipeFlipPaginator;
    private JPanel recipeFlipsBackgroundPanel = new JPanel();


    RecipeFlipGroupPanel(FlippingPlugin plugin, RecipeFlipGroup recipeFlipGroup) {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, ColorScheme.DARKER_GRAY_COLOR.darker()));

        this.plugin = plugin;
        this.recipeFlipGroup = recipeFlipGroup;
        this.statsPanel = plugin.getStatPanel();

        List<RecipeFlip> flips = recipeFlipGroup.getRecipeFlips();

        this.recipeFlipPaginator = createPaginator(() -> putRecipeFlipPanelsOnBackgroundPanel(flips));
        recipeFlipPaginator.updateTotalPages(flips.size());

        recipeFlipPanels = createRecipeFlipPanels(flips);

        JLabel[] descriptionLabels = {new JLabel("Total Profit: "), new JLabel("Avg. Profit ea: "), new JLabel("Avg. ROI: "), new JLabel("Quantity Flipped: ")};

        JLabel[] valueLabels = {totalProfitValLabel, profitEachValLabel, roiValLabel, quantityFlipped};

        JPanel subInfoPanel = createSubInfoPanel(descriptionLabels, valueLabels);
        JPanel tradeHistoryPanel = createTradeHistoryPanel(recipeFlipsBackgroundPanel);
        JPanel subInfoAndHistoryContainer = createSubInfoAndHistoryContainer(subInfoPanel, tradeHistoryPanel);
        JPanel titlePanel = createTitlePanel(createIconPanel(plugin.getItemManager()), createNameAndProfitPanel(), createCollapseIcon(), subInfoAndHistoryContainer);

        updateLabels(offers, adjustedOffers);

        add(titlePanel, BorderLayout.NORTH);
        add(subInfoAndHistoryContainer, BorderLayout.CENTER);
    }

    private JPanel createSubInfoAndHistoryContainer(JPanel subInfoPanel, JPanel tradeHistoryPanel) {
        JPanel subInfoAndHistoryContainer = new JPanel(new BorderLayout());
        //Set background and border of container with sub infos and trade history
        subInfoAndHistoryContainer.setBackground(CustomColors.DARK_GRAY_LIGHTER);
        subInfoAndHistoryContainer.add(subInfoPanel, BorderLayout.CENTER);
        subInfoAndHistoryContainer.add(tradeHistoryPanel, BorderLayout.SOUTH);
        subInfoAndHistoryContainer.setVisible(statsPanel.getExpandedItems().contains(recipeFlipGroup.getItemName()));
        return subInfoAndHistoryContainer;
    }

    private Paginator createPaginator(Runnable runnable) {
        Paginator paginator = new Paginator(runnable);
        paginator.setPageSize(10);
        paginator.setBackground(CustomColors.DARK_GRAY);
        paginator.getStatusText().setFont(FontManager.getRunescapeSmallFont());
        paginator.setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, ColorScheme.DARKER_GRAY_COLOR.darker()));
        return paginator;
    }

    private void putPanelsOnBackgroundPanel(List<JPanel> panels, JPanel backgroundPanel, Paginator paginator) {
        List<JPanel> panelsAndPaginator = new ArrayList<>();
        JPanel paginatorWrapper = new JPanel();
        paginatorWrapper.add(paginator);
        panelsAndPaginator.add(paginatorWrapper);
        panelsAndPaginator.addAll(panels);
        backgroundPanel.removeAll();
        UIUtilities.stackPanelsVertically(panelsAndPaginator, backgroundPanel, 2);
        if (panels.isEmpty()) {
            //if i don't wrap the label, the box layout places it weird....
            JPanel labelWrapper = new JPanel();
            JLabel noDataLabel = new JLabel("Nothing here...", SwingConstants.CENTER);
            noDataLabel.setForeground(CustomColors.TOMATO);
            noDataLabel.setFont(new Font("Whitney", Font.PLAIN, 10));
            labelWrapper.add(noDataLabel);
            backgroundPanel.add(labelWrapper);
        }
        repaint();
        revalidate();
    }

    private List<RecipeFlipPanel> createRecipeFlipPanels(List<RecipeFlip> recipeFlips) {
        List<RecipeFlip> flipsCopy = new ArrayList<>(recipeFlips);
        Collections.reverse(flipsCopy);
        List<RecipeFlip> flipsOnCurrentPage = recipeFlipPaginator.getCurrentPageItems(flipsCopy);
        return flipsOnCurrentPage.stream().map(rf -> new RecipeFlipPanel(rf, recipeFlipGroup.getRecipe(), plugin)).collect(Collectors.toList());
    }

    private void putRecipeFlipPanelsOnBackgroundPanel(List<RecipeFlip> flips) {
        recipeFlipPanels = createRecipeFlipPanels(flips);
        putPanelsOnBackgroundPanel(new ArrayList<>(recipeFlipPanels), recipeFlipsBackgroundPanel, recipeFlipPaginator);
    }

    private JPanel createTitlePanel(JPanel itemIconPanel, JPanel nameAndProfitPanel, JLabel collapseIcon, JPanel subInfoAndHistoryContainer) {
        JPanel titlePanel = new JPanel(new BorderLayout());
        titlePanel.setBackground(CustomColors.DARK_GRAY);
        titlePanel.setBorder(new EmptyBorder(5, 4, 5, 4));

        titlePanel.add(itemIconPanel, BorderLayout.WEST);
        titlePanel.add(nameAndProfitPanel, BorderLayout.CENTER);
        titlePanel.add(collapseIcon, BorderLayout.EAST);

        titlePanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    if (subInfoAndHistoryContainer.isVisible()) {
                        collapseIconTitleLabel.setIcon(Icons.CLOSE_ICON);
                        subInfoAndHistoryContainer.setVisible(false);
                        statsPanel.getExpandedItems().remove(recipeFlipGroup.getItemName());
                    } else {
                        collapseIconTitleLabel.setIcon(Icons.OPEN_ICON);
                        subInfoAndHistoryContainer.setVisible(true);
                        statsPanel.getExpandedItems().add(recipeFlipGroup.getItemName());
                    }
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                titlePanel.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
                nameAndProfitPanel.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
                for (Component component : nameAndProfitPanel.getComponents()) {
                    component.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
                }
                itemIconTitlePanel.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                titlePanel.setBackground(CustomColors.DARK_GRAY);
                nameAndProfitPanel.setBackground(CustomColors.DARK_GRAY);
                for (Component component : nameAndProfitPanel.getComponents()) {
                    component.setBackground(CustomColors.DARK_GRAY);
                }
                itemIconTitlePanel.setBackground(CustomColors.DARK_GRAY);
            }
        });

        return titlePanel;
    }

    private JPanel createSubInfoPanel(JLabel[] descriptionLabels, JLabel[] valueLabels) {
        JPanel subInfoContainer = new JPanel();
        subInfoContainer.setBackground(CustomColors.DARK_GRAY_LIGHTER);
        subInfoContainer.setLayout(new DynamicGridLayout(valueLabels.length, descriptionLabels.length));
        subInfoContainer.setBorder(new EmptyBorder(10, 6, 6, 6));

        for (int i = 0; i < descriptionLabels.length; i++) {
            JLabel textLabel = descriptionLabels[i];
            JLabel valLabel = valueLabels[i];
            JPanel panel = new JPanel(new BorderLayout());

            panel.add(textLabel, BorderLayout.WEST);
            panel.add(valLabel, BorderLayout.EAST);

            panel.setBorder(new EmptyBorder(4, 2, 4, 2));
            panel.setBackground(CustomColors.DARK_GRAY_LIGHTER);

            textLabel.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);

            textLabel.setFont(FontManager.getRunescapeSmallFont());
            valLabel.setFont(FontManager.getRunescapeSmallFont());

            subInfoContainer.add(panel);
        }

        return subInfoContainer;
    }

    private JPanel createTradeHistoryPanel(JPanel recipeFlipsBackgroundPanel) {
        JPanel recipeFlipHistoryTitlePanel = new JPanel(new BorderLayout());
        recipeFlipHistoryTitlePanel.setBackground(CustomColors.DARK_GRAY);
        recipeFlipHistoryTitlePanel.setBorder(new EmptyBorder(4, 0, 4, 0));

        JLabel collapseTradeHistoryIconLabel = new JLabel(Icons.OPEN_ICON);
        JLabel recipeFlipHistoryTitleLabel = new JLabel("View Recipe Flips", SwingConstants.CENTER);
        recipeFlipHistoryTitleLabel.setFont(new Font("Whitney", Font.ITALIC, 10));
        recipeFlipHistoryTitlePanel.add(recipeFlipHistoryTitleLabel, BorderLayout.CENTER);
        recipeFlipHistoryTitlePanel.add(collapseTradeHistoryIconLabel, BorderLayout.EAST);
        recipeFlipHistoryTitlePanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    if (recipeFlipsBackgroundPanel.isVisible()) {
                        collapseTradeHistoryIconLabel.setIcon(Icons.CLOSE_ICON);
                    } else {

                        collapseTradeHistoryIconLabel.setIcon(Icons.OPEN_ICON);
                    }
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                recipeFlipHistoryTitlePanel.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                recipeFlipHistoryTitlePanel.setBackground(CustomColors.DARK_GRAY);
            }
        });

        JPanel tradeHistoryPanel = new JPanel(new BorderLayout());
        tradeHistoryPanel.add(recipeFlipHistoryTitlePanel, BorderLayout.NORTH);
        tradeHistoryPanel.add(recipeFlipsBackgroundPanel, BorderLayout.CENTER);

        return tradeHistoryPanel;
    }

    /**
     * Creates icon panel that contains the item image and the delete icon which shows when
     * you hover over the item image.
     */
    private JPanel createIconPanel(ItemManager itemManager) {
        JLabel deleteLabel = new JLabel(Icons.DELETE_ICON);
        deleteLabel.setPreferredSize(new Dimension(24, 24));
        deleteLabel.setVisible(false);

        AsyncBufferedImage itemImage = itemManager.getImage(recipeFlipGroup.getItemId());
        JLabel itemLabel = new JLabel();
        Runnable resize = () ->
        {
            BufferedImage subIcon = itemImage.getSubimage(0, 0, 32, 32);
            ImageIcon itemIcon = new ImageIcon(subIcon.getScaledInstance(24, 24, Image.SCALE_SMOOTH));
            itemLabel.setIcon(itemIcon);
        };
        itemImage.onLoaded(resize);
        resize.run();

        itemIconTitlePanel.add(itemLabel, BorderLayout.WEST);
        itemIconTitlePanel.add(deleteLabel, BorderLayout.EAST);
        itemIconTitlePanel.setBackground(CustomColors.DARK_GRAY);
        itemIconTitlePanel.setBorder(new EmptyBorder(5, 2, 0, 5));
        itemIconTitlePanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int result = JOptionPane.showOptionDialog(itemIconTitlePanel, "Are you sure you want to delete this item's offers from this time interval?",
                    "Are you sure?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
                    null, new String[]{"Yes", "No"}, "No");

                if (result == JOptionPane.YES_OPTION) {
                    deletePanel();
                    statsPanel.rebuild(plugin.viewTradesForCurrentView());
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                itemLabel.setVisible(false);
                deleteLabel.setVisible(true);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                itemLabel.setVisible(true);
                deleteLabel.setVisible(false);
            }
        });

        return itemIconTitlePanel;
    }

    private JPanel createNameAndProfitPanel() {
        JPanel nameAndProfitPanel = new JPanel(new BorderLayout());
        nameAndProfitPanel.setBackground(CustomColors.DARK_GRAY);
        JLabel itemNameLabel = new JLabel(recipeFlipGroup.getItemName());
        nameAndProfitPanel.add(itemNameLabel, BorderLayout.NORTH);
        nameAndProfitPanel.add(recipeProfitAndQuantityLabel, BorderLayout.SOUTH);
        nameAndProfitPanel.setPreferredSize(new Dimension(0, 0));
        return nameAndProfitPanel;
    }

    private JLabel createCollapseIcon() {
        JLabel collapseIconLabel = new JLabel();
        collapseIconLabel.setIcon(statsPanel.getExpandedItems().contains(recipeFlipGroup.getItemName()) ? Icons.OPEN_ICON : Icons.CLOSE_ICON);
        collapseIconLabel.setBorder(new EmptyBorder(2, 2, 2, 2));
        return collapseIconLabel;
    }

    public void updateLabels(List<RecipeFlip> recipeFlips) {
        quantityFlipped.setForeground(ColorScheme.LIGHT_GRAY_COLOR);


        int itemCountFlipped = FlippingItem.countFlipQuantity(adjustedOffers);
        long revenueFromFlippedItems = FlippingItem.getValueOfMatchedOffers(adjustedOffers, false);
        long expenseFromFlippedItems = FlippingItem.getValueOfMatchedOffers(adjustedOffers, true);
        long profit = revenueFromFlippedItems - expenseFromFlippedItems;

        updateTitleLabels(profit, itemCountFlipped);
        updateFlippingLabels(expenseFromFlippedItems, revenueFromFlippedItems, itemCountFlipped);
        updateTimeLabels();
    }

    /**
     * Updates the labels on the title panel. This includes the profit label which shows how much profit you made
     * from flipping that item and the number of times you flipped that item.
     */
    private void updateTitleLabels(long profitFromFlips, long numItemsFlipped) {
        String totalProfitString = (profitFromFlips >= 0 ? "+" : "") + UIUtilities.quantityToRSDecimalStack(profitFromFlips, true) + " gp";
        totalProfitString += " (x " + QuantityFormatter.formatNumber(numItemsFlipped) + ")";

        recipeProfitAndQuantityLabel.setText(totalProfitString);
        recipeProfitAndQuantityLabel.setForeground((profitFromFlips >= 0) ? ColorScheme.GRAND_EXCHANGE_PRICE : CustomColors.OUTDATED_COLOR);
        recipeProfitAndQuantityLabel.setBorder(new EmptyBorder(0, 0, 2, 0));
        recipeProfitAndQuantityLabel.setFont(FontManager.getRunescapeSmallFont());
    }

    private void updateFlippingLabels(long flippingExpense, long flippingRevenue, int itemsFlipped) {
        long profitFromFlips = flippingRevenue - flippingExpense;
        totalProfitValLabel.setText(UIUtilities.quantityToRSDecimalStack(profitFromFlips, true) + " gp");
        totalProfitValLabel.setForeground((profitFromFlips >= 0) ? ColorScheme.GRAND_EXCHANGE_PRICE : CustomColors.OUTDATED_COLOR);
        totalProfitValLabel.setToolTipText(QuantityFormatter.formatNumber(profitFromFlips) + " gp");

        String profitEach = UIUtilities.quantityToRSDecimalStack(itemsFlipped > 0 ? (profitFromFlips / itemsFlipped) : 0, true) + " gp/ea";
        profitEachValLabel.setText(profitEach);
        profitEachValLabel.setForeground((profitFromFlips >= 0) ? ColorScheme.GRAND_EXCHANGE_PRICE : CustomColors.OUTDATED_COLOR);
        profitEachValLabel.setToolTipText(QuantityFormatter.formatNumber(itemsFlipped > 0 ? profitFromFlips / itemsFlipped : 0) + " gp/ea");

        quantityFlipped.setText(QuantityFormatter.formatNumber(itemsFlipped) + " Items");

        float roi = (float) flippingExpense > 0 ? (float) profitFromFlips / flippingExpense * 100 : 0;

        roiValLabel.setText(String.format("%.2f", roi) + "%");
        roiValLabel.setForeground(UIUtilities.gradiatePercentage(roi, plugin.getConfig().roiGradientMax()));
        roiValLabel.setToolTipText("<html>Return on investment:<br>Percentage of profit relative to gp invested</html>");
    }


    public void updateTimeLabels() {
        recipeFlipPanels.forEach(RecipeFlipPanel::updateTimeLabels);
    }

    private void deletePanel() {
        statsPanel.deletePanel(this);
    }
}
