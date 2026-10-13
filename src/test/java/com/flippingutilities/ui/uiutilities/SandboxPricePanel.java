package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.model.Timestep;
import com.flippingutilities.model.TimeseriesPoint;
import com.flippingutilities.model.TimeseriesResponse;
import com.flippingutilities.ui.widgets.graph.ChartConfig;
import com.flippingutilities.ui.widgets.graph.TimeSeriesChart;
import com.flippingutilities.utilities.SlotInfo;
import com.flippingutilities.utilities.SlotPredictedState;
import com.flippingutilities.utilities.WikiItemMargins;
import net.runelite.client.util.QuantityFormatter;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Swing host for the same chart and Quick Look widgets used by the real plugin. */
final class SandboxPricePanel extends JPanel implements AutoCloseable {
    private static final DateTimeFormatter HOVER_TIME = DateTimeFormatter.ofPattern("MMM d, HH:mm")
        .withZone(ZoneId.systemDefault());
    private final SandboxWikiData wiki;
    private final JLabel title = named(new JLabel("Select an item"), "Price item");
    private final JLabel reference = named(new JLabel(" "), "Offer reference");
    private final JComboBox<Timestep> timestep = named(new JComboBox<>(Timestep.values()), "Chart period");
    private final JButton refresh = named(new JButton("Refresh"), "Refresh prices");
    private final QuickLookPanel quickLook = named(new QuickLookPanel(), "Latest Wiki prices");
    private final JLabel latestStatus = named(new JLabel(" "), "Latest price status");
    private final JLabel chartStatus = named(new JLabel("Select an item to see its price history."), "Chart status");
    private final JLabel hover = named(new JLabel(" "), "Chart hover prices");
    private final ChartCanvas canvas = named(new ChartCanvas(), "Price chart");
    private final Timer animation = new Timer(80, event -> canvas.repaint());
    private int itemId;
    private int offerPrice;
    private boolean buy;
    private long generation;
    private boolean closed;
    private boolean loading;
    private WikiItemMargins latest;
    private TimeseriesResponse series;

    SandboxPricePanel(SandboxWikiData wiki) {
        this.wiki = wiki;
        setLayout(new BorderLayout(8, 8));
        setBorder(new EmptyBorder(12, 12, 12, 12));
        setPreferredSize(new Dimension(660, 560));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        JPanel heading = new JPanel(new BorderLayout(8, 4));
        heading.add(title, BorderLayout.NORTH);
        heading.add(reference, BorderLayout.CENTER);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        controls.add(timestep);
        controls.add(refresh);
        heading.add(controls, BorderLayout.SOUTH);
        JPanel prices = new JPanel(new BorderLayout());
        prices.add(quickLook, BorderLayout.CENTER);
        prices.add(latestStatus, BorderLayout.SOUTH);
        JPanel north = new JPanel(new BorderLayout(8, 8));
        north.add(heading, BorderLayout.NORTH);
        north.add(prices, BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
        JPanel footer = new JPanel(new GridLayout(3, 1, 0, 3));
        footer.add(chartStatus);
        footer.add(hover);
        footer.add(new JLabel("OSRS Wiki prices · Public market data; simulated offers stay in this sandbox."));
        add(footer, BorderLayout.SOUTH);
        quickLook.updateDetails(null, null);
        timestep.addActionListener(event -> load());
        refresh.addActionListener(event -> load());
        refresh.setEnabled(false);
    }

    void showItem(int itemId, String name, int offerPrice, boolean buy) {
        requireEdt();
        if (closed) throw new IllegalStateException("Price panel is closed");
        if (itemId <= 0 || offerPrice < 0) throw new IllegalArgumentException("Invalid item or offer price");
        this.itemId = itemId;
        this.offerPrice = offerPrice;
        this.buy = buy;
        title.setText(name + " (" + itemId + ")");
        reference.setText(offerPrice == 0 ? "Market prices" : (buy ? "Buy" : "Sell") + " offer: " + price(offerPrice));
        refresh.setEnabled(true);
        load();
    }

    private void load() {
        requireEdt();
        if (closed || itemId == 0) return;
        long request = ++generation;
        int requestedItem = itemId;
        Timestep requestedStep = (Timestep) timestep.getSelectedItem();
        series = null;
        latest = null;
        canvas.chart = null;
        loading = true;
        quickLook.updateDetails(null, null);
        latestStatus.setText("Loading latest Wiki prices…");
        chartStatus.setText("Loading price history…");
        hover.setText(" ");
        animation.start();
        canvas.repaint();
        wiki.latest().whenComplete((response, failure) -> SwingUtilities.invokeLater(() -> {
            if (closed || request != generation) return;
            if (failure != null) {
                latestStatus.setText("Latest prices unavailable. Refresh to retry.");
                return;
            }
            latest = response.getData().get(requestedItem);
            latestStatus.setText(latest == null ? "No latest Wiki prices for this item." : "Latest Wiki prices loaded.");
            updateQuickLook();
        }));
        wiki.timeseries(requestedItem, requestedStep).whenComplete((response, failure) -> SwingUtilities.invokeLater(() -> {
            if (closed || request != generation) return;
            loading = false;
            animation.stop();
            if (failure != null) {
                chartStatus.setText("Price history unavailable. Refresh to retry.");
            } else {
                long earliest = Instant.now().getEpochSecond() - requestedStep.getMaxTimeRangeSeconds();
                boolean usable = response.getData().stream().anyMatch(point -> point != null
                    && point.getTimestamp() >= earliest
                    && (point.getAvgHighPrice() != null || point.getAvgLowPrice() != null));
                if (usable) {
                    series = response;
                    chartStatus.setText("Wiki Insta Buy / Insta Sell · Hover over the chart for prices.");
                } else {
                    chartStatus.setText("No price history for this item and period.");
                }
            }
            canvas.repaint();
        }));
    }

    private void updateQuickLook() {
        SlotPredictedState state = latest == null ? SlotPredictedState.UNKNOWN
            : SlotPredictedState.getPredictedState(buy, offerPrice, latest.getLow(), latest.getHigh());
        quickLook.updateDetails(new SlotInfo(0, state, itemId, offerPrice, buy, false), latest);
        if (offerPrice == 0) {
            quickLook.offerCompetitivenessText.setText("");
            quickLook.toMakeOfferCompetitiveTest.setText("");
        }
    }

    @Override
    public void close() {
        requireEdt();
        closed = true;
        generation++;
        loading = false;
        animation.stop();
        refresh.setEnabled(false);
        timestep.setEnabled(false);
        // The shared Wiki service belongs to the sandbox, not this window.
    }

    private final class ChartCanvas extends JPanel {
        private final ChartLoadingAnimation spinner = new ChartLoadingAnimation();
        private TimeSeriesChart chart;
        private Dimension chartSize;

        ChartCanvas() {
            setPreferredSize(new Dimension(620, 240));
            setMinimumSize(new Dimension(320, 160));
            setBackground(CustomColors.CHART_BACKGROUND);
            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent event) {
                    if (chart == null || closed) return;
                    TimeseriesPoint point = chart.getHoveredDataPoint(event.getX(), event.getY());
                    chart.setHoveredPoint(point);
                    chart.setHoveredPriceY(point == null ? null : event.getY());
                    hover.setText(point == null ? " " : HOVER_TIME.format(Instant.ofEpochSecond(point.getTimestamp()))
                        + " · Insta Buy: " + price(point.getAvgHighPrice())
                        + " · Insta Sell: " + price(point.getAvgLowPrice()));
                    repaint();
                }

                @Override public void mouseExited(MouseEvent event) {
                    if (chart == null) return;
                    chart.setHoveredPoint(null);
                    chart.setHoveredPriceY(null);
                    hover.setText(" ");
                    repaint();
                }
            };
            addMouseMotionListener(mouse);
            addMouseListener(mouse);
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (getWidth() < 80 || getHeight() < 60) return;
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                if (loading) {
                    spinner.render(copy, new Rectangle(0, 0, getWidth(), getHeight()), System.currentTimeMillis());
                } else if (series != null) {
                    Dimension size = getSize();
                    // The production chart also uses config dimensions for axis labels and hit testing.
                    if (chart == null || !size.equals(chartSize)) {
                        chartSize = size;
                        int referencePrice = offerPrice;
                        if (referencePrice == 0) {
                            // A market-only view has no simulated offer; avoid expanding the axis down to zero.
                            referencePrice = series.getData().stream()
                                .filter(point -> point.getAvgHighPrice() != null || point.getAvgLowPrice() != null)
                                .map(point -> point.getAvgHighPrice() != null ? point.getAvgHighPrice() : point.getAvgLowPrice())
                                .findFirst().orElse(0);
                        }
                        chart = new TimeSeriesChart(ChartConfig.builder().width(size.width).height(size.height)
                            .referenceLineColor(offerPrice == 0 ? new Color(0, 0, 0, 0) : CustomColors.CHART_ACCENT).build());
                        chart.setPreferredSize(size);
                        chart.setDataSeries(series, (Timestep) timestep.getSelectedItem(), referencePrice);
                    }
                    chart.render(copy);
                }
            } finally {
                copy.dispose();
            }
        }
    }

    private static String price(Integer value) {
        return value == null ? "No data" : QuantityFormatter.formatNumber(value) + " gp";
    }

    private static <T extends JComponent> T named(T component, String name) {
        component.setName(name);
        component.getAccessibleContext().setAccessibleName(name);
        return component;
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Use the Swing event thread");
    }
}
