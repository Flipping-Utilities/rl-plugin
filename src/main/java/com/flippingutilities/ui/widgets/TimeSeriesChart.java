package com.flippingutilities.ui.widgets;

import com.flippingutilities.model.Timestep;
import com.flippingutilities.model.TimeseriesPoint;
import com.flippingutilities.model.TimeseriesResponse;
import com.flippingutilities.ui.uiutilities.UIUtilities;
import com.flippingutilities.ui.widgets.chart.ChartBounds;
import com.flippingutilities.ui.widgets.chart.PriceRange;
import com.flippingutilities.ui.widgets.chart.TimeRange;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public final class TimeSeriesChart implements LayoutableRenderableEntity {

    private static final int PRICE_RANGE_MIN_THRESHOLD = 10;
    private static final int PRICE_RANGE_SMALL_MARGIN = 20;
    private static final int PRICE_RANGE_MARGIN_DIVISOR = 10;
    private static final int MIN_PRICE_MARGIN = 5;

    private static final int LABEL_PADDING = 5;
    private static final int OFFER_LABEL_OFFSET = 3;

    private static final float GRID_DASH_LENGTH = 2.0f;
    private static final float OFFER_LINE_DASH_LENGTH = 5.0f;
    private static final float STROKE_ROUND_MITER = 10.0f;

    private static final int DEFAULT_LEFT_PADDING = 38;

    private static final String OFFER_LABEL_TEXT = "Your offer";

    private final ChartConfig config;
    private final TickIntervalCalculator tickCalculator;

    private final Point position = new Point();
    private final Dimension dimension = new Dimension();

    private TimeseriesResponse timeseries;
    private Timestep timestep;
    private int offerPrice;

    public TimeSeriesChart(ChartConfig config) {
        this.config = config;
        this.tickCalculator = new TickIntervalCalculator();
    }

    @Override
    public Dimension render(Graphics2D g2d) {
        if (!hasData()) {
            return new Dimension();
        }

        setupRenderingHints(g2d);
        drawBackground(g2d);

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
        drawBorder(g2d, bounds);

        dimension.setSize(config.getWidth(), config.getHeight());
        return dimension;
    }

    private void setupRenderingHints(Graphics2D g2d) {
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    private void drawBackground(Graphics2D g2d) {
        g2d.setColor(config.getBackgroundColor());
        g2d.fillRect(position.x, position.y, config.getWidth(), config.getHeight());
    }

    private List<TimeseriesPoint> filterAndSortData(List<TimeseriesPoint> dataPoints) {
        List<TimeseriesPoint> sorted = new ArrayList<>(dataPoints);

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
        long minPrice = Long.MAX_VALUE;
        long maxPrice = Long.MIN_VALUE;

        for (TimeseriesPoint point : dataPoints) {
            if (point.getAvgHighPrice() != null) {
                int highPrice = point.getAvgHighPrice();
                maxPrice = Math.max(maxPrice, highPrice);
            }

            if (point.getAvgLowPrice() != null) {
                int lowPrice = point.getAvgLowPrice();
                minPrice = Math.min(minPrice, lowPrice);
            }
        }

        maxPrice = Math.max(maxPrice, offerPrice);
        minPrice = Math.min(minPrice, offerPrice);

        return new PriceRange(minPrice, maxPrice);
    }

    private PriceRange adjustPriceRange(PriceRange original) {
        long range = original.getRange();
        long min = original.min;
        long max = original.max;

        if (range < PRICE_RANGE_MIN_THRESHOLD) {
            long midPrice = (max + min) / 2;
            max = midPrice + PRICE_RANGE_SMALL_MARGIN;
            min = midPrice - PRICE_RANGE_SMALL_MARGIN;
        } else {
            long margin = Math.max(range / PRICE_RANGE_MARGIN_DIVISOR, MIN_PRICE_MARGIN);
            max += margin;
            min = Math.max(0, min - margin);
        }

        return new PriceRange(min, max);
    }

    private ChartBounds calculateChartBounds() {
        int x = position.x + DEFAULT_LEFT_PADDING;
        int y = position.y + config.getTopPadding();
        int width = config.getWidth() - DEFAULT_LEFT_PADDING - config.getRightPadding();
        int height = config.getHeight() - config.getTopPadding() - config.getBottomPadding();

        return new ChartBounds(x, y, width, height);
    }

    private TimeRange calculateTimeRange(List<TimeseriesPoint> filteredData) {
        if (filteredData.isEmpty()) {
            long currentTime = System.currentTimeMillis() / 1000;
            return new TimeRange(currentTime - timestep.getMaxTimeRangeSeconds(), 1);
        }

        TimeseriesPoint first = filteredData.get(0);
        TimeseriesPoint last = filteredData.get(filteredData.size() - 1);
        long start = first.getTimestamp();
        long range = last.getTimestamp() - start;

        return new TimeRange(start, range == 0 ? timestep.getMaxTimeRangeSeconds() : range);
    }

    private void drawGrid(Graphics2D g2d, ChartBounds bounds, PriceRange priceRange) {
        g2d.setColor(config.getGridColor());
        g2d.setStroke(new BasicStroke(config.getGridStroke(), BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, STROKE_ROUND_MITER,
                new float[]{GRID_DASH_LENGTH, GRID_DASH_LENGTH}, 0.0f));

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
                new float[]{ OFFER_LINE_DASH_LENGTH, OFFER_LINE_DASH_LENGTH }, 0.0f));
        g2d.setColor(config.getReferenceLineColor());

        int rightEdge = bounds.getRightEdge();
        g2d.drawLine(bounds.x, offerY, rightEdge, offerY);

        g2d.setFont(config.getLabelFont());
        g2d.setColor(config.getReferenceLineColor());
        FontMetrics fm = g2d.getFontMetrics();
        int labelWidth = fm.stringWidth(OFFER_LABEL_TEXT);
        g2d.drawString(OFFER_LABEL_TEXT, rightEdge - labelWidth - LABEL_PADDING,
                offerY - OFFER_LABEL_OFFSET);
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

    private void drawBorder(Graphics2D g2d, ChartBounds bounds) {
        g2d.setStroke(new BasicStroke(1.0f));
        g2d.setColor(config.getGridColor());
        g2d.drawRect(bounds.x, bounds.y, bounds.width, bounds.height);
    }

    public void setDataSeries(TimeseriesResponse timeseries, Timestep timestep, int offerPrice) {
        this.timeseries = timeseries;
        this.timestep = timestep;
        this.offerPrice = offerPrice;
    }

    public boolean hasData() {
        return timeseries != null && timestep != null && timeseries.getData() != null && !timeseries.getData().isEmpty();
    }

    @Override
    public void setPreferredLocation(Point position) {
        this.position.setLocation(position);
    }

    @Override
    public void setPreferredSize(Dimension dimension) {
    }

    @Override
    public Rectangle getBounds() {
        return new Rectangle(position, dimension);
    }
}
