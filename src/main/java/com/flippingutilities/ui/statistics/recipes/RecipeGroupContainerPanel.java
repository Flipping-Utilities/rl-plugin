package com.flippingutilities.ui.statistics.recipes;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.RecipeFlipGroup;
import com.flippingutilities.ui.statistics.StatsPanel;
import com.flippingutilities.ui.statistics.recipes.customrecipes.CustomRecipeManagerPanel;
import com.flippingutilities.ui.uiutilities.Icons;
import com.flippingutilities.ui.uiutilities.Paginator;
import com.flippingutilities.ui.uiutilities.UIUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class RecipeGroupContainerPanel extends JPanel {
    private JPanel recipeGroupContainer;
    private List<RecipeFlipGroupPanel> activePanels = new ArrayList<>();
    private Paginator paginator;
    private FlippingPlugin plugin;

    public RecipeGroupContainerPanel(FlippingPlugin flippingPlugin) {
        plugin = flippingPlugin;
        recipeGroupContainer = createRecipeGroupContainer();
        paginator = createPaginator();

        JScrollPane scrollPane = createScrollPane(recipeGroupContainer);

        setLayout(new BorderLayout());

        add(createHeaderPanel(), BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(paginator, BorderLayout.SOUTH);
    }

    public void resetPaginator() {
        paginator.setPageNumber(1);
    }

    public void rebuild(List<RecipeFlipGroup> recipeFlipGroups) {
        activePanels.clear();
        recipeGroupContainer.removeAll();
        paginator.updateTotalPages(recipeFlipGroups.size());

        if (!recipeFlipGroups.isEmpty()) {
            List<RecipeFlipGroup> itemsOnCurrentPage = paginator.getCurrentPageItems(recipeFlipGroups);
            List<RecipeFlipGroupPanel> newPanels = itemsOnCurrentPage.stream().map(rfg -> new RecipeFlipGroupPanel(plugin, rfg)).collect(Collectors.toList());
            UIUtilities.stackPanelsVertically((List) newPanels, recipeGroupContainer, 5);
            activePanels.addAll(newPanels);
        }
        else {
            recipeGroupContainer.add(createHelpPanel());
        }
    }

    public void showPanel(JPanel panel) {
        activePanels.clear();
        recipeGroupContainer.removeAll();
        recipeGroupContainer.add(panel);
    }

    private JPanel createHelpPanel() {
        JLabel picDesc = new JLabel(
            "<html><body width='220' style='text-align:center;'>" +
                "Create a recipe flip by going to an offer for an item " +
                "and clicking on the recipe flip button.<br><br> ", SwingConstants.CENTER);
        picDesc.setFont(new Font("Whitney", Font.PLAIN, 15));
        picDesc.setIcon(Icons.RECIPE_HELP);
        picDesc.setBorder(new EmptyBorder(20,5,0,0));
        picDesc.setHorizontalTextPosition(JLabel.CENTER);
        picDesc.setVerticalTextPosition(JLabel.NORTH);

        JLabel additionalInfoLabel = new JLabel("<html><body width='220' style='text-align:center;'>" +
            "This button will only be there if that item has a recipe associated with it</b>.<br><br> ",
            SwingConstants.CENTER);
        additionalInfoLabel.setFont(new Font("Whitney", Font.PLAIN, 10));

        JLabel contactUsLabel = new JLabel("<html><body width='220' style='text-align:center;'>" +
            "If a recipe is missing, contact us on discord and we will add it!", SwingConstants.CENTER);
        contactUsLabel.setFont(new Font("Whitney", Font.ITALIC, 10));

        JPanel helpPanel = new JPanel(new DynamicGridLayout(3,1));
        helpPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
        helpPanel.add(picDesc);
        helpPanel.add(additionalInfoLabel);
        helpPanel.add(contactUsLabel);

        return helpPanel;
    }

    private JPanel createRecipeGroupContainer() {
        JPanel statItemPanelsContainer = new JPanel();
        statItemPanelsContainer.setLayout(new BoxLayout(statItemPanelsContainer, BoxLayout.Y_AXIS));
        return statItemPanelsContainer;
    }

    private JScrollPane createScrollPane(JPanel recipeGroupContainer) {
        JPanel statItemPanelsContainerWrapper = new JPanel(new BorderLayout());
        statItemPanelsContainerWrapper.setBorder(new EmptyBorder(0,0,0,3));
        statItemPanelsContainerWrapper.add(recipeGroupContainer, BorderLayout.NORTH);

        JScrollPane scrollPane = new JScrollPane(statItemPanelsContainerWrapper);
        scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
        scrollPane.setBorder(new EmptyBorder(5, 0, 0, 0));
        scrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(2, 0));

        return scrollPane;
    }

    private Paginator createPaginator() {
        paginator = new Paginator(() -> SwingUtilities.invokeLater(() -> {
            StatsPanel statsPanel = plugin.getStatPanel();
            Instant rebuildStart = Instant.now();
            rebuild(statsPanel.getRecipeFlipGroupsToDisplay(plugin.viewRecipeFlipGroupsForCurrentView()));
            revalidate();
            repaint();
            log.debug("page change took {}", Duration.between(rebuildStart, Instant.now()).toMillis());
        }));
        paginator.setBackground(ColorScheme.DARK_GRAY_COLOR);
        paginator.setBorder(new MatteBorder(1,0,0,0, ColorScheme.DARK_GRAY_COLOR.darker()));
        return paginator;
    }

    public void updateTimeDisplay() {
        activePanels.forEach(RecipeFlipGroupPanel::updateTimeLabels);
    }

    private JPanel createHeaderPanel() {
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        headerPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

        JButton manageRecipesButton = new JButton("Manage Custom Recipes");
        manageRecipesButton.setToolTipText("Create and manage your own custom recipes");
        manageRecipesButton.addActionListener(e -> openCustomRecipeManager());

        headerPanel.add(manageRecipesButton, BorderLayout.CENTER);

        return headerPanel;
    }

    private void openCustomRecipeManager() {
        CustomRecipeManagerPanel managerPanel = new CustomRecipeManagerPanel(plugin);

        JDialog dialog = new JDialog();
        dialog.setTitle("Custom Recipe Manager");
        dialog.setModal(true);
        dialog.add(managerPanel);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(730, 400));
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }
}

