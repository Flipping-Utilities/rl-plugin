package com.flippingutilities.ui.statistics;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.laf.RuneLiteLAF;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/** Run on the test classpath to render actual sidebar panels with controlled offline history. */
public final class SidebarConsistencyPreview {
    public static void main(String[] args) throws Exception {
        File directory = new File(args.length == 0 ? "build/ui-preview" : args[0]);
        directory.mkdirs();
        SwingUtilities.invokeAndWait(RuneLiteLAF::setup);
        for (String state : new String[]{"empty", "interval", "search"}) {
            try (StatsEmptyStateTest.Fixture narrow = new StatsEmptyStateTest.Fixture();
                 StatsEmptyStateTest.Fixture wide = new StatsEmptyStateTest.Fixture()) {
                for (StatsEmptyStateTest.Fixture fixture : new StatsEmptyStateTest.Fixture[]{narrow, wide}) {
                    if (state.equals("interval")) fixture.items = Collections.singletonList(StatsEmptyStateTest.oldItem());
                    fixture.rebuild();
                    if (state.equals("search")) {
                        SwingUtilities.invokeAndWait(() -> {
                            IconTextField search = StatsEmptyStateTest.find(fixture.panel, IconTextField.class, null);
                            search.setText("Unmatched item");
                            StatsEmptyStateTest.find(search, JTextField.class, null).postActionEvent();
                        });
                        fixture.executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
                        SwingUtilities.invokeAndWait(() -> {});
                        SwingUtilities.invokeAndWait(() -> {});
                    }
                }
                SwingUtilities.invokeAndWait(() -> {
                    JPanel board = new JPanel(null);
                    board.setBackground(ColorScheme.DARK_GRAY_COLOR);
                    board.setSize(585, 620);
                    int column = 0;
                    for (StatsEmptyStateTest.Fixture fixture : new StatsEmptyStateTest.Fixture[]{narrow, wide}) {
                        int width = column == 0 ? 225 : 300;
                        int x = column++ == 0 ? 15 : 260;
                        JLabel title = new JLabel(width + " px - " + state);
                        title.setBounds(x, 5, width, 25);
                        board.add(title);
                        fixture.panel.setBounds(x, 35, width, 565);
                        board.add(fixture.panel);
                    }
                    layoutTree(board);
                    BufferedImage image = new BufferedImage(board.getWidth(), board.getHeight(), BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = image.createGraphics();
                    board.printAll(graphics);
                    graphics.dispose();
                    try {
                        ImageIO.write(image, "png", new File(directory, "sidebar-" + state + ".png"));
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                });
            }
        }
    }

    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container) layoutTree((Container) child);
        }
    }
}
