package com.flippingutilities.ui.uiutilities;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.laf.RuneLiteLAF;

import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDate;

/** Run on the test classpath to render the custom range picker to a PNG. */
public final class DateRangePickerPreview {
    public static void main(String[] args) throws Exception {
        File directory = new File(args.length == 0 ? "build/ui-preview" : args[0]);
        directory.mkdirs();
        SwingUtilities.invokeAndWait(RuneLiteLAF::setup);
        SwingUtilities.invokeAndWait(() -> {
            JPanel board = new JPanel(null);
            board.setBackground(ColorScheme.DARK_GRAY_COLOR);
            board.setSize(268, 318);

            DateRangePicker picker = new DateRangePicker(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 20));
            picker.setBounds(15, 15, 238, 288);
            board.add(picker);

            layoutTree(board);
            try {
                BufferedImage image = new BufferedImage(board.getWidth() * 2, board.getHeight() * 2,
                    BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                graphics.scale(2, 2);
                board.printAll(graphics);
                graphics.dispose();
                ImageIO.write(image, "png", new File(directory, "custom-range-picker.png"));
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container) layoutTree((Container) child);
        }
    }
}
