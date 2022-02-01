package com.flippingutilities.ui.statistics.recipes;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.RecipeFlipGroup;
import com.flippingutilities.ui.statistics.StatsPanel;
import com.flippingutilities.ui.uiutilities.Paginator;
import com.flippingutilities.ui.uiutilities.UIUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;

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

        add(scrollPane, BorderLayout.CENTER);
        add(paginator, BorderLayout.SOUTH);
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
            recipeGroupContainer.add(createHelpLabel());
        }
    }

    public void showPanel(JPanel panel) {
        activePanels.clear();
        recipeGroupContainer.removeAll();
        recipeGroupContainer.add(panel);
    }

    private JLabel createHelpLabel() {
        JLabel helpLabel = new JLabel(
            "<html><body width='220' style='text-align:center;'>" +
                "You currently have no recipe flips for this interval. You can create a recipe flip by going to an offer for an item " +
                "and clicking on the recipe flip button.<br><br> " +
                "<b>That button will only be there if that item has a recipe associated with it</b>.<br><br> " +
                "If a recipe is missing, contact us on discord and we will add it!");
        helpLabel.setFont(new Font("Whitney", Font.PLAIN, 15));
        helpLabel.setBorder(new EmptyBorder(20,5,0,0));
        helpLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        return helpLabel;
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
}
