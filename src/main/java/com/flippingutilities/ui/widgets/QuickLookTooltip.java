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
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.BasicStroke;
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
    private static final int TEXT_HORIZONTAL_PADDING_WITH_GRAPH = 60;
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

        int textHorizontalPadding = TEXT_HORIZONTAL_PADDING_WITH_GRAPH;

        int twoColumnWidth =
             maxLeftWidth + (maxRightWidth > 0 ? COLUMN_GAP + maxRightWidth : 0);
        int contentWidth = Math.max(twoColumnWidth, maxCenteredWidth);
        int panelWidth = contentWidth + PADDING * 2 + textHorizontalPadding * 2;

        // Use fixed chart dimensions instead of chart.getBounds() to ensure consistent sizing
        int fixedChartWidth = 320;
        int fixedChartHeight = 200;
        panelWidth = Math.max(panelWidth, fixedChartWidth);
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
        panelHeight += PADDING + fixedChartHeight;

        panelWidth = Math.max(panelWidth, fixedChartWidth);

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
                graphics.drawString(textRow.left, position.x + PADDING + textHorizontalPadding, y);

                if (textRow.right != null) {
                    int rightX = position.x +
                            panelWidth -
                            PADDING -
                            textHorizontalPadding -
                            metrics.stringWidth(textRow.right);
                    graphics.setColor(textRow.rightColor);
                    graphics.drawString(textRow.right, rightX, y);
                }
            }
            y += metrics.getDescent() + LINE_SPACING;
        }

        graphics.setFont(defaultFont);

        int chartX = position.x;
        int chartY = position.y + panelHeight - fixedChartHeight;

        chart.setPreferredLocation(new Point(chartX, chartY));
        if (chart.hasData()) {
            chart.setPreferredLocation(new Point(chartX, chartY));
            java.awt.Shape originalClip = graphics.getClip();
            graphics.setClip(chartX, chartY, fixedChartWidth, fixedChartHeight);
            chart.render(graphics);
            graphics.setClip(originalClip);
        } else {
            Rectangle chartBounds = new Rectangle(chartX, chartY, fixedChartWidth, fixedChartHeight);
            drawLoadingAnimation(graphics, chartBounds);
        }

        dimension.setSize(panelWidth, panelHeight);
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
     * Draws a loading animation in the chart area when graph data is not available.
     * Creates an animated chart with red and green lines moving around
     */
    private void drawLoadingAnimation(Graphics2D graphics, Rectangle chartBounds) {
        java.awt.Shape originalClip = graphics.getClip();
        graphics.setClip(chartBounds);
        
        // Set rendering hints for smooth animation
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        // Chart padding
        int leftPadding = 10;
        int rightPadding = 10;
        int topPadding = 10;
        int bottomPadding = 25;
        
        int plotX = chartBounds.x + leftPadding;
        int plotY = chartBounds.y + topPadding;
        int plotWidth = chartBounds.width - leftPadding - rightPadding;
        int plotHeight = chartBounds.height - topPadding - bottomPadding;
        
        // Draw background grid
        graphics.setColor(new Color(60, 60, 60, 100));
        graphics.setStroke(new BasicStroke(1f));
        for (int i = 0; i <= 4; i++) {
            int y = plotY + (plotHeight * i) / 4;
            graphics.drawLine(plotX, y, plotX + plotWidth, y);
        }
        
        // Animation parameters
        long currentTime = System.currentTimeMillis();
        int numPoints = 20; // Number of data points to display
        
        // Red vs Green battle
        Color[] colors = {
            new Color(255, 80, 80),    // Red
            new Color(0, 220, 100)     // Green
        };
        
        // Use full scale from 0-200 but keep values oscillating in the middle
        double minValue = 0;
        double maxValue = 200;
        double centerValue = 100;
        
        // Generate and draw each line
        for (int lineIndex = 0; lineIndex < colors.length; lineIndex++) {
            List<Point> points = new ArrayList<>();
            
            double currentValue = centerValue + (lineIndex == 0 ? 5 : -5); // Start slightly apart
            
            for (int i = 0; i < numPoints; i++) {
                // Faster, more aggressive animation
                double seed = (currentTime / 50.0) + i * 0.5 + lineIndex * 500;
                
                // Multiple frequencies create chaotic but smooth movement
                double noise = Math.sin(seed * 0.15) * 8
                            + Math.sin(seed * 0.08) * 6
                            + Math.sin(seed * 0.25) * 3;
                
                // Add competition effect - lines try to cross each other
                double competition = Math.sin((currentTime / 300.0) + i * 0.1 + lineIndex * Math.PI) * 8;
                
                // Quick spikes for dramatic effect
                double spike = Math.sin(seed * 0.3) > 0.9 ? Math.sin(seed) * 5 : 0;
                
                // Gentle pull back to center to keep lines from drifting too far
                double centerPull = (centerValue - currentValue) * 0.15;
                
                currentValue += noise + competition + spike + centerPull;
                
                // Soft clamp - allow some overshoot but pull back
                if (currentValue < centerValue - 30) {
                    currentValue = centerValue - 30 + (currentValue - (centerValue - 30)) * 0.3;
                }
                if (currentValue > centerValue + 30) {
                    currentValue = centerValue + 30 + (currentValue - (centerValue + 30)) * 0.3;
                }
                
                int x = plotX + (plotWidth * i) / (numPoints - 1);
                points.add(new Point(x, (int) (currentValue * 100) / 100));
            }
            
            // Draw the line
            graphics.setColor(colors[lineIndex]);
            graphics.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            
            for (int i = 0; i < points.size() - 1; i++) {
                Point p1 = points.get(i);
                Point p2 = points.get(i + 1);
                
                // Scale y values to fit plot height - invert because screen coords are top-down
                int y1 = plotY + plotHeight - (int) ((p1.y - minValue) / (maxValue - minValue) * plotHeight);
                int y2 = plotY + plotHeight - (int) ((p2.y - minValue) / (maxValue - minValue) * plotHeight);
                
                graphics.drawLine(p1.x, y1, p2.x, y2);
            }
            
            // Draw a pulsing highlight dot at the end of the line
            Point lastPoint = points.get(points.size() - 1);
            int lastY = plotY + plotHeight - (int) ((lastPoint.y - minValue) / (maxValue - minValue) * plotHeight);
            
            // Pulsing effect
            double pulse = Math.sin(currentTime / 150.0) * 0.3 + 0.7;
            graphics.setColor(new Color(
                colors[lineIndex].getRed(),
                colors[lineIndex].getGreen(),
                colors[lineIndex].getBlue(),
                (int) (255 * pulse)
            ));
            graphics.fillOval(lastPoint.x - 5, lastY - 5, 10, 10);
            
            // Bright center
            graphics.setColor(colors[lineIndex].brighter());
            graphics.fillOval(lastPoint.x - 3, lastY - 3, 6, 6);
        }
        
        // Draw "Loading graph..." text
        graphics.setColor(new Color(200, 200, 200));
        graphics.setFont(FontManager.getRunescapeSmallFont());
        String loadingText = "Loading graph...";
        FontMetrics fm = graphics.getFontMetrics();
        int textX = chartBounds.x + chartBounds.width / 2 - fm.stringWidth(loadingText) / 2;
        int textY = chartBounds.y + chartBounds.height - 8;
        graphics.drawString(loadingText, textX, textY);
        
        // Animated progress dots
        int numDots = 3;
        int dotIndex = (int) ((currentTime / 400) % (numDots + 1));
        String dots = "";
        for (int i = 0; i < dotIndex; i++) {
            dots += ".";
        }
        graphics.drawString(dots, textX + fm.stringWidth(loadingText) + 2, textY);
        
        // Restore original clip
        graphics.setClip(originalClip);
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
