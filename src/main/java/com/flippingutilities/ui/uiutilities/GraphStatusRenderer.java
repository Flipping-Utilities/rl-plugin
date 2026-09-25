package com.flippingutilities.ui.uiutilities;

import net.runelite.client.ui.FontManager;

import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;

/** Shared status presentation for the offer overlay and quick-look tooltip. */
public final class GraphStatusRenderer {
    private final ChartLoadingAnimation loading = new ChartLoadingAnimation();

    public void render(Graphics2D original, Rectangle bounds, GraphLoadState state, boolean retryButton) {
        Graphics2D graphics = (Graphics2D) original.create();
        try {
            graphics.clip(bounds);
            if (state == GraphLoadState.LOADING) {
                loading.render(graphics, bounds, System.currentTimeMillis());
                return;
            }
            if (state == GraphLoadState.READY) {
                return;
            }
            int centerY = bounds.y + bounds.height / 2;
            graphics.setFont(FontManager.getRunescapeBoldFont());
            graphics.setColor(CustomColors.OFF_WHITE);
            centered(graphics, bounds, state == GraphLoadState.EMPTY
                ? "No price history yet" : "Price history unavailable", centerY - 22);
            graphics.setFont(FontManager.getRunescapeSmallFont());
            graphics.setColor(CustomColors.CHART_LABEL);
            centered(graphics, bounds, state == GraphLoadState.EMPTY
                ? "No prices were reported for this interval."
                : "Prices could not be loaded.", centerY - 3);
            if (state == GraphLoadState.EMPTY) {
                centered(graphics, bounds, "Try another interval or item.", centerY + 16);
            } else if (!retryButton) {
                centered(graphics, bounds, "Click the magnifier to retry.", centerY + 20);
            } else {
                Rectangle button = retryBounds(bounds);
                graphics.setColor(CustomColors.CHART_BUTTON_UNSELECTED_BG);
                graphics.fillRoundRect(button.x, button.y, button.width, button.height, 6, 6);
                graphics.setColor(CustomColors.CHART_BUTTON_SELECTED_BORDER);
                graphics.drawRoundRect(button.x, button.y, button.width, button.height, 6, 6);
                graphics.setColor(CustomColors.CHART_BUTTON_SELECTED_TEXT);
                centered(graphics, button, "Retry", button.y + 16);
            }
        } finally {
            graphics.dispose();
        }
    }

    public static Rectangle retryBounds(Rectangle chartBounds) {
        return new Rectangle(chartBounds.x + (chartBounds.width - 84) / 2,
            chartBounds.y + chartBounds.height / 2 + 10, 84, 25);
    }

    private void centered(Graphics2D graphics, Rectangle bounds, String text, int baseline) {
        FontMetrics metrics = graphics.getFontMetrics();
        graphics.drawString(text, bounds.x + (bounds.width - metrics.stringWidth(text)) / 2, baseline);
    }
}
