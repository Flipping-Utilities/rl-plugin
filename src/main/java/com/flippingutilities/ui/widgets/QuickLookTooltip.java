package com.flippingutilities.ui.widgets;

import com.flippingutilities.model.TimeseriesResponse;
import com.flippingutilities.ui.uiutilities.CustomColors;
import com.flippingutilities.ui.uiutilities.TimeFormatters;
import com.flippingutilities.ui.uiutilities.UIUtilities;
import com.flippingutilities.utilities.SlotInfo;
import com.flippingutilities.utilities.SlotPredictedState;
import com.flippingutilities.utilities.WikiItemMargins;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;
import net.runelite.client.util.QuantityFormatter;

/**
 * A custom tooltip component that displays item price information and offer
 * competitiveness. This component is self-contained and responsible only for rendering.
 */
public class QuickLookTooltip implements LayoutableRenderableEntity {

    private static final int PADDING = 5;
    private static final int LINE_SPACING = 2;
    private static final int COLUMN_GAP = 10;
    private static final int TEXT_HORIZONTAL_PADDING = 60;
    private static final Color BACKGROUND_COLOR = new Color(0, 0, 0, 180);

    private final List<TextRow> textRows = new ArrayList<>();
    private final Point position = new Point();
    private final Dimension dimension = new Dimension();

    private final TimeSeriesChart chart;

    public QuickLookTooltip() {
        ChartConfig chartConfig = ChartConfig.builder()
                .labelFont(FontManager.getRunescapeSmallFont())
                .build();
        this.chart = new TimeSeriesChart(chartConfig);
    }

    /**
     * Clears all existing rows and rebuilds the tooltip content based on the
     * provided slot and wiki margin information.
     *
     * @param slot         The slot information containing the user's offer.
     * @param wikiItemInfo The latest wiki margin data for the item.
     */
    public void update(SlotInfo slot, WikiItemMargins wikiItemInfo) {
        textRows.clear();

        if (wikiItemInfo == null || slot == null) {
            addCenteredRow("Missing data.", Color.GRAY,
                    FontManager.getRunescapeBoldFont()
            );
            return;
        }

        // Default colors
        Color buyPriceColor = Color.WHITE;
        Color sellPriceColor = Color.WHITE;

        String competitivenessText = "";
        Color competitivenessColor = Color.WHITE;
        String suggestionText = "";
        Color suggestionColor = CustomColors.TOMATO;

        int wikiHigh = wikiItemInfo.getHigh();
        int wikiLow = wikiItemInfo.getLow();

        // Determine competitiveness and set colors/text accordingly
        if (slot.isBuyOffer()) {
            if (slot.getPredictedState() == SlotPredictedState.BETTER_THAN_WIKI) {
                buyPriceColor = ColorScheme.GRAND_EXCHANGE_PRICE;
                competitivenessText = "Buy offer is ultra competitive";
                competitivenessColor = ColorScheme.GRAND_EXCHANGE_PRICE;
            } else if (slot.getPredictedState() == SlotPredictedState.IN_RANGE) {
                buyPriceColor = ColorScheme.GRAND_EXCHANGE_PRICE;
                sellPriceColor = CustomColors.IN_RANGE;
                competitivenessText = "Buy offer is competitive";
                competitivenessColor = CustomColors.IN_RANGE;
            } else if (slot.getPredictedState() == SlotPredictedState.OUT_OF_RANGE) {
                sellPriceColor = CustomColors.TOMATO;
                competitivenessText = "Buy offer is not competitive";
                competitivenessColor = CustomColors.TOMATO;
                suggestionText =
                        "Set price to >= " +
                                QuantityFormatter.formatNumber(wikiLow);
            }
        } else { // Is a sell offer
            if (slot.getPredictedState() == SlotPredictedState.BETTER_THAN_WIKI) {
                sellPriceColor = ColorScheme.GRAND_EXCHANGE_PRICE;
                competitivenessText = "Sell offer is ultra competitive";
                competitivenessColor = ColorScheme.GRAND_EXCHANGE_PRICE;
            } else if (slot.getPredictedState() == SlotPredictedState.IN_RANGE) {
                buyPriceColor = CustomColors.IN_RANGE;
                sellPriceColor = ColorScheme.GRAND_EXCHANGE_PRICE;
                competitivenessText = "Sell offer is competitive";
                competitivenessColor = CustomColors.IN_RANGE;
            } else if (slot.getPredictedState() == SlotPredictedState.OUT_OF_RANGE) {
                buyPriceColor = CustomColors.TOMATO;
                competitivenessText = "Sell offer is not competitive";
                competitivenessColor = CustomColors.TOMATO;
                suggestionText =
                        "Set price to <= " +
                                QuantityFormatter.formatNumber(wikiHigh);
            }
        }

        // Add price and age rows
        String buyPriceText =
                wikiHigh == 0
                        ? "No data"
                        : QuantityFormatter.formatNumber(wikiHigh) + " gp";
        String sellPriceText =
                wikiLow == 0
                        ? "No data"
                        : QuantityFormatter.formatNumber(wikiLow) + " gp";
        String buyAgeText =
                wikiItemInfo.getHighTime() == 0
                        ? "No data"
                        : TimeFormatters.formatDuration(
                        Instant.ofEpochSecond(wikiItemInfo.getHighTime())
                );
        String sellAgeText =
                wikiItemInfo.getLowTime() == 0
                        ? "No data"
                        : TimeFormatters.formatDuration(
                        Instant.ofEpochSecond(wikiItemInfo.getLowTime())
                );


        // Add summary text
        if (!competitivenessText.isEmpty()) {
            addCenteredRow(
                    competitivenessText,
                    competitivenessColor,
                    FontManager.getRunescapeBoldFont()
            );
            addCenteredRow(
                    suggestionText,
                    suggestionColor,
                    FontManager.getRunescapeSmallFont()
            );
            addSpacerRow();
        }

        addRow(
                "Offer Price:",
                UIUtilities.quantityToRSDecimalStack(slot.getOfferPrice(), true),
                Color.WHITE,
                getColorForStack(slot.getOfferPrice())
        );
        addRow(
                "State:",
                slot.getPredictedState().toString(),
                Color.WHITE,
                Color.WHITE
        );
        addSpacerRow();

        addRow("Wiki Insta Buy:", buyPriceText, Color.WHITE, buyPriceColor);
        addRow("Wiki Insta Sell:", sellPriceText, Color.WHITE, sellPriceColor);
        addRow("Buy Age:", buyAgeText, Color.WHITE, Color.WHITE);
        addRow("Sell Age:", sellAgeText, Color.WHITE, Color.WHITE);
    }

    /**
     * Adds a two-column line of text to the tooltip.
     *
     * @param left       Text for the left column.
     * @param right      Text for the right column (can be null).
     * @param leftColor  Color for the left text.
     * @param rightColor Color for the right text.
     */
    public void addRow(String left, String right, Color leftColor, Color rightColor) {
        textRows.add(new TextRow(left, right, leftColor, rightColor, false, null));
    }

    /**
     * Adds a single, centered line of text to the tooltip.
     *
     * @param text  Text to be centered.
     * @param color Color of the text.
     * @param font  Font to use for the text.
     */
    public void addCenteredRow(String text, Color color, Font font) {
        if (text != null && !text.isEmpty()) {
            textRows.add(new TextRow(text, null, color, null, true, font));
        }
    }

    // Spacer row
    public void addSpacerRow() {
        addRow("", null, Color.WHITE, null);
    }

    public void setGraphData(TimeseriesResponse timeseries, com.flippingutilities.model.Timestep timestep, int offerPrice) {
        chart.setDataSeries(timeseries, timestep, offerPrice);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (textRows.isEmpty()) {
            return new Dimension();
        }

        final Font defaultFont = graphics.getFont();
        final FontMetrics defaultMetrics = graphics.getFontMetrics(defaultFont);

        int maxLeftWidth = 0;
        int maxRightWidth = 0;
        int maxCenteredWidth = 0;

        // Calculate maximum widths for layout
        for (final TextRow textRow : textRows) {
            Font font = textRow.font != null ? textRow.font : defaultFont;
            FontMetrics metrics = graphics.getFontMetrics(font);
            if (textRow.centered) {
                maxCenteredWidth =
                        Math.max(maxCenteredWidth, metrics.stringWidth(textRow.left));
            } else {
                maxLeftWidth =
                        Math.max(maxLeftWidth, metrics.stringWidth(textRow.left));
                if (textRow.right != null) {
                    maxRightWidth =
                            Math.max(
                                    maxRightWidth,
                                    metrics.stringWidth(textRow.right)
                            );
                }
            }
        }

        int twoColumnWidth =
                maxLeftWidth + (maxRightWidth > 0 ? COLUMN_GAP + maxRightWidth : 0);
        int contentWidth = Math.max(twoColumnWidth, maxCenteredWidth);
        int panelWidth = contentWidth + PADDING * 2 + TEXT_HORIZONTAL_PADDING * 2;

        if (chart.hasData()) {
            panelWidth = Math.max(panelWidth, chart.getBounds().width);
        }

        // Calculate panel height
        int panelHeight = PADDING * 2;
        for (int i = 0; i < textRows.size(); i++) {
            TextRow textRow = textRows.get(i);
            Font font = textRow.font != null ? textRow.font : defaultFont;
            FontMetrics metrics = graphics.getFontMetrics(font);
            panelHeight += metrics.getHeight();
            if (i < textRows.size() - 1) {
                panelHeight += LINE_SPACING;
            }
        }

        // Draw background
        graphics.setColor(BACKGROUND_COLOR);
        graphics.fillRect(position.x, position.y, panelWidth, panelHeight);

        // Draw text lines
        int y = position.y + PADDING;
        for (final TextRow textRow : textRows) {
            Font font = textRow.font != null ? textRow.font : defaultFont;
            FontMetrics metrics = graphics.getFontMetrics(font);
            graphics.setFont(font);
            y += metrics.getAscent();

            if (textRow.centered) {
                int textWidth = metrics.stringWidth(textRow.left);
                int centeredX = position.x + (panelWidth - textWidth) / 2;
                graphics.setColor(textRow.leftColor);
                graphics.drawString(textRow.left, centeredX, y);
            } else {
                graphics.setColor(textRow.leftColor);
                graphics.drawString(textRow.left, position.x + PADDING + TEXT_HORIZONTAL_PADDING, y);

                if (textRow.right != null) {
                    int rightX = position.x +
                            panelWidth -
                            PADDING -
                            TEXT_HORIZONTAL_PADDING -
                            metrics.stringWidth(textRow.right);
                    graphics.setColor(textRow.rightColor);
                    graphics.drawString(textRow.right, rightX, y);
                }
            }
            y += metrics.getDescent() + LINE_SPACING;
        }

        graphics.setFont(defaultFont);

        int totalHeight = panelHeight;
        int graphY = position.y + panelHeight;

        if (chart.hasData()) {
            chart.setPreferredLocation(new Point(position.x, graphY));
            Dimension chartSize = chart.render(graphics);
            totalHeight += chartSize.height;
            panelWidth = Math.max(panelWidth, chartSize.width);
        }

        dimension.setSize(panelWidth, totalHeight);
        return dimension;
    }

    @Override
    public void setPreferredLocation(Point position) {
        this.position.setLocation(position);
    }

    @Override
    public void setPreferredSize(Dimension dimension) {
        // Not used, size is calculated dynamically
    }

    @Override
    public Rectangle getBounds() {
        return new Rectangle(position, dimension);
    }

    /**
     * A simple data class to hold information for a single line in the tooltip.
     */
    private static final class TextRow {

        private final String left;
        private final String right;
        private final Color leftColor;
        private final Color rightColor;
        private final boolean centered;
        private final Font font;

        TextRow(
                String left,
                String right,
                Color leftColor,
                Color rightColor,
                boolean centered,
                Font font
        ) {
            this.left = left;
            this.right = right;
            this.leftColor = leftColor;
            this.rightColor = rightColor;
            this.centered = centered;
            this.font = font;
        }
    }

    /**
     * Determines the color for a gp value based on its magnitude.
     */
    private static Color getColorForStack(long quantity) {
        if (quantity >= 10_000_000) return Color.GREEN;
        if (quantity >= 100_000) return Color.WHITE;
        return Color.YELLOW;
    }
}