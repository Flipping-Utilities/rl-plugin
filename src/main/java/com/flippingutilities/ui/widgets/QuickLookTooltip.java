package com.flippingutilities.ui.widgets;

import com.flippingutilities.model.TimeseriesPoint;
import com.flippingutilities.model.TimeseriesResponse;
import com.flippingutilities.ui.uiutilities.CustomColors;
import com.flippingutilities.ui.uiutilities.TimeFormatters;
import com.flippingutilities.ui.uiutilities.UIUtilities;
import com.flippingutilities.utilities.SlotInfo;
import com.flippingutilities.utilities.SlotPredictedState;
import com.flippingutilities.utilities.WikiItemMargins;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;
import net.runelite.client.util.QuantityFormatter;

/**
 * A custom tooltip component that displays item price information and offer
 * competitiveness. This component is self-contained and responsible only for rendering.
 */
public class QuickLookTooltip implements LayoutableRenderableEntity {

    // Component layout constants
    private static final int PADDING = 5;
    private static final int LINE_SPACING = 2;
    private static final int COLUMN_GAP = 10;
    private static final int TEXT_HORIZONTAL_PADDING = 60;
    private static final Color BACKGROUND_COLOR = new Color(0, 0, 0, 180);

    // Improved graph constants
    private static final int GRAPH_WIDTH = 300;  // Increased from 150
    private static final int GRAPH_HEIGHT = 180; // Increased from 120
    private static final int GRAPH_TOP_PADDING = 15;
    private static final int GRAPH_BOTTOM_PADDING = 25;
    private static final int GRAPH_RIGHT_PADDING = 15;

    private static final Color GRAPH_BACKGROUND_COLOR = new Color(0, 0, 0, 180);
    private static final Color GRAPH_GRID_COLOR = new Color(50, 50, 50, 200);
    private static final Color GRAPH_FILL_COLOR = new Color(80, 80, 80, 100);
    private static final Color GRAPH_HIGH_LINE_COLOR = new Color(0, 200, 0);
    private static final Color GRAPH_LOW_LINE_COLOR = new Color(200, 0, 0);
    private static final Color GRAPH_OFFER_LINE_COLOR = new Color(0, 200, 255);
    private static final Color GRAPH_LABEL_COLOR = new Color(200, 200, 200);
    private static final float GRAPH_LINE_STROKE = 1.3f;
    private static final float GRAPH_GRID_STROKE = 0.5f;
    private static final float GRAPH_OFFER_STROKE = 1.3f;

    private final List<TextRow> textRows = new ArrayList<>();
    private final Point position = new Point();
    private final Dimension dimension = new Dimension();

    private TimeseriesResponse timeseries;
    private int offerPrice;

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

    public void setGraphData(TimeseriesResponse timeseries, int offerPrice) {
        this.timeseries = timeseries;
        this.offerPrice = offerPrice;
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

        if (timeseries != null && timeseries.getData() != null && !timeseries.getData().isEmpty()) {
            panelWidth = Math.max(panelWidth, GRAPH_WIDTH);
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

        if (timeseries != null && timeseries.getData() != null && !timeseries.getData().isEmpty()) {
            int graphPanelWidth = Math.max(panelWidth, GRAPH_WIDTH);
            renderGraph(graphics, position.x, graphY, graphPanelWidth, GRAPH_HEIGHT);
            totalHeight += GRAPH_HEIGHT;
            panelWidth = graphPanelWidth;
        }

        dimension.setSize(panelWidth, totalHeight);
        return dimension;
    }

    private void renderGraph(Graphics2D g2d, int x, int y, int width, int height) {
        // Enable anti-aliasing for smooth lines
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Draw graph background
        g2d.setColor(GRAPH_BACKGROUND_COLOR);
        g2d.fillRect(x, y, width, height);

        List<TimeseriesPoint> dataPoints = timeseries.getData();
        if (dataPoints.isEmpty()) {
            return;
        }

        // Filter data for last 24 hours
        long currentTime = System.currentTimeMillis() / 1000;
        long twentyFourHoursAgo = currentTime - (24 * 60 * 60);

        List<TimeseriesPoint> last24Hours = dataPoints.stream()
                .filter(p -> p.getTimestamp() >= twentyFourHoursAgo)
                .sorted((a, b) -> Long.compare(a.getTimestamp(), b.getTimestamp()))
                .collect(Collectors.toList());

        if (last24Hours.isEmpty()) {
            // If no data in last 24h, use all available data
            last24Hours = new ArrayList<>(dataPoints);
        }

        long minPrice = Long.MAX_VALUE;
        long maxPrice = Long.MIN_VALUE;

        for (TimeseriesPoint point : last24Hours) {
            Integer highPrice = point.getAvgHighPrice();
            Integer lowPrice = point.getAvgLowPrice();

            if (highPrice != null) {
                maxPrice = Math.max(maxPrice, highPrice);
            }
            if (lowPrice != null) {
                minPrice = Math.min(minPrice, lowPrice);
            }
        }

        maxPrice = Math.max(maxPrice, offerPrice);
        minPrice = Math.min(minPrice, offerPrice);

        int dynamicLeftPadding = calculateLeftPadding(maxPrice);

        // Calculate actual graph drawing area
        int graphX = x + dynamicLeftPadding;
        int graphY = y + GRAPH_TOP_PADDING;
        int graphWidth = width - dynamicLeftPadding - GRAPH_RIGHT_PADDING;
        int graphHeight = height - GRAPH_TOP_PADDING - GRAPH_BOTTOM_PADDING;

        // Add margin to prevent lines from being at the edges
        long priceRange = maxPrice - minPrice;
        if (priceRange < 10) {
            // If price range is too small, expand it uwu
            long midPrice = (maxPrice + minPrice) / 2;
            maxPrice = midPrice + 20;
            minPrice = midPrice - 20;
        } else {
            // Add 10% margin on top and bottom owo
            long margin = Math.max(priceRange / 10, 5);
            maxPrice += margin;
            minPrice = Math.max(0, minPrice - margin);
        }
        priceRange = maxPrice - minPrice;

        // grid lines
        g2d.setColor(GRAPH_GRID_COLOR);
        g2d.setStroke(new BasicStroke(GRAPH_GRID_STROKE, BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, 10.0f, new float[]{2.0f, 2.0f}, 0.0f));

        // horizontal grid lines and price labels
        Font smallFont = FontManager.getRunescapeSmallFont();
        g2d.setFont(smallFont);
        FontMetrics fm = g2d.getFontMetrics();

        // nice tick intervals
        long tickInterval = calculateNiceTickInterval(priceRange);
        long startPrice = (minPrice / tickInterval) * tickInterval;
        long endPrice = ((maxPrice / tickInterval) + 1) * tickInterval;

        // grid lines at nice intervals
        for (long price = startPrice; price <= endPrice; price += tickInterval) {
            if (price < minPrice || price > maxPrice) {
                continue;
            }

            // Calculate Y position for x price
            int lineY = graphY + graphHeight - (int)((price - minPrice) * graphHeight / priceRange);

            g2d.setColor(GRAPH_GRID_COLOR);
            g2d.drawLine(graphX, lineY, graphX + graphWidth, lineY);

            String priceText = formatPriceNice(price);
            g2d.setColor(GRAPH_LABEL_COLOR);
            int textWidth = fm.stringWidth(priceText);
            g2d.drawString(priceText, graphX - textWidth - 5, lineY + fm.getAscent() / 2);
        }

        // vertical grid lines for time
        for (int i = 0; i <= 4; i++) {
            int lineX = graphX + (i * graphWidth / 4);
            g2d.setColor(GRAPH_GRID_COLOR);
            g2d.drawLine(lineX, graphY, lineX, graphY + graphHeight);
        }

        // data for drawing
        long timeRange = last24Hours.isEmpty() ? 1 :
                last24Hours.get(last24Hours.size() - 1).getTimestamp() - last24Hours.get(0).getTimestamp();

        if (timeRange == 0) {
            timeRange = 24 * 60 * 60; // Default to 24 hours if all points have same timestamp
        }

        long startTime = last24Hours.isEmpty() ? twentyFourHoursAgo : last24Hours.get(0).getTimestamp();

        // points for high and low lines
        List<Point> highPoints = new ArrayList<>();
        List<Point> lowPoints = new ArrayList<>();

        for (TimeseriesPoint point : last24Hours) {
            long timeOffset = point.getTimestamp() - startTime;
            float timePercent = timeRange > 0 ? (float) timeOffset / timeRange : 0.5f;
            int xPos = graphX + (int) (timePercent * graphWidth);

            Integer highPrice = point.getAvgHighPrice();
            if (highPrice != null) {
                int yPos = graphY + graphHeight - (int) ((highPrice - minPrice) * graphHeight / priceRange);
                highPoints.add(new Point(xPos, yPos));
            }

            Integer lowPrice = point.getAvgLowPrice();
            if (lowPrice != null) {
                int yPos = graphY + graphHeight - (int) ((lowPrice - minPrice) * graphHeight / priceRange);
                lowPoints.add(new Point(xPos, yPos));
            }
        }

        // Draw fill between high and low
        if (highPoints.size() > 1 && lowPoints.size() > 1) {
            int[] fillX = new int[highPoints.size() + lowPoints.size()];
            int[] fillY = new int[highPoints.size() + lowPoints.size()];

            // high points
            for (int i = 0; i < highPoints.size(); i++) {
                fillX[i] = highPoints.get(i).x;
                fillY[i] = highPoints.get(i).y;
            }

            // low points in reverse
            for (int i = 0; i < lowPoints.size(); i++) {
                int idx = highPoints.size() + i;
                fillX[idx] = lowPoints.get(lowPoints.size() - 1 - i).x;
                fillY[idx] = lowPoints.get(lowPoints.size() - 1 - i).y;
            }

            g2d.setColor(GRAPH_FILL_COLOR);
            g2d.fillPolygon(fillX, fillY, fillX.length);
        }
        g2d.setStroke(new BasicStroke(GRAPH_LINE_STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        // draw high line
        drawLine(g2d, highPoints, GRAPH_HIGH_LINE_COLOR);

        // draw low line
        drawLine(g2d, lowPoints, GRAPH_LOW_LINE_COLOR);

        // Draw offer price line
        int offerY = graphY + graphHeight - (int) ((offerPrice - minPrice) * graphHeight / priceRange);
        g2d.setStroke(new BasicStroke(GRAPH_OFFER_STROKE, BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, 10.0f, new float[]{5.0f, 5.0f}, 0.0f));
        g2d.setColor(GRAPH_OFFER_LINE_COLOR);
        g2d.drawLine(graphX, offerY, graphX + graphWidth, offerY);

        // Add "Your offer" label
        String offerLabel = "Your offer";
        g2d.setFont(smallFont);
        g2d.setColor(GRAPH_OFFER_LINE_COLOR);
        int labelWidth = fm.stringWidth(offerLabel);
        g2d.drawString(offerLabel, graphX + graphWidth - labelWidth - 5, offerY - 3);

        // Draw time labels
        g2d.setColor(GRAPH_LABEL_COLOR);
        g2d.setFont(smallFont);

        // Time labels at bottom
        String[] timeLabels = {"24h", "18h", "12h", "6h", "Now"};
        for (int i = 0; i < 5; i++) {
            int labelX = graphX + (i * graphWidth / 4);
            String label = timeLabels[i];
            int labelWidth2 = fm.stringWidth(label);
            g2d.drawString(label, labelX - labelWidth2 / 2, y + height - 5);
        }

        // Draw border around graph
        g2d.setStroke(new BasicStroke(1.0f));
        g2d.setColor(GRAPH_GRID_COLOR);
        g2d.drawRect(graphX, graphY, graphWidth, graphHeight);
    }

    private void drawLine(Graphics2D g2d, List<Point> points, Color graphLineColor) {
        if (points.size() > 1) {
            g2d.setColor(graphLineColor);
            for (int i = 0; i < points.size() - 1; i++) {
                Point p1 = points.get(i);
                Point p2 = points.get(i + 1);
                g2d.drawLine(p1.x, p1.y, p2.x, p2.y);
            }
        }
    }

    /**
     * Calculates a "nice" tick interval that is a multiple of 1, 2, 5, or 10.
     * This ensures human-readable values on the Y-axis.
     */
    private long calculateNiceTickInterval(long range) {
        long roughInterval = range / 6;

        if (roughInterval <= 0) {
            return 1;
        }

        // magnitude (power of 10)
        long magnitude = 1;
        while (magnitude * 10 <= roughInterval) {
            magnitude *= 10;
        }

        // figure out nice number in the sequence [1, 2, 2.5, 5, 10] * magnitude
        if (roughInterval <= magnitude) {
            return magnitude;
        } else if (roughInterval <= 2 * magnitude) {
            return 2 * magnitude;
        } else if (roughInterval <= 2.5 * magnitude) {
            // For prices, we'll use 2.5 as 25, 250, 2500, etc.
            return (25 * magnitude) / 10;
        } else if (roughInterval <= 5 * magnitude) {
            return 5 * magnitude;
        } else {
            return 10 * magnitude;
        }
    }

    /**
     * Formats price values cleanly, showing actual values at nice intervals.
     * For example: 12.5M for 12,500,000 or 500K for 500,000
     */
    private String formatPriceNice(long price) {
        if (price >= 1_000_000_000) {
            if (price % 1_000_000_000 == 0) {
                return String.format("%dB", price / 1_000_000_000);
            } else {
                return String.format("%.1fB", price / 1_000_000_000.0);
            }
        } else if (price >= 1_000_000) {
            if (price % 1_000_000 == 0) {
                return String.format("%dM", price / 1_000_000);
            } else if (price % 100_000 == 0) {
                return String.format("%.1fM", price / 1_000_000.0);
            } else {
                return String.format("%.2fM", price / 1_000_000.0);
            }
        } else if (price >= 1_000) {
            if (price % 1_000 == 0) {
                return String.format("%dK", price / 1_000);
            } else if (price % 100 == 0) {
                return String.format("%.1fK", price / 1_000.0);
            } else {
                return String.format("%.2fK", price / 1_000.0);
            }
        } else {
            return String.valueOf(price);
        }
    }

    /**
     * set values for the padding based on value, no pattern so no dynimci stuff
     */
    private int calculateLeftPadding(long maxPrice) {
        if (maxPrice >= 100_000_000) {
            return 55;
        } else if (maxPrice >= 10_000_000) {
            return 50;
        } else if (maxPrice >= 1_000_000) {
            return 48;
        } else if (maxPrice >= 100_000) {
            return 42;
        } else if (maxPrice >= 10_000) {
            return 38;
        } else if (maxPrice >= 1_000) {
            return 35;
        } else {
            return 30;
        }
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