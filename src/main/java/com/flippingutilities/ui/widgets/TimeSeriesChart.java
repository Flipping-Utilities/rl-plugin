package com.flippingutilities.ui.widgets;

import com.flippingutilities.model.Timestep;
import com.flippingutilities.model.TimeseriesPoint;
import com.flippingutilities.model.TimeseriesResponse;
import com.flippingutilities.ui.uiutilities.CustomColors;
import com.flippingutilities.ui.uiutilities.UIUtilities;
import com.flippingutilities.ui.widgets.chart.ChartBounds;
import com.flippingutilities.ui.widgets.chart.PriceRange;
import com.flippingutilities.ui.widgets.chart.TimeRange;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;
import lombok.extern.slf4j.Slf4j;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public final class TimeSeriesChart implements LayoutableRenderableEntity {

    private static final int PRICE_RANGE_MARGIN_DIVISOR = 10;
    private static final int MIN_PRICE_MARGIN = 5;

    private static final int LABEL_PADDING = 5;
    private static final int OFFER_LABEL_OFFSET = 3;

    private static final float GRID_DASH_LENGTH = 2.0f;
    private static final float OFFER_LINE_DASH_LENGTH = 5.0f;
    private static final float STROKE_ROUND_MITER = 10.0f;
    private static final float HOVER_LINE_STROKE = 1.5f;

    private static final int DEFAULT_LEFT_PADDING = 38;

    private static final String OFFER_LABEL_TEXT = "Your offer";

    private final ChartConfig config;
    private final TickIntervalCalculator tickCalculator;

    private final Point position = new Point();
    private final Dimension dimension = new Dimension();

    // Mutable dimensions that can be updated by setPreferredSize
    private int mutableWidth;
    private int mutableHeight;

    private TimeseriesResponse timeseries;
    private Timestep timestep;
    private int offerPrice;
    private long maxTimeRangeSeconds; // Custom max time range (overrides timestep default)

    // Hover state for vertical line indicator
    private TimeseriesPoint hoveredPoint;

    // Hover state for horizontal price line indicator
    private Integer hoveredPriceY;

    public TimeSeriesChart(ChartConfig config) {
        this.config = config;
        this.tickCalculator = new TickIntervalCalculator();
        this.mutableWidth = config.getWidth();
        this.mutableHeight = config.getHeight();
    }

    @Override
    public Dimension render(Graphics2D g2d) {
        if (!hasData()) {
            return new Dimension();
        }

        setupRenderingHints(g2d);

        List<TimeseriesPoint> dataPoints = timeseries.getData();
        if (dataPoints.isEmpty()) {
            dimension.setSize(config.getWidth(), config.getHeight());
            return dimension;
        }

        List<TimeseriesPoint> filteredData = filterAndSortData(dataPoints);

        PriceRange priceRange = calculatePriceRange(filteredData);

        ChartBounds bounds = calculateChartBounds();

        priceRange = adjustPriceRange(priceRange);

        drawGrid(g2d, bounds, priceRange);
        drawDataSeries(g2d, bounds, priceRange, filteredData);
        drawOfferLine(g2d, bounds, priceRange);
        drawTimeLabels(g2d, bounds);
        drawHoverLine(g2d, bounds, filteredData);
        drawHorizontalPriceLine(g2d, bounds, priceRange);

        dimension.setSize(config.getWidth(), config.getHeight());
        return dimension;
    }

    private void setupRenderingHints(Graphics2D g2d) {
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    public List<TimeseriesPoint> filterAndSortData(List<TimeseriesPoint> dataPoints) {
        List<TimeseriesPoint> sorted = new ArrayList<>();

        // Only include points within the selected date range
        long currentTime = System.currentTimeMillis() / 1000;
        long minTimestamp = currentTime - maxTimeRangeSeconds;

        for (TimeseriesPoint point : dataPoints) {
            if (point.getTimestamp() >= minTimestamp) {
                sorted.add(point);
            }
        }

        // Insertion sort by timestamp
        for (int i = 1; i < sorted.size(); i++) {
            TimeseriesPoint key = sorted.get(i);
            int j = i - 1;
            while (j >= 0 && sorted.get(j).getTimestamp() > key.getTimestamp()) {
                sorted.set(j + 1, sorted.get(j));
                j--;
            }
            sorted.set(j + 1, key);
        }

        return sorted;
    }

    private PriceRange calculatePriceRange(List<TimeseriesPoint> dataPoints) {
        Integer minPrice = null;
        Integer maxPrice = null;

        for (TimeseriesPoint point : dataPoints) {
            if (point.getAvgHighPrice() != null) {
                int highPrice = point.getAvgHighPrice();
                maxPrice = maxPrice == null ? highPrice : Math.max(maxPrice, highPrice);
            }

            if (point.getAvgLowPrice() != null) {
                int lowPrice = point.getAvgLowPrice();
                minPrice = minPrice == null ? lowPrice : Math.min(minPrice, lowPrice);
            }
        }

        // If we only have one type of price, use it for both min and max
        // This handles cases where we only have instasell or only instabuy data
        if (minPrice == null && maxPrice != null) {
            minPrice = maxPrice;
        } else if (maxPrice == null && minPrice != null) {
            maxPrice = minPrice;
        } else if (minPrice == null && maxPrice == null) {
            // No data at all, use offer price for both
            minPrice = offerPrice;
            maxPrice = offerPrice;
        }

        // Expand range to include offer price
        maxPrice = Math.max(maxPrice, offerPrice);
        minPrice = Math.min(minPrice, offerPrice);

        return new PriceRange(minPrice, maxPrice);
    }

    private PriceRange adjustPriceRange(PriceRange original) {
        int range = original.getRange();
        int min = original.min;
        int max = original.max;

        // For very small prices (<100), start at 0
        // For larger prices, use a margin around the data
        if (min < 100 && range < 100) {
            // Small prices - start at 0
            max = Math.max(max + 2, 5);
            min = 0;
        } else {
            // Larger prices - add margin around the data range
            int margin = Math.max(range / PRICE_RANGE_MARGIN_DIVISOR, MIN_PRICE_MARGIN);
            max += margin;
            min = Math.max(0, min - margin);
        }

        return new PriceRange(min, max);
    }

    private ChartBounds calculateChartBounds() {
        int x = position.x + DEFAULT_LEFT_PADDING;
        int y = position.y + config.getTopPadding();
        int width = mutableWidth - DEFAULT_LEFT_PADDING - config.getRightPadding();
        int height = mutableHeight - config.getTopPadding() - config.getBottomPadding();

        return new ChartBounds(x, y, width, height);
    }

    private TimeRange calculateTimeRange(List<TimeseriesPoint> filteredData) {
        // Always use the full range anchored to current time
        // This ensures the x-axis spans the selected duration (e.g., 24h for 1d)
        // and data points are positioned correctly within that range
        long currentTime = System.currentTimeMillis() / 1000;
        long start = currentTime - maxTimeRangeSeconds;
        long range = maxTimeRangeSeconds;

        return new TimeRange(start, range);
    }

    private void drawGrid(Graphics2D g2d, ChartBounds bounds, PriceRange priceRange) {
        g2d.setColor(config.getGridColor());
        g2d.setStroke(new BasicStroke(config.getGridStroke(), BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, STROKE_ROUND_MITER,
                new float[] { GRID_DASH_LENGTH, GRID_DASH_LENGTH }, 0.0f));

        g2d.setFont(config.getLabelFont());
        FontMetrics fm = g2d.getFontMetrics();

        drawHorizontalGridLines(g2d, bounds, priceRange, fm);
        drawVerticalGridLines(g2d, bounds);
    }

    private void drawHorizontalGridLines(Graphics2D g2d, ChartBounds bounds,
            PriceRange priceRange, FontMetrics fm) {
        long tickInterval = tickCalculator.calculate(priceRange.getRange());
        long startPrice = (priceRange.min / tickInterval) * tickInterval;
        long endPrice = ((priceRange.max / tickInterval) + 1) * tickInterval;

        int rightEdge = bounds.getRightEdge();

        for (long price = startPrice; price <= endPrice; price += tickInterval) {
            if (price < priceRange.min || price > priceRange.max) {
                continue;
            }

            int lineY = calculateYPosition(price, bounds, priceRange);

            g2d.setColor(config.getGridColor());
            g2d.drawLine(bounds.x, lineY, rightEdge, lineY);

            String priceText = UIUtilities.quantityToRSDecimalStack(price, false);
            g2d.setColor(config.getLabelColor());
            int textWidth = fm.stringWidth(priceText);
            g2d.drawString(priceText, bounds.x - textWidth - LABEL_PADDING,
                    lineY + fm.getAscent() / 2);
        }
    }

    private void drawVerticalGridLines(Graphics2D g2d, ChartBounds bounds) {
        int bottomEdge = bounds.getBottomEdge();
        int divisions = timestep.getLabelCount() - 1;

        for (int i = 0; i <= divisions; i++) {
            int lineX = bounds.x + (i * bounds.width / divisions);
            g2d.setColor(config.getGridColor());
            g2d.drawLine(lineX, bounds.y, lineX, bottomEdge);
        }
    }

    private int calculateYPosition(long price, ChartBounds bounds, PriceRange priceRange) {
        int bottomEdge = bounds.getBottomEdge();
        long range = priceRange.getRange();
        return bottomEdge - (int) ((price - priceRange.min) * bounds.height / range);
    }

    private void drawDataSeries(Graphics2D g2d, ChartBounds bounds,
            PriceRange priceRange, List<TimeseriesPoint> filteredData) {
        if (filteredData.isEmpty()) {
            return;
        }

        TimeRange timeRange = calculateTimeRange(filteredData);

        int dataSize = filteredData.size();
        int[] highX = new int[dataSize];
        int[] highY = new int[dataSize];
        int[] lowX = new int[dataSize];
        int[] lowY = new int[dataSize];
        int highCount = 0;
        int lowCount = 0;

        for (TimeseriesPoint point : filteredData) {
            long timeOffset = point.getTimestamp() - timeRange.start;
            float timePercent = timeRange.range > 0 ? (float) timeOffset / timeRange.range : 0.5f;
            int xPos = bounds.x + (int) (timePercent * bounds.width);

            if (point.getAvgHighPrice() != null) {
                int highPrice = point.getAvgHighPrice();
                highX[highCount] = xPos;
                highY[highCount] = calculateYPosition(highPrice, bounds, priceRange);
                highCount++;
            }

            if (point.getAvgLowPrice() != null) {
                int lowPrice = point.getAvgLowPrice();
                lowX[lowCount] = xPos;
                lowY[lowCount] = calculateYPosition(lowPrice, bounds, priceRange);
                lowCount++;
            }
        }

        drawFillBetweenLines(g2d, highX, highY, highCount, lowX, lowY, lowCount);

        g2d.setStroke(new BasicStroke(config.getLineStroke(),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        drawLine(g2d, highX, highY, highCount, config.getHighLineColor());
        drawLine(g2d, lowX, lowY, lowCount, config.getLowLineColor());
    }

    private void drawFillBetweenLines(Graphics2D g2d, int[] highX, int[] highY, int highCount,
            int[] lowX, int[] lowY, int lowCount) {
        if (highCount <= 1 || lowCount <= 1) {
            return;
        }

        int totalPoints = highCount + lowCount;
        int[] fillX = new int[totalPoints];
        int[] fillY = new int[totalPoints];

        System.arraycopy(highX, 0, fillX, 0, highCount);
        System.arraycopy(highY, 0, fillY, 0, highCount);

        for (int i = 0; i < lowCount; i++) {
            int idx = highCount + i;
            fillX[idx] = lowX[lowCount - 1 - i];
            fillY[idx] = lowY[lowCount - 1 - i];
        }

        g2d.setColor(config.getFillColor());
        g2d.fillPolygon(fillX, fillY, totalPoints);
    }

    private void drawLine(Graphics2D g2d, int[] xPoints, int[] yPoints, int count, java.awt.Color color) {
        if (count <= 1) {
            return;
        }

        g2d.setColor(color);
        for (int i = 0; i < count - 1; i++) {
            g2d.drawLine(xPoints[i], yPoints[i], xPoints[i + 1], yPoints[i + 1]);
        }
    }

    private void drawOfferLine(Graphics2D g2d, ChartBounds bounds, PriceRange priceRange) {
        int offerY = calculateYPosition(offerPrice, bounds, priceRange);

        g2d.setStroke(new BasicStroke(config.getReferenceStroke(), BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, STROKE_ROUND_MITER,
                new float[] { OFFER_LINE_DASH_LENGTH, OFFER_LINE_DASH_LENGTH }, 0.0f));
        g2d.setColor(config.getReferenceLineColor());

        int rightEdge = bounds.getRightEdge();
        g2d.drawLine(bounds.x, offerY, rightEdge, offerY);

        g2d.setFont(config.getLabelFont());
        g2d.setColor(config.getReferenceLineColor());
        g2d.drawString(OFFER_LABEL_TEXT, bounds.x + LABEL_PADDING,
                offerY - OFFER_LABEL_OFFSET);
    }

    /**
     * Draws a vertical line at the hovered data point position.
     */
    private void drawHoverLine(Graphics2D g2d, ChartBounds bounds, List<TimeseriesPoint> filteredData) {
        if (hoveredPoint == null) {
            return;
        }
        if (filteredData.isEmpty()) {
            return;
        }

        TimeRange timeRange = calculateTimeRange(filteredData);
        long timeOffset = hoveredPoint.getTimestamp() - timeRange.start;
        float timePercent = timeRange.range > 0 ? (float) timeOffset / timeRange.range : 0.5f;
        int lineX = bounds.x + (int) (timePercent * bounds.width);

        lineX = Math.max(bounds.x, Math.min(lineX, bounds.x + bounds.width));

        int bottomEdge = bounds.getBottomEdge();

        g2d.setStroke(new BasicStroke(HOVER_LINE_STROKE, BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, STROKE_ROUND_MITER,
                new float[] { 5.0f, 5.0f }, 0.0f));
        g2d.setColor(CustomColors.CHART_ACCENT);
        g2d.drawLine(lineX, bounds.y, lineX, bottomEdge);
    }

    /**
     * Sets the currently hovered data point for the vertical line indicator.
     */
    public void setHoveredPoint(TimeseriesPoint point) {
        this.hoveredPoint = point;
    }

    private void drawTimeLabels(Graphics2D g2d, ChartBounds bounds) {
        g2d.setColor(config.getLabelColor());
        g2d.setFont(config.getLabelFont());
        FontMetrics fm = g2d.getFontMetrics();

        int bottomY = position.y + config.getHeight() - LABEL_PADDING;
        long currentTimeSeconds = System.currentTimeMillis() / 1000;
        String[] timeLabels = TimeLabelGenerator.generate(timestep, currentTimeSeconds);
        int divisions = timestep.getLabelCount() - 1;

        for (int i = 0; i < timeLabels.length; i++) {
            int labelX = bounds.x + (i * bounds.width / divisions);
            String label = timeLabels[i];
            int labelWidth = fm.stringWidth(label);
            g2d.drawString(label, labelX - labelWidth / 2, bottomY);
        }
    }

    public void setDataSeries(TimeseriesResponse timeseries, Timestep timestep, int offerPrice,
            long maxTimeRangeSeconds) {
        this.timeseries = timeseries;
        this.timestep = timestep;
        this.offerPrice = offerPrice;
        this.maxTimeRangeSeconds = maxTimeRangeSeconds;
    }

    public void setDataSeries(TimeseriesResponse timeseries, Timestep timestep, int offerPrice) {
        setDataSeries(timeseries, timestep, offerPrice, timestep.getMaxTimeRangeSeconds());
    }

    public boolean hasData() {
        boolean hasData = timeseries != null && timestep != null && timeseries.getData() != null
                && !timeseries.getData().isEmpty();
        return hasData;
    }

    @Override
    public void setPreferredLocation(Point position) {
        this.position.setLocation(position);
    }

    @Override
    public void setPreferredSize(Dimension dimension) {
        if (dimension != null) {
            this.mutableWidth = dimension.width;
            this.mutableHeight = dimension.height;
            this.dimension.setSize(dimension);
        }
    }

    public Rectangle getBounds() {
        return new Rectangle(position, dimension);
    }

    /**
     * Gets the data point closest to the given mouse position.
     * 
     * @return The closest data point, or null if mouse is outside the chart area
     */
    public TimeseriesPoint getHoveredDataPoint(int mouseX, int mouseY) {
        if (!hasData()) {
            return null;
        }

        List<TimeseriesPoint> dataPoints = filterAndSortData(timeseries.getData());
        if (dataPoints.isEmpty()) {
            return null;
        }

        // Check if mouse is within chart area (use full dimension)
        if (mouseX < position.x || mouseX > position.x + dimension.width ||
                mouseY < position.y || mouseY > position.y + dimension.height) {
            return null;
        }

        ChartBounds bounds = calculateChartBounds();
        TimeRange timeRange = calculateTimeRange(dataPoints);

        // Find closest data point to mouse X position
        TimeseriesPoint closest = null;
        long minTimeDiff = Long.MAX_VALUE;

        // Convert mouse X to timestamp (relative to plot area)
        float timePercent = (float) (mouseX - bounds.x) / bounds.width;
        // Clamp to valid range
        timePercent = Math.max(0, Math.min(1, timePercent));
        long mouseTimestamp = timeRange.start + (long) (timePercent * timeRange.range);

        for (TimeseriesPoint point : dataPoints) {
            long timeDiff = Math.abs(point.getTimestamp() - mouseTimestamp);
            if (timeDiff < minTimeDiff) {
                minTimeDiff = timeDiff;
                closest = point;
            }
        }

        return closest;
    }

    public TimeseriesResponse getTimeseries() {
        return timeseries;
    }

    public Timestep getTimestep() {
        return timestep;
    }

    public void setOfferPrice(int offerPrice) {
        this.offerPrice = offerPrice;
    }

    public TimeseriesPoint getLatestInstabuyDataPoint() {
        if (!hasData()) {
            return null;
        }

        List<TimeseriesPoint> dataPoints = filterAndSortData(timeseries.getData());
        if (dataPoints.isEmpty()) {
            return null;
        }
        return dataPoints.stream().filter(point -> point.getAvgHighPrice() != null).reduce((first, second) -> second)
                .orElse(null);
    }

    public TimeseriesPoint getLatestInstasellDataPoint() {
        if (!hasData()) {
            return null;
        }

        List<TimeseriesPoint> dataPoints = filterAndSortData(timeseries.getData());
        if (dataPoints.isEmpty()) {
            return null;
        }

        return dataPoints.stream().filter(point -> point.getAvgLowPrice() != null).reduce((first, second) -> second)
                .orElse(null);
    }

    /**
     * Calculates the price value from a Y position on the chart.
     * This is the inverse of calculateYPosition.
     */
    public int calculatePriceFromY(int mouseY, ChartBounds bounds, PriceRange priceRange) {
        int bottomEdge = bounds.getBottomEdge();
        int range = priceRange.getRange();
        // Inverse of: y = bottomEdge - (price - min) * height / range
        // price = min + (bottomEdge - y) * range / height
        int price = priceRange.min + ((bottomEdge - mouseY) * range / bounds.height);
        return Math.max(0, price);
    }

    /**
     * Calculates the price value from a Y position on the chart using the current
     * chart bounds.
     * Convenience overload for click handling.
     */
    public int calculatePriceFromY(int mouseY, Rectangle chartBounds) {
        if (!hasData()) {
            return 0;
        }

        List<TimeseriesPoint> dataPoints = filterAndSortData(timeseries.getData());
        ChartBounds bounds = calculateChartBounds();
        PriceRange priceRange = calculatePriceRange(dataPoints);
        priceRange = adjustPriceRange(priceRange);

        return calculatePriceFromY(mouseY, bounds, priceRange);
    }

    /**
     * Sets the Y position for the horizontal price hover line.
     * 
     * @param mouseY The Y coordinate of the mouse, or null to hide the line
     */
    public void setHoveredPriceY(Integer mouseY) {
        this.hoveredPriceY = mouseY;
    }

    /**
     * Gets the current hovered price Y position.
     */
    public Integer getHoveredPriceY() {
        return hoveredPriceY;
    }

    /**
     * Draws a horizontal line at the hovered price position.
     */
    private void drawHorizontalPriceLine(Graphics2D g2d, ChartBounds bounds, PriceRange priceRange) {
        if (hoveredPriceY == null) {
            return;
        }

        // Clamp Y to chart bounds
        int lineY = Math.max(bounds.y, Math.min(hoveredPriceY, bounds.getBottomEdge()));

        // Draw horizontal dashed line
        g2d.setStroke(new BasicStroke(HOVER_LINE_STROKE, BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, STROKE_ROUND_MITER,
                new float[] { 5.0f, 5.0f }, 0.0f));
        g2d.setColor(CustomColors.CHART_ACCENT);
        g2d.drawLine(bounds.x, lineY, bounds.getRightEdge(), lineY);

        // Calculate and display the price at this Y position
        long price = calculatePriceFromY(lineY, bounds, priceRange);
        String priceText = UIUtilities.quantityToRSDecimalStack(price, false);

        g2d.setFont(config.getLabelFont());
        FontMetrics fm = g2d.getFontMetrics();
        int textWidth = fm.stringWidth(priceText);

        // Draw price label at right edge of chart
        g2d.setColor(CustomColors.CHART_ACCENT);
        g2d.drawString(priceText, bounds.getRightEdge() - textWidth - LABEL_PADDING, lineY - 2);
    }
}
