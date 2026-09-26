package com.flippingutilities.ui.flipping;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.FlippingItem;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;

/** Adds chart links to the real price widgets only while running the developer sandbox. */
public final class SandboxFlippingPanel extends FlippingPanel {
    private final Consumer<FlippingItem> openChart;

    public SandboxFlippingPanel(FlippingPlugin plugin, Consumer<FlippingItem> openChart) {
        super(plugin);
        this.openChart = openChart;
    }

    @Override public void rebuild(List<FlippingItem> items) {
        super.rebuild(items);
        // Production rebuild queues its component construction on Swing.
        SwingUtilities.invokeLater(() -> linkPrices(this));
    }

    private void linkPrices(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof FlippingItemPanel) {
                FlippingItemPanel item = (FlippingItemPanel) child;
                for (JLabel label : new JLabel[]{item.wikiBuyVal, item.wikiSellVal}) {
                    if (label.getClientProperty("sandbox-chart") != null) continue;
                    label.putClientProperty("sandbox-chart", true);
                    label.setName("Wiki price chart " + item.getFlippingItem().getItemId());
                    label.setToolTipText("Click to open the Wiki price chart");
                    label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    label.addMouseListener(new MouseAdapter() {
                        @Override public void mouseClicked(MouseEvent event) {
                            if (SwingUtilities.isLeftMouseButton(event)) openChart.accept(item.getFlippingItem());
                        }
                    });
                }
            } else if (child instanceof Container) linkPrices((Container) child);
        }
    }
}
