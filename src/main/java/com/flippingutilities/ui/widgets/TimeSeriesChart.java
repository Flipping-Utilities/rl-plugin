package com.flippingutilities.ui.widgets;

import com.flippingutilities.model.TimeseriesPoint;
import com.flippingutilities.model.TimeseriesResponse;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class TimeSeriesChart implements LayoutableRenderableEntity {

    private static final long TWENTY_FOUR_HOURS_SECONDS = 24 * 60 * 60;

    private static final long HUNDRED_MILLION = 100_000_000L;
    private static final long TEN_MILLION = 10_000_000L;
    private static final long ONE_MILLION = 1_000_000L;
    private static final long HUNDRED_THOUSAND = 100_000L;
    private static final long TEN_THOUSAND = 10_000L;
    private static final long ONE_THOUSAND = 1_000L;

    private final ChartConfig config;
    private final PriceFormatter priceFormatter;
    private final TickIntervalCalculator tickCalculator;

    private final Point position = new Point();
    private final Dimension dimension = new Dimension();

    private TimeseriesResponse timeseries;
    private int offerPrice;

    public TimeSeriesChart(ChartConfig config) {
        this.config = config;
        this.priceFormatter = new PriceFormatter();
        this.tickCalculator = new TickIntervalCalculator();
    }

    public void setDataSeries(TimeseriesResponse timeseries, int offerPrice) {
        this.timeseries = timeseries;
        this.offerPrice = offerPrice;
    }

    public boolean hasData() {
        return timeseries != null && timeseries.getData() != null && !timeseries.getData().isEmpty();
    }

    @Override
    public Dimension render(Graphics2D g2d) {
        if (!hasData()) {
            return new Dimension();
        }

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g2d.setColor(config.getBackgroundColor());
        g2d.fillRect(position.x, position.y, config.getWidth(), config.getHeight());

        List<TimeseriesPoint> dataPoints = timeseries.getData();
        if (dataPoints.isEmpty()) {
            dimension.setSize(config.getWidth(), config.getHeight());
            return dimension;
        }

        List<TimeseriesPoint> filteredData = filterLast24Hours(dataPoints);

        long minPrice = calculateMinPrice(filteredData);
        long maxPrice = calculateMaxPrice(filteredData);

        maxPrice = Math.max(maxPrice, offerPrice);
        minPrice = Math.min(minPrice, offerPrice);

        int dynamicLeftPadding = calculateLeftPadding(maxPrice);

        int graphX = position.x + dynamicLeftPadding;
        int graphY = position.y + config.getTopPadding();
        int graphWidth = config.getWidth() - dynamicLeftPadding - config.getRightPadding();
        int graphHeight = config.getHeight() - config.getTopPadding() - config.getBottomPadding();

        long priceRange = maxPrice - minPrice;
        if (priceRange < 10) {
            long midPrice = (maxPrice + minPrice) / 2;
            maxPrice = midPrice + 20;
            minPrice = midPrice - 20;
        } else {
            long margin = Math.max(priceRange / 10, 5);
            maxPrice += margin;
            minPrice = Math.max(0, minPrice - margin);
        }
        priceRange = maxPrice - minPrice;

        drawGrid(g2d, graphX, graphY, graphWidth, graphHeight, minPrice, maxPrice, priceRange);

        long timeRange = calculateTimeRange(filteredData);
        long startTime = filteredData.isEmpty() ?
            (System.currentTimeMillis() / 1000 - TWENTY_FOUR_HOURS_SECONDS) :
            filteredData.get(0).getTimestamp();

        List<Point> highPoints = new ArrayList<>();
        List<Point> lowPoints = new ArrayList<>();

        for (TimeseriesPoint point : filteredData) {
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

        drawFillBetweenLines(g2d, highPoints, lowPoints);

        g2d.setStroke(new BasicStroke(config.getLineStroke(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        drawLine(g2d, highPoints, config.getHighLineColor());
        drawLine(g2d, lowPoints, config.getLowLineColor());

        drawOfferLine(g2d, graphX, graphY, graphWidth, graphHeight, minPrice, priceRange);

        drawTimeLabels(g2d, graphX, graphWidth);

        g2d.setStroke(new BasicStroke(1.0f));
        g2d.setColor(config.getGridColor());
        g2d.drawRect(graphX, graphY, graphWidth, graphHeight);

        dimension.setSize(config.getWidth(), config.getHeight());
        return dimension;
    }

    private List<TimeseriesPoint> filterLast24Hours(List<TimeseriesPoint> dataPoints) {
        long currentTime = System.currentTimeMillis() / 1000;
        long twentyFourHoursAgo = currentTime - TWENTY_FOUR_HOURS_SECONDS;

        List<TimeseriesPoint> last24Hours = dataPoints.stream()
                .filter(p -> p.getTimestamp() >= twentyFourHoursAgo)
                .sorted((a, b) -> Long.compare(a.getTimestamp(), b.getTimestamp()))
                .collect(Collectors.toList());

        if (last24Hours.isEmpty()) {
            return new ArrayList<>(dataPoints);
        }

        return last24Hours;
    }

    private long calculateMinPrice(List<TimeseriesPoint> dataPoints) {
        long minPrice = Long.MAX_VALUE;

        for (TimeseriesPoint point : dataPoints) {
            Integer lowPrice = point.getAvgLowPrice();
            if (lowPrice != null) {
                minPrice = Math.min(minPrice, lowPrice);
            }
        }

        return minPrice;
    }

    private long calculateMaxPrice(List<TimeseriesPoint> dataPoints) {
        long maxPrice = Long.MIN_VALUE;

        for (TimeseriesPoint point : dataPoints) {
            Integer highPrice = point.getAvgHighPrice();
            if (highPrice != null) {
                maxPrice = Math.max(maxPrice, highPrice);
            }
        }

        return maxPrice;
    }

    private long calculateTimeRange(List<TimeseriesPoint> filteredData) {
        if (filteredData.isEmpty()) {
            return 1;
        }

        long timeRange = filteredData.get(filteredData.size() - 1).getTimestamp() -
                        filteredData.get(0).getTimestamp();

        return timeRange == 0 ? TWENTY_FOUR_HOURS_SECONDS : timeRange;
    }

    private void drawGrid(Graphics2D g2d, int graphX, int graphY, int graphWidth, int graphHeight,
                         long minPrice, long maxPrice, long priceRange) {
        g2d.setColor(config.getGridColor());
        g2d.setStroke(new BasicStroke(config.getGridStroke(), BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, 10.0f, new float[]{2.0f, 2.0f}, 0.0f));

        g2d.setFont(config.getLabelFont());
        FontMetrics fm = g2d.getFontMetrics();

        long tickInterval = tickCalculator.calculate(priceRange);
        long startPrice = (minPrice / tickInterval) * tickInterval;
        long endPrice = ((maxPrice / tickInterval) + 1) * tickInterval;

        for (long price = startPrice; price <= endPrice; price += tickInterval) {
            if (price < minPrice || price > maxPrice) {
                continue;
            }

            int lineY = graphY + graphHeight - (int)((price - minPrice) * graphHeight / priceRange);

            g2d.setColor(config.getGridColor());
            g2d.drawLine(graphX, lineY, graphX + graphWidth, lineY);

            String priceText = priceFormatter.format(price);
            g2d.setColor(config.getLabelColor());
            int textWidth = fm.stringWidth(priceText);
            g2d.drawString(priceText, graphX - textWidth - 5, lineY + fm.getAscent() / 2);
        }

        for (int i = 0; i <= 4; i++) {
            int lineX = graphX + (i * graphWidth / 4);
            g2d.setColor(config.getGridColor());
            g2d.drawLine(lineX, graphY, lineX, graphY + graphHeight);
        }
    }

    private void drawFillBetweenLines(Graphics2D g2d, List<Point> highPoints, List<Point> lowPoints) {
        if (highPoints.size() > 1 && lowPoints.size() > 1) {
            int[] fillX = new int[highPoints.size() + lowPoints.size()];
            int[] fillY = new int[highPoints.size() + lowPoints.size()];

            for (int i = 0; i < highPoints.size(); i++) {
                fillX[i] = highPoints.get(i).x;
                fillY[i] = highPoints.get(i).y;
            }

            for (int i = 0; i < lowPoints.size(); i++) {
                int idx = highPoints.size() + i;
                fillX[idx] = lowPoints.get(lowPoints.size() - 1 - i).x;
                fillY[idx] = lowPoints.get(lowPoints.size() - 1 - i).y;
            }

            g2d.setColor(config.getFillColor());
            g2d.fillPolygon(fillX, fillY, fillX.length);
        }
    }

    private void drawLine(Graphics2D g2d, List<Point> points, java.awt.Color color) {
        if (points.size() > 1) {
            g2d.setColor(color);
            for (int i = 0; i < points.size() - 1; i++) {
                Point p1 = points.get(i);
                Point p2 = points.get(i + 1);
                g2d.drawLine(p1.x, p1.y, p2.x, p2.y);
            }
        }
    }

    private void drawOfferLine(Graphics2D g2d, int graphX, int graphY, int graphWidth, int graphHeight,
                               long minPrice, long priceRange) {
        int offerY = graphY + graphHeight - (int) ((offerPrice - minPrice) * graphHeight / priceRange);
        g2d.setStroke(new BasicStroke(config.getReferenceStroke(), BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, 10.0f, new float[]{5.0f, 5.0f}, 0.0f));
        g2d.setColor(config.getReferenceLineColor());
        g2d.drawLine(graphX, offerY, graphX + graphWidth, offerY);

        String offerLabel = "Your offer";
        g2d.setFont(config.getLabelFont());
        g2d.setColor(config.getReferenceLineColor());
        FontMetrics fm = g2d.getFontMetrics();
        int labelWidth = fm.stringWidth(offerLabel);
        g2d.drawString(offerLabel, graphX + graphWidth - labelWidth - 5, offerY - 3);
    }

    private void drawTimeLabels(Graphics2D g2d, int graphX, int graphWidth) {
        g2d.setColor(config.getLabelColor());
        g2d.setFont(config.getLabelFont());
        FontMetrics fm = g2d.getFontMetrics();

        String[] timeLabels = {"24h", "18h", "12h", "6h", "Now"};
        for (int i = 0; i < 5; i++) {
            int labelX = graphX + (i * graphWidth / 4);
            String label = timeLabels[i];
            int labelWidth = fm.stringWidth(label);
            g2d.drawString(label, labelX - labelWidth / 2,
                position.y + config.getHeight() - 5);
        }
    }

    private int calculateLeftPadding(long maxPrice) {
        if (maxPrice >= HUNDRED_MILLION) {
            return 55;
        }

        if (maxPrice >= TEN_MILLION) {
            return 50;
        }

        if (maxPrice >= ONE_MILLION) {
            return 48;
        }

        if (maxPrice >= HUNDRED_THOUSAND) {
            return 42;
        }

        if (maxPrice >= TEN_THOUSAND) {
            return 38;
        }

        if (maxPrice >= ONE_THOUSAND) {
            return 35;
        }

        return 30;
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
