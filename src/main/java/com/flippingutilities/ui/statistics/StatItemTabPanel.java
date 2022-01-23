package com.flippingutilities.ui.statistics;

import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.ui.uiutilities.Paginator;
import com.flippingutilities.ui.uiutilities.UIUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class StatItemTabPanel extends JPanel {

    private JPanel statItemPanelsContainer;
    private ArrayList<StatItemPanel> activePanels = new ArrayList<>();
    private Paginator paginator;

    public StatItemTabPanel() {
        statItemPanelsContainer = createStatItemsPanelContainer();
    }


    private void rebuild(List<FlippingItem> flippingItems) {
        activePanels.clear();
        statItemPanelsContainer.removeAll();
        paginator.updateTotalPages(flippingItems.size());
        List<FlippingItem> itemsOnCurrentPage = paginator.getCurrentPageItems(flippingItems);
        List<StatItemPanel> newPanels = itemsOnCurrentPage.stream().map(item -> new StatItemPanel(plugin, itemManager, item)).collect(Collectors.toList());
        UIUtilities.stackPanelsVertically((List) newPanels, statItemPanelsContainer, 5);
        activePanels.addAll(newPanels);
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
            //we only want to rebuildStatItemContainer and not updateDisplays bc the display
            //is built based on every item on every page and so it shouldn't change if you
            //switch pages.
            Instant rebuildStart = Instant.now();
            rebuild(getResultsForCurrentSearchQuery(plugin.viewTradesForCurrentView()));
            revalidate();
            repaint();
            log.debug("page change took {}", Duration.between(rebuildStart, Instant.now()).toMillis());
        }));
        paginator.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
        paginator.setBorder(new EmptyBorder(0, 0, 0, 10));
        return paginator;
    }
}
