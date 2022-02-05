package com.flippingutilities.ui.statistics.recipes;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.*;
import com.flippingutilities.ui.statistics.StatsPanel;
import com.flippingutilities.ui.uiutilities.CustomColors;
import com.flippingutilities.ui.uiutilities.Icons;
import com.flippingutilities.ui.uiutilities.Paginator;
import com.flippingutilities.ui.uiutilities.UIUtilities;
import com.flippingutilities.utilities.Recipe;
import lombok.Getter;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.QuantityFormatter;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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

    private JLabel totalProfitValLabel = new JLabel("", SwingConstants.RIGHT);
    private JLabel profitEachValLabel = new JLabel("", SwingConstants.RIGHT);
    private JLabel quantityFlipped = new JLabel("", SwingConstants.RIGHT);
    private JLabel roiValLabel = new JLabel("", SwingConstants.RIGHT);

    private List<RecipeFlipPanel> recipeFlipPanels;
    private Paginator recipeFlipPaginator;
    private JPanel recipeFlipsBackgroundPanel = createRecipeFlipsBackgroundPanel();


    RecipeFlipGroupPanel(FlippingPlugin plugin, RecipeFlipGroup recipeFlipGroup) {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, ColorScheme.DARKER_GRAY_COLOR.darker()));

        this.plugin = plugin;
        this.recipeFlipGroup = recipeFlipGroup;
        this.statsPanel = plugin.getStatPanel();

        List<RecipeFlip> flips = recipeFlipGroup.getFlipsInInterval(statsPanel.getStartOfInterval());

        this.recipeFlipPaginator = createPaginator(() -> updateBackgroundPanel(flips));
        recipeFlipPaginator.updateTotalPages(flips.size());

        recipeFlipPanels = createRecipeFlipPanels(flips);
        putPanelsOnBackgroundPanel(new ArrayList<>(recipeFlipPanels), recipeFlipsBackgroundPanel, recipeFlipPaginator);

        JLabel[] descriptionLabels = {new JLabel("Total Profit: "), new JLabel("Avg. Profit ea: "), new JLabel("Avg. ROI: "), new JLabel("Quantity Flipped: ")};

        JLabel[] valueLabels = {totalProfitValLabel, profitEachValLabel, roiValLabel, quantityFlipped};

        JPanel subInfoPanel = createSubInfoPanel(descriptionLabels, valueLabels);
        JPanel tradeHistoryPanel = createTradeHistoryPanel(recipeFlipsBackgroundPanel);
        JPanel subInfoAndHistoryContainer = createSubInfoAndHistoryContainer(subInfoPanel, tradeHistoryPanel);
        JPanel titlePanel = createTitlePanel(createIconPanel(plugin.getItemManager()), createNameAndProfitPanel(), createCollapseIcon(), subInfoAndHistoryContainer);

        updateLabels(flips);

        add(titlePanel, BorderLayout.NORTH);
        add(subInfoAndHistoryContainer, BorderLayout.CENTER);
    }

    private JPanel createSubInfoAndHistoryContainer(JPanel subInfoPanel, JPanel tradeHistoryPanel) {
        JPanel subInfoAndHistoryContainer = new JPanel(new BorderLayout());
        //Set background and border of container with sub infos and trade history
        subInfoAndHistoryContainer.setBackground(CustomColors.DARK_GRAY_LIGHTER);
        subInfoAndHistoryContainer.add(subInfoPanel, BorderLayout.CENTER);
        subInfoAndHistoryContainer.add(tradeHistoryPanel, BorderLayout.SOUTH);
        subInfoAndHistoryContainer.setVisible(false);
        return subInfoAndHistoryContainer;
    }

    private JPanel createRecipeFlipsBackgroundPanel() {
        JPanel recipeFlipsBackgroundPanel = new JPanel();
        recipeFlipsBackgroundPanel.setVisible(false);
        return recipeFlipsBackgroundPanel;
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
        return flipsOnCurrentPage.stream().map(rf -> new RecipeFlipPanel(recipeFlipGroup, rf, recipeFlipGroup.getRecipe(), plugin)).collect(Collectors.toList());
    }

    private void updateBackgroundPanel(List<RecipeFlip> flips) {
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
                        collapseIcon.setIcon(Icons.CLOSE_ICON);
                        subInfoAndHistoryContainer.setVisible(false);
                    } else {
                        collapseIcon.setIcon(Icons.OPEN_ICON);
                        subInfoAndHistoryContainer.setVisible(true);
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
                        recipeFlipsBackgroundPanel.setVisible(false);
                        collapseTradeHistoryIconLabel.setIcon(Icons.CLOSE_ICON);
                    } else {
                        recipeFlipsBackgroundPanel.setVisible(true);
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

        JLabel itemLabel = new JLabel(Icons.CONSTRUCTION);

        itemIconTitlePanel.add(itemLabel, BorderLayout.WEST);
        itemIconTitlePanel.add(deleteLabel, BorderLayout.EAST);
        itemIconTitlePanel.setBackground(CustomColors.DARK_GRAY);
        itemIconTitlePanel.setBorder(new EmptyBorder(5, 2, 0, 5));
        itemIconTitlePanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (plugin.getAccountCurrentlyViewed().equals(FlippingPlugin.ACCOUNT_WIDE)) {
                    JOptionPane.showMessageDialog(null, "You cannot delete recipe flips in the Accountwide view");
                    return;
                }
                int result = JOptionPane.showOptionDialog(itemIconTitlePanel, "Are you sure you want to delete this recipe's flips from this time interval?",
                    "Are you sure?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
                    null, new String[]{"Yes", "No"}, "No");

                if (result == JOptionPane.YES_OPTION) {
                    deletePanel();
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
        JLabel itemNameLabel = new JLabel(recipeFlipGroup.getRecipe().getName());
        nameAndProfitPanel.add(itemNameLabel, BorderLayout.NORTH);
        nameAndProfitPanel.add(recipeProfitAndQuantityLabel, BorderLayout.SOUTH);
        nameAndProfitPanel.setPreferredSize(new Dimension(0, 0));
        return nameAndProfitPanel;
    }

    private JLabel createCollapseIcon() {
        JLabel collapseIconLabel = new JLabel();
        collapseIconLabel.setIcon(Icons.OPEN_ICON);
        collapseIconLabel.setBorder(new EmptyBorder(2, 2, 2, 2));
        return collapseIconLabel;
    }

    public void updateLabels(List<RecipeFlip> recipeFlips) {
        quantityFlipped.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

        Recipe recipe = recipeFlipGroup.getRecipe();

        int recipesMade = recipeFlips.stream().mapToInt(rf -> rf.getRecipeCountMade(recipe)).sum();
        long revenue = recipeFlips.stream().mapToLong(RecipeFlip::getRevenue).sum();
        long expense = recipeFlips.stream().mapToLong(RecipeFlip::getExpense).sum();
        long profit = revenue - expense;

        updateTitleLabels(profit, recipesMade);
        updateFlippingLabels(expense, revenue, recipesMade);
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
        statsPanel.deleteRecipeFlipGroupPanel(this);
    }
}
