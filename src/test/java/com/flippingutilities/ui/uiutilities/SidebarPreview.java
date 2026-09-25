package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.utilities.SlotInfo;
import com.flippingutilities.utilities.SlotPredictedState;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.laf.RuneLiteLAF;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.image.BufferedImage;
import java.io.File;

/** Run on the test classpath to render deterministic component fixtures without a game login. */
public class SidebarPreview {
    public static void main(String[] args) throws Exception {
        File output = new File(args.length == 0 ? "build/ui-preview/sidebar-states.png" : args[0]);
        SwingUtilities.invokeAndWait(() -> {
            RuneLiteLAF.setup();
            JPanel canvas = new JPanel(null);
            canvas.setBackground(ColorScheme.DARK_GRAY_COLOR);
            canvas.setSize(700, 400);
            label(canvas, "Paginator at 225 px", 20, 10, 225);
            label(canvas, "Paginator at 300 px", 310, 10, 300);
            for (int column = 0; column < 2; column++) {
                int x = column == 0 ? 20 : 310;
                int width = column == 0 ? 225 : 300;
                for (int row = 0; row < 3; row++) {
                    Paginator paginator = new Paginator(() -> {});
                    paginator.updateTotalPages(row == 2 ? 0 : 60);
                    paginator.setPageNumber(row == 1 ? 3 : 1);
                    paginator.setBounds(x, 40 + row * 35, width, 28);
                    canvas.add(paginator);
                    if (row == 0) {
                        // Render the same border used by a real focus event, without opening a window.
                        JButton next = (JButton) paginator.getComponent(4);
                        for (FocusListener listener : next.getFocusListeners()) {
                            listener.focusGained(new FocusEvent(next, FocusEvent.FOCUS_GAINED));
                        }
                    }
                }
            }
            label(canvas, "Equal buy/sell prices", 20, 158, 320);
            label(canvas, "Same offer after data becomes unavailable", 365, 158, 320);
            SlotInfo slot = new SlotInfo(0, SlotPredictedState.BETTER_THAN_WIKI, 1, 100, true, false);
            for (int column = 0; column < 2; column++) {
                QuickLookPanel panel = new QuickLookPanel();
                panel.updateDetails(slot, QuickLookPanelTest.margins(100, 100));
                if (column == 1) {
                    panel.updateDetails(slot, null);
                }
                panel.setBounds(20 + column * 345, 185, 320, 195);
                canvas.add(panel);
            }
            layoutTree(canvas);
            BufferedImage image = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            canvas.printAll(graphics);
            graphics.dispose();
            try {
                output.getAbsoluteFile().getParentFile().mkdirs();
                ImageIO.write(image, "png", output);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void label(JPanel canvas, String text, int x, int y, int width) {
        JLabel label = new JLabel(text);
        label.setForeground(Color.WHITE);
        label.setBounds(x, y, width, 25);
        canvas.add(label);
    }

    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container) {
                layoutTree((Container) child);
            }
        }
    }
}
