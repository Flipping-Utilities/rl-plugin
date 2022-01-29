package com.flippingutilities.ui.statistics.items;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.FlippingItem;
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
public class FlippingItemTabPanel extends JPanel {

    private JPanel flippingItemPanelsContainer;
    private List<FlippingItemPanel> activePanels = new ArrayList<>();
    private Paginator paginator;
    private FlippingPlugin plugin;

    public FlippingItemTabPanel(FlippingPlugin flippingPlugin) {
        plugin = flippingPlugin;
        flippingItemPanelsContainer = createStatItemsPanelContainer();
        paginator = createPaginator();

        JScrollPane scrollPane = createScrollPane(flippingItemPanelsContainer);

        setLayout(new BorderLayout());

        add(scrollPane, BorderLayout.CENTER);
        add(paginator, BorderLayout.SOUTH);
    }

    public void rebuild(List<FlippingItem> flippingItems) {
        activePanels.clear();
        flippingItemPanelsContainer.removeAll();

        paginator.updateTotalPages(flippingItems.size());

        List<FlippingItem> itemsOnCurrentPage = paginator.getCurrentPageItems(flippingItems);
        List<FlippingItemPanel> newPanels = itemsOnCurrentPage.stream().map(item -> new FlippingItemPanel(plugin, item)).collect(Collectors.toList());
        UIUtilities.stackPanelsVertically((List) newPanels, flippingItemPanelsContainer, 5);
        activePanels.addAll(newPanels);
    }

    public void showPanel(JPanel panel) {
        activePanels.clear();
        flippingItemPanelsContainer.removeAll();
        flippingItemPanelsContainer.add(panel);
    }

    private JPanel createStatItemsPanelContainer() {
        JPanel statItemPanelsContainer = new JPanel();
        statItemPanelsContainer.setLayout(new BoxLayout(statItemPanelsContainer, BoxLayout.Y_AXIS));
        return statItemPanelsContainer;
    }

    private JScrollPane createScrollPane(JPanel statItemPanelsContainer) {
        JPanel statItemPanelsContainerWrapper = new JPanel(new BorderLayout());
        statItemPanelsContainerWrapper.setBorder(new EmptyBorder(0,0,0,3));
        statItemPanelsContainerWrapper.add(statItemPanelsContainer, BorderLayout.NORTH);

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
            rebuild(statsPanel.getItemsToDisplay(plugin.viewTradesForCurrentView()));
            revalidate();
            repaint();
            log.debug("page change took {}", Duration.between(rebuildStart, Instant.now()).toMillis());
        }));
        paginator.setBackground(ColorScheme.DARK_GRAY_COLOR);
        paginator.setBorder(new MatteBorder(1,0,0,0, ColorScheme.DARK_GRAY_COLOR.darker()));
        return paginator;
    }

    public void updateTimeDisplay() {
        activePanels.forEach(FlippingItemPanel::updateTimeLabels);
    }
}
