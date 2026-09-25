package com.flippingutilities.ui.accounting;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

final class AccountingUi {
    private AccountingUi() {}

    private static final class Column extends JPanel implements Scrollable {
        @Override public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction) { return 12; }
        @Override public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return Math.max(12, visible.height - 12);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    static JPanel column() {
        JPanel panel = new Column();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    static JPanel card() {
        JPanel panel = column();
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
            BorderFactory.createEmptyBorder(7, 5, 7, 5)));
        return panel;
    }

    static JTextArea text(String value) {
        JTextArea text = new JTextArea(value) {
            @Override public Dimension getPreferredSize() {
                int width = getParent() == null || getParent().getWidth() <= 0 ? 215
                    : getParent().getWidth() - getParent().getInsets().left - getParent().getInsets().right;
                setSize(Math.max(40, width), Short.MAX_VALUE);
                Dimension preferred = super.getPreferredSize();
                return new Dimension(Math.max(40, width), preferred.height);
            }
            @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }
            @Override public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        text.setFont(FontManager.getRunescapeSmallFont());
        text.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.setBorder(BorderFactory.createEmptyBorder(2, 0, 3, 0));
        return text;
    }

    static JLabel title(String value) {
        JLabel title = new JLabel(value);
        title.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
        title.setFont(FontManager.getRunescapeSmallFont());
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        return title;
    }

    static JPanel field(String label, Component control) {
        JPanel panel = new JPanel(new BorderLayout(0, 3)) {
            @Override public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.add(title(label), BorderLayout.NORTH);
        panel.add(control, BorderLayout.CENTER);
        panel.setBorder(BorderFactory.createEmptyBorder(3, 0, 5, 0));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    static String money(AccountingUiService.Money value) {
        if (value.completeGp == null) {
            return "Unknown (" + (value.estimated ? "estimated " : "") + "known subtotal: " + number(value.knownSubtotalGp) + " gp; "
                + number(value.unknownCount) + " incomplete)";
        }
        return number(value.completeGp) + " gp" + (value.estimated ? " (estimated)" : "");
    }

    static String number(long value) { return String.format(java.util.Locale.ROOT, "%,d", value); }

    static JPanel amounts(AccountingUiService.Amounts amounts) {
        JPanel panel = column();
        panel.add(text("Profit: " + money(amounts.profit)));
        panel.add(text("Cost: " + money(amounts.cost)));
        panel.add(text("Proceeds: " + money(amounts.net)));
        panel.add(text("Tax: " + money(amounts.tax)));
        panel.add(text("Return on investment: " + roi(amounts)));
        return panel;
    }

    static String roi(AccountingUiService.Amounts amounts) {
        if (amounts.profit.completeGp == null || amounts.cost.completeGp == null) return "Unknown";
        if (amounts.cost.completeGp <= 0) return "N/A (no positive basis)";
        return decimal(BigDecimal.valueOf(amounts.profit.completeGp).multiply(BigDecimal.valueOf(100)),
            amounts.cost.completeGp) + "%" + (amounts.profit.estimated || amounts.cost.estimated ? " (estimated)" : "");
    }

    static String perUnit(AccountingUiService.Money amount, long quantity) {
        if (amount.completeGp == null) return "Unknown";
        if (quantity <= 0) return "N/A";
        return decimal(BigDecimal.valueOf(amount.completeGp), quantity) + " gp" + (amount.estimated ? " (estimated)" : "");
    }

    private static String decimal(BigDecimal numerator, long denominator) {
        return numerator.divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    static JPanel segment(AccountingUiService.Segment segment) {
        JPanel panel = card();
        panel.add(title(segment.methodLabel));
        panel.add(text(segment.accountLabel + " · " + segment.periodLabel));
        panel.add(amounts(segment.amounts));
        panel.add(text(number(segment.flipCount) + " flips · " + number(segment.soldQuantity)
            + " sold · " + number(segment.unknownQuantity) + " with unknown basis"));
        if (segment.sessionMillis != null && segment.sessionMillis > 0) {
            long seconds = segment.sessionMillis / 1000;
            panel.add(text("Tracked time: " + (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m " + (seconds % 60) + "s"));
            panel.add(text("Profit per tracked hour: " + (segment.amounts.profit.completeGp == null ? "Unknown"
                : decimal(BigDecimal.valueOf(segment.amounts.profit.completeGp).multiply(BigDecimal.valueOf(3_600_000)),
                    segment.sessionMillis) + " gp" + (segment.amounts.profit.estimated ? " (estimated)" : ""))));
        }
        if (segment.activity != null) {
            panel.add(title("All-item trade activity"));
            panel.add(text(number(segment.activity.boughtQuantity) + " bought · " + number(segment.activity.soldQuantity) + " sold"));
            panel.add(text("Purchases: " + money(segment.activity.spent)));
            panel.add(text("Sales: " + money(segment.activity.proceeds)));
            panel.add(text("Activity tax: " + money(segment.activity.tax)));
        }
        return panel;
    }

    static JPanel inventorySegment(AccountingUiService.Segment segment) {
        JPanel panel = card();
        panel.add(title("Current tracked inventory"));
        panel.add(text(segment.accountLabel + " · " + segment.methodLabel));
        panel.add(text("Tracked cost: " + money(segment.amounts.cost)));
        panel.add(text("Unsold purchases retained by this accounting plan. Tracked quantities may differ from stock you still own."));
        return panel;
    }

    static String formatDate(Instant instant, ZoneId zone) {
        return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(instant.atZone(zone).toLocalDateTime());
    }

    static Instant parseDate(String value, ZoneId zone) {
        LocalDateTime date = LocalDateTime.parse(value.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(date);
        if (offsets.size() != 1) {
            throw new IllegalArgumentException("This local time is skipped or repeated by daylight saving. Use UTC for this time.");
        }
        return date.toInstant(offsets.get(0));
    }

    static <T> void request(Executor executor, Supplier<CompletableFuture<T>> work,
                            BiConsumer<T, Throwable> completed) {
        CompletableFuture.supplyAsync(work, executor).thenCompose(future -> future)
            .whenComplete((value, failure) -> SwingUtilities.invokeLater(() -> completed.accept(value, failure)));
    }

    static Throwable cause(Throwable error) {
        while ((error instanceof java.util.concurrent.CompletionException
            || error instanceof java.util.concurrent.ExecutionException) && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    static String failureMessage(Throwable error) {
        Throwable underlying = cause(error);
        String message = underlying.getMessage();
        return message == null || message.trim().isEmpty() ? "The operation failed (" + underlying.getClass().getSimpleName() + ")."
            : message;
    }

    static void status(JTextArea status, String value, boolean error) {
        status.setText(value);
        status.setForeground(error ? new Color(250, 100, 100) : ColorScheme.LIGHT_GRAY_COLOR);
    }
}
