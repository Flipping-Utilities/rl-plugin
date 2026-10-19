package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.utilities.MathUtils;
import com.flippingutilities.utilities.SlotInfo;
import com.flippingutilities.utilities.SlotPredictedState;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.laf.RuneLiteLAF;
import net.runelite.client.util.QuantityFormatter;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
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
            canvas.setSize(700, 660);
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
            label(canvas, "Item stats labels, billion-quantity flip (avg profit ea rounds, not truncates)", 20, 390, 660);
            canvas.add(itemStatsFixture());
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

    /**
     * Renders the stats labels of an item panel with the same formatting calls
     * {@code FlippingItemPanel.updateFlippingLabels}/{@code updateGeneralLabels} use,
     * fed an offer list shaped like a reported miscalculation: billions bought/sold at
     * 4-5 gp with only ~25m matched into flips. Avg. profit ea must round (2 gp/ea from
     * 1.88) instead of truncating to 1, and the bought/sold quantities must carry
     * thousands separators.
     */
    private static JComponent itemStatsFixture() {
        long totalItemsBought = 2_352_971_263L;
        long totalItemsSold = 1_044_505_776L;
        long totalExpense = 9_411_885_052L;
        long totalRevenue = 5_222_528_880L;
        long itemsFlipped = 24_999_927L;
        long flippingExpense = 100_000_000L;
        long flippingRevenue = 147_031_100L;
        long profit = flippingRevenue - flippingExpense;
        float roi = (float) flippingExpense > 0 ? (float) profit / flippingExpense * 100 : 0;

        JPanel stats = new JPanel(new DynamicGridLayout(8, 1));
        stats.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        stats.setBounds(20, 415, 340, 230);
        stats.setBorder(new EmptyBorder(6, 6, 6, 6));
        statRow(stats, "Total Profit:", UIUtilities.quantityToRSDecimalStack(profit, true) + " gp");
        statRow(stats, "Avg. Profit ea:", UIUtilities.quantityToRSDecimalStack(
            itemsFlipped > 0 ? MathUtils.roundedQuotient(profit, itemsFlipped) : 0, true) + " gp/ea");
        statRow(stats, "Avg. ROI:", String.format("%.2f", roi) + "%");
        statRow(stats, "Quantity Flipped:", QuantityFormatter.formatNumber(itemsFlipped) + " Items");
        statRow(stats, "Quantity Bought:", QuantityFormatter.formatNumber(totalItemsBought));
        statRow(stats, "Quantity Sold:", QuantityFormatter.formatNumber(totalItemsSold));
        statRow(stats, "Avg. Buy Price:", QuantityFormatter.formatNumber(
            totalItemsBought > 0 ? totalExpense / totalItemsBought : 0) + " gp");
        statRow(stats, "Avg. Sell Price:", QuantityFormatter.formatNumber(
            totalItemsSold > 0 ? totalRevenue / totalItemsSold : 0) + " gp");
        return stats;
    }

    private static void statRow(JPanel container, String description, String value) {
        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setBorder(new EmptyBorder(4, 2, 4, 2));
        JLabel textLabel = new JLabel(description);
        JLabel valueLabel = new JLabel(value, SwingConstants.RIGHT);
        for (JLabel label : new JLabel[]{textLabel, valueLabel}) {
            label.setFont(FontManager.getRunescapeSmallFont());
            label.setForeground(label == textLabel
                ? ColorScheme.GRAND_EXCHANGE_ALCH
                : ColorScheme.LIGHT_GRAY_COLOR);
        }
        row.add(textLabel, BorderLayout.WEST);
        row.add(valueLabel, BorderLayout.EAST);
        container.add(row);
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
