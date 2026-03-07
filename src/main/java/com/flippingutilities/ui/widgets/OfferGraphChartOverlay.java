package com.flippingutilities.ui.widgets;

import com.flippingutilities.FlippingConfig;
import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.jobs.TimeseriesFetcher;
import com.flippingutilities.model.Timestep;
import com.flippingutilities.model.TimeseriesPoint;
import com.flippingutilities.ui.uiutilities.CustomColors;
import com.flippingutilities.ui.uiutilities.ChartLoadingAnimation;
import com.flippingutilities.ui.uiutilities.TimeFormatters;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.MenuAction;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import javax.inject.Singleton;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.MouseEvent;
import java.util.Arrays;

/**
 * Overlay that renders a price history graph over the chatbox area.
 * Will eat the clicks so it can handle the time buttons and set a price on new offers.
 */
@Slf4j
@Singleton
public class OfferGraphChartOverlay extends Overlay implements MouseListener {
    private static final int BUTTON_ROW_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 28;
    private static final int BUTTON_GAP = 4;
    private static final int PADDING = 5;

    private final Client client;
    private final ClientThread clientThread;
    private final TimeseriesFetcher timeseriesFetcher;
    private final FlippingConfig config;
    private final FlippingPlugin plugin;

    private OverlayManager overlayManager;
    private MouseManager mouseManager;
    private Rectangle graphBounds = new Rectangle();
    private Rectangle chartBounds = new Rectangle();
    private final int[] intervalButtonStartPosition = new int[4];
    private int priceToSet = -1;
    private int currentItemId = -1;
    private int currentOfferPrice = 0;
    private GraphDuration selectedDuration;

    // Price info to display
    private String instabuyText = "Instabuy: -";
    private String instasellText = "Instasell: -";

    private ChartLoadingAnimation chartLoadingAnimation;
    private TimeSeriesChart chart;

    /**
     * Duration options for the graph.
     */
    public enum GraphDuration {
        ONE_DAY("1d", Timestep.FIVE_MINUTES, 24 * 60 * 60),
        ONE_WEEK("1w", Timestep.ONE_HOUR, 7 * 24 * 60 * 60),
        ONE_MONTH("1m", Timestep.SIX_HOURS, 30 * 24 * 60 * 60),
        ONE_YEAR("1y", Timestep.TWENTY_FOUR_HOURS, 365 * 24 * 60 * 60);

        private final String label;
        private final Timestep timestep;
        private final long maxTimeRangeSeconds;

        GraphDuration(String label, Timestep timestep, long maxTimeRangeSeconds) {
            this.label = label;
            this.timestep = timestep;
            this.maxTimeRangeSeconds = maxTimeRangeSeconds;
        }

        public Timestep getTimestep() {
            return this.timestep;
        }

        public String getLabel() {
            return this.label;
        }

        public long getMaxTimeRangeSeconds() {
            return this.maxTimeRangeSeconds;
        }

        /**
         * Converts a Timestep to the corresponding GraphDuration.
         * (Wiki API format granular format to total chart length)
         */
        public static GraphDuration fromTimestep(Timestep timestep) {
            if (timestep == null) {
                return ONE_DAY;
            }
            switch (timestep) {
                case ONE_HOUR:
                    return ONE_WEEK;
                case SIX_HOURS:
                    return ONE_MONTH;
                case TWENTY_FOUR_HOURS:
                    return ONE_YEAR;
                case FIVE_MINUTES:
                default:
                    return ONE_DAY;
            }
        }
    }

    @Inject
    public OfferGraphChartOverlay(Client client, ClientThread clientThread, 
                                   TimeseriesFetcher timeseriesFetcher, EventBus eventBus,
                                   FlippingConfig config, FlippingPlugin plugin) {
        this.client = client;
        this.clientThread = clientThread;
        this.timeseriesFetcher = timeseriesFetcher;
        this.config = config;
        this.plugin = plugin;
        this.selectedDuration = GraphDuration.fromTimestep(config.priceGraphTimestep());
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(PRIORITY_LOW);
        eventBus.register(this);
        initChart();
    }
    private void initChart() {
        ChartConfig chartConfig = ChartConfig.builder()
                .labelFont(FontManager.getRunescapeSmallFont())
                .build();
        this.chart = new TimeSeriesChart(chartConfig);
    }

    @Inject
    private void register(OverlayManager overlayManager, MouseManager mouseManager) {
        this.overlayManager = overlayManager;
        this.mouseManager = mouseManager;
        overlayManager.add(this);
        mouseManager.registerMouseListener(this);
    }

    @Subscribe
    public void onClientShutdown(ClientShutdown event) {
        if (overlayManager != null) {
            overlayManager.remove(this);
        }
        if (mouseManager != null) {
            mouseManager.unregisterMouseListener(this);
        }
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event) {
        // Handle GE slot selection changes
        if (event.getVarbitId() == VarbitID.GE_SELECTEDSLOT) {
            handleSlotSelectionChange();
            return;
        }
        
        // Handle offer price changes
        if (event.getVarbitId() == VarbitID.GE_NEWOFFER_PRICE) {
            handleOfferPriceChange();
            return;
        }
        
        // Handle item search changes
        if (event.getVarpId() == VarPlayerID.TRADINGPOST_SEARCH) {
            int varpValue = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
            if (varpValue != -1 && varpValue != 0) {
                // Item selected in search
                showGraphForItem(varpValue);
            } else {
                hide();
            }
        }
    }

    private void handleOfferPriceChange() {
        if (currentItemId <= 0) {
            return;
        }
        int newPrice = client.getVarbitValue(VarbitID.GE_NEWOFFER_PRICE);
        if (newPrice > 0 && newPrice != currentOfferPrice) {
            currentOfferPrice = newPrice;
            if (chart != null) {
                chart.setOfferPrice(newPrice);
            }
        }
    }

    private void handleSlotSelectionChange() {
        int slot = client.getVarbitValue(VarbitID.GE_SELECTEDSLOT) - 1;
        
        if (slot == -1) {
            hide();
            return;
        }
        
        // Get item from selected slot
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (slot >= 0 && slot < offers.length) {
            GrandExchangeOffer offer = offers[slot];
            if (offer != null && offer.getItemId() > 0) {
                showGraphForItem(offer.getItemId());
            } else {
                hide();
            }
        }
    }

    private void showGraphForItem(int itemId) {
        if (!plugin.getApiAuthHandler().isPremium()) {
            return;
        }
        if (!config.offerPageChartEnabled()) {
            return;
        }
        
        int offerPrice = getOfferPriceFromClient(itemId);
        show(itemId, offerPrice);
    }

    private int getOfferPriceFromClient(int itemId) {
        int offerPrice = 0;
        
        // Get the price from the existing offer
        int selectedSlot = client.getVarbitValue(VarbitID.GE_SELECTEDSLOT) - 1;
        if (selectedSlot >= 0 && selectedSlot < client.getGrandExchangeOffers().length) {
            GrandExchangeOffer offer = client.getGrandExchangeOffers()[selectedSlot];
            if (offer != null && offer.getItemId() == itemId) {
                offerPrice = offer.getPrice();
            }
        }
        
        // For new offers, get price from varbit
        if (offerPrice == 0) {
            offerPrice = client.getVarbitValue(VarbitID.GE_NEWOFFER_PRICE);
        }
        
        return offerPrice;
    }

    /*
     * Enable the chart for the specified item & offer price
     */
    private void show(int itemId, int offerPrice) {
        this.currentItemId = itemId;
        this.currentOfferPrice = offerPrice;
        if (chart != null) {
            chart.setOfferPrice(offerPrice);
        }
        fetchGraphData();
    }

    /**
     * Hide the chart
     */
    public void hide() {
        this.currentItemId = -1;
    }

    public void setSelectedDuration(GraphDuration duration) {
        if (duration == null || duration == selectedDuration) {
            return;
        }
        selectedDuration = duration;
        fetchGraphData();
    }

    private void fetchGraphData() {
        if (timeseriesFetcher == null || currentItemId <= 0 || chart == null) {
            return;
        }
        timeseriesFetcher.fetch(currentItemId, selectedDuration.getTimestep(), response -> {
            if (chart != null && response != null && response.getData() != null) {
                if (response.getData().isEmpty()) {
                    log.warn("[OfferGraphChartOverlay] No price history data for item {}", currentItemId);
                } else {
                    chart.setDataSeries(response, selectedDuration.getTimestep(), 
                        currentOfferPrice, selectedDuration.getMaxTimeRangeSeconds());
                }
            }
        });
    }

    private boolean isOfferCreation() {
        return checkGeBoxText("Grand Exchange: Set up offer");
    }

    private boolean isOfferStatus() {
        return checkGeBoxText("Grand Exchange: Offer status");
    }

    /**
     * Checks if the GE offers frame widget contains the specified text.
     */
    private boolean checkGeBoxText(String text) {
        Widget geBox = client.getWidget(InterfaceID.GeOffers.FRAME);
        if (geBox == null) {
            return false;
        }
        return Arrays.stream(geBox.getChildren()).anyMatch(w -> w.getText().contains(text));
    }

    private boolean isSelectingItem() {
        Widget chatBox = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
        if (chatBox == null) {
            return false;
        }
        return chatBox.getText().contains("What would you like to buy?");
    }

    /**
     * Checks if the price/quantity input chatbox is open.
     */
    private boolean isInputModeOpen() {
        return client.getVarcIntValue(VarClientID.MESLAYERMODE) == 7;
    }

    /**
     * Computes whether the graph should be rendered based on current state.
     */
    private boolean shouldRender() {
        return currentItemId > 0
            && !isInputModeOpen()
            && !isSelectingItem()
            && (isOfferCreation() || isOfferStatus());
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!shouldRender()) {
            return null;
        }

        Widget chatbox = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
        if (chatbox == null) {
            return null;
        }

        Rectangle chatboxBounds = chatbox.getBounds();
        if (chatboxBounds == null || chatboxBounds.isEmpty()) {
            return null;
        }

        if (chart == null) {
            return null;
        }

        int graphX = chatboxBounds.x + PADDING;
        int graphY = chatboxBounds.y + PADDING;
        int graphWidth = chatboxBounds.width - PADDING * 2;

        int availableHeight = chatboxBounds.height - PADDING * 2;
        int chartHeight = Math.min(availableHeight - BUTTON_ROW_HEIGHT, 180);
        if (chartHeight < 80) {
            chartHeight = 80; // Minimum height
        }

        int totalHeight = BUTTON_ROW_HEIGHT + chartHeight + PADDING * 2;

        // Store bounds for click detection
        graphBounds = new Rectangle(graphX, graphY, graphWidth, totalHeight);

        // Draw semi-transparent background
        graphics.setColor(CustomColors.CHART_PANEL_BG);
        graphics.fillRect(graphX, graphY, graphWidth, totalHeight);

        // Draw border
        graphics.setColor(CustomColors.CHART_PANEL_BORDER);
        graphics.drawRect(graphX, graphY, graphWidth, totalHeight);

        drawDurationButtons(graphics, graphX, graphY);

        drawPriceInfo(graphics, graphX, graphY, graphWidth);

        int chartY = graphY + BUTTON_ROW_HEIGHT + 2;
        int chartWidth = graphWidth - PADDING * 2;
        chartBounds = new Rectangle(graphX + PADDING, chartY, chartWidth, chartHeight);

        Shape originalClip = graphics.getClip();
        graphics.setClip(chartBounds);
        if (chart.hasData()) {
            chart.setPreferredLocation(new Point(chartBounds.x, chartBounds.y));
            chart.setPreferredSize(new Dimension(chartBounds.width, chartBounds.height));

            // Update hover state and price info using the same chart bounds
            updateHoverStateAndPriceInfo(chart, chartBounds);

            chart.render(graphics);
        } else {
            if (chartLoadingAnimation == null) {
                chartLoadingAnimation = new ChartLoadingAnimation();
            }
            chartLoadingAnimation.render(graphics, chartBounds, System.currentTimeMillis());
        }

        graphics.setClip(originalClip);
        return new Dimension(graphWidth, totalHeight);
    }

    private void drawDurationButtons(Graphics2D graphics, int graphX, int graphY) {
        graphics.setFont(FontManager.getRunescapeSmallFont());
        GraphDuration[] durations = GraphDuration.values();

        int buttonX = graphX + PADDING;

        for (int i = 0; i < durations.length; i++) {
            GraphDuration duration = durations[i];
            boolean selected = duration == this.selectedDuration;

            intervalButtonStartPosition[i] = buttonX;

            // Button background
            graphics.setColor(
                    selected ? CustomColors.CHART_BUTTON_SELECTED_BG : CustomColors.CHART_BUTTON_UNSELECTED_BG);
            graphics.fillRect(buttonX, graphY + 2, BUTTON_WIDTH, BUTTON_ROW_HEIGHT - 4);

            // Button border
            graphics.setColor(
                    selected ? CustomColors.CHART_BUTTON_SELECTED_BORDER : CustomColors.CHART_BUTTON_UNSELECTED_BORDER);
            graphics.drawRect(buttonX, graphY + 2, BUTTON_WIDTH, BUTTON_ROW_HEIGHT - 4);

            // Button text
            graphics.setColor(
                    selected ? CustomColors.CHART_BUTTON_SELECTED_TEXT : CustomColors.CHART_BUTTON_UNSELECTED_TEXT);
            FontMetrics fm = graphics.getFontMetrics();
            int textWidth = fm.stringWidth(duration.getLabel());
            graphics.drawString(duration.getLabel(), buttonX + (BUTTON_WIDTH - textWidth) / 2,
                    graphY + BUTTON_ROW_HEIGHT / 2 + 6);

            buttonX += BUTTON_WIDTH + BUTTON_GAP;
        }
    }

    /**
     * Draws the currently hovered or latest price information
     */
    private void drawPriceInfo(Graphics2D graphics, int graphX, int graphY, int graphWidth) {
        graphics.setFont(FontManager.getRunescapeSmallFont());
        FontMetrics fm = graphics.getFontMetrics();

        int textHeight = fm.getHeight();
        int y = graphY + textHeight;

        // Calculate width needed for price info
        int buyWidth = fm.stringWidth(instabuyText);
        int sellWidth = fm.stringWidth(instasellText);
        int maxWidth = Math.max(buyWidth, sellWidth);

        int rightX = graphX + graphWidth - maxWidth - PADDING - 10;

        graphics.setColor(CustomColors.CHART_INSTABUY_TEXT);
        graphics.drawString(instabuyText, rightX, y);

        y += textHeight;
        graphics.setColor(CustomColors.CHART_INSTASELL_TEXT);
        graphics.drawString(instasellText, rightX, y);
    }

    @Override
    public MouseEvent mouseClicked(MouseEvent e) {
        return e;
    }

    @Override
    public MouseEvent mousePressed(MouseEvent e) {
        return handleMouseEvent(e, true);
    }

    @Override
    public MouseEvent mouseReleased(MouseEvent e) {
        return e;
    }

    @Override
    public MouseEvent mouseEntered(MouseEvent e) {
        return e;
    }

    @Override
    public MouseEvent mouseExited(MouseEvent e) {
        return e;
    }

    @Override
    public MouseEvent mouseDragged(MouseEvent e) {
        return e;
    }

    @Override
    public MouseEvent mouseMoved(MouseEvent e) {
        return handleMouseEvent(e, false);
    }

    private MouseEvent handleMouseEvent(MouseEvent e, boolean checkButtonClick) {
        if (!shouldRender()) {
            return e;
        }
        Point p = e.getPoint();
        if (graphBounds == null || graphBounds.isEmpty() || !graphBounds.contains(p)) {
            return e;
        }

        if (checkButtonClick) {
            int buttonY = graphBounds.y + 2;

            // Check if click is on duration buttons
            if (p.y >= buttonY && p.y <= buttonY + BUTTON_ROW_HEIGHT - 4) {
                GraphDuration[] durations = GraphDuration.values();
                for (int i = 0; i < durations.length; i++) {
                    if (p.x >= intervalButtonStartPosition[i] && p.x <= intervalButtonStartPosition[i] + BUTTON_WIDTH) {
                        setSelectedDuration(durations[i]);
                        e.consume();
                        return e;
                    }
                }
            }
            // Check if click is in chart area - set price
            else if (chartBounds.contains(p) && chart != null && chart.hasData()) {
                handleChartClick(p.y);
                e.consume();
            }
        }
        return e;
    }

    private void updateHoverStateAndPriceInfo(TimeSeriesChart chart, Rectangle chartBounds) {
        if (!hasChartData()) {
            chart.setHoveredPoint(null);
            chart.setHoveredPriceY(null);
            clearPriceInfo();
            return;
        }

        net.runelite.api.Point mousePosition = client.getMouseCanvasPosition();
        if (mousePosition == null) {
            chart.setHoveredPriceY(null);
            showLatestPrices(chart);
            return;
        }

        int mouseX = mousePosition.getX();
        int mouseY = mousePosition.getY();

        if (!chartBounds.contains(mouseX, mouseY)) {
            chart.setHoveredPriceY(null);
            showLatestPrices(chart);
            return;
        }

        chart.setHoveredPriceY(mouseY);

        TimeseriesPoint hoveredPoint = chart.getHoveredDataPoint(mouseX, mouseY);
        if (hoveredPoint == null) {
            showLatestPrices(chart);
            return;
        }
        chart.setHoveredPoint(hoveredPoint);

        // Update price info based on hovered point
        String buyPrice = hoveredPoint.getAvgHighPrice() != null
                ? QuantityFormatter.quantityToRSDecimalStack(hoveredPoint.getAvgHighPrice(), false)
                : null;
        String sellPrice = hoveredPoint.getAvgLowPrice() != null
                ? QuantityFormatter.quantityToRSDecimalStack(hoveredPoint.getAvgLowPrice(), false)
                : null;
        String timeAgo = TimeFormatters.formatTimeAgo(hoveredPoint.getTimestamp());

        setPriceInfo(buyPrice, sellPrice, timeAgo, timeAgo);
    }

    private void showLatestPrices(TimeSeriesChart chart) {
        // Clear hover state when showing latest prices
        chart.setHoveredPoint(null);
        chart.setHoveredPriceY(null);

        TimeseriesPoint lastIB = chart.getLatestInstabuyDataPoint();
        TimeseriesPoint lastIS = chart.getLatestInstasellDataPoint();
        if (lastIS == null && lastIB == null) {
            clearPriceInfo();
            return;
        }

        String instabuyPrice = lastIB != null && lastIB.getAvgHighPrice() != null
                ? QuantityFormatter.quantityToRSDecimalStack(lastIB.getAvgHighPrice(), false)
                : null;
        String ibTimeAgo = lastIB != null ? TimeFormatters.formatTimeAgo(lastIB.getTimestamp()) : "-";
        String instasellPrice = lastIS != null && lastIS.getAvgLowPrice() != null
                ? QuantityFormatter.quantityToRSDecimalStack(lastIS.getAvgLowPrice(), false)
                : null;
        String isTimeAgo = lastIS != null ? TimeFormatters.formatTimeAgo(lastIS.getTimestamp()) : "-";

        setPriceInfo(instabuyPrice, instasellPrice, ibTimeAgo, isTimeAgo);
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event) {
        // When we want to set the price of the item, we first open the chatbox via "Enter price", then set the price
        // This is setting the price: It must wait until the chatbox is open
        if (this.priceToSet != -1 && event.getScriptId() == 108) {
            int price = this.priceToSet;
            this.priceToSet = -1;
            clientThread.invokeLater(() -> {
                Widget chat = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
                if(chat != null){
                    chat.setText(price + "*");
                    client.setVarcStrValue(VarClientID.MESLAYERINPUT, String.valueOf(price));
                }
            });
        }
    }

    /**
     * Handles a click on the chart to set the offer price.
     * Calculates the price from the Y position and sets it via varbit.
     */
    private void handleChartClick(int mouseY) {
        if (this.isSelectingItem()) {
            return;
        }
        if (!this.isOfferCreation()) {
            return;
        }
        if (!hasChartData()) {
            return;
        }

        // Calculate price from Y position using the chart's method
        int price = Math.max(chart.calculatePriceFromY(mouseY, chartBounds), 0);

        Widget geContainer = client.getWidget(InterfaceID.GeOffers.SETUP);
        if (geContainer == null) {
            return;
        }
        Widget[] children = geContainer.getDynamicChildren();
        final Widget changePriceButton = Arrays.stream(children)
                .filter(button -> Arrays.asList(button.getActions()).contains("Enter price"))
                .findFirst()
                .orElse(null);

        if (changePriceButton == null) {
            return;
        }
        clientThread.invokeLater(() -> {
            client.menuAction(
                    changePriceButton.getIndex(),
                    changePriceButton.getId(),
                    MenuAction.CC_OP,
                    1,
                    -1,
                    "Enter price",
                    "");
        });
        this.priceToSet = price;
    }

    private void clearPriceInfo() {
        instabuyText = "Instabuy: -";
        instasellText = "Instasell: -";
    }

    /**
     * Sets the price info text for instabuy and instasell.
     */
    private void setPriceInfo(String buyPrice, String sellPrice, String buyTimeAgo, String sellTimeAgo) {
        instabuyText = "Instabuy: " + (buyPrice != null ? buyPrice : "-") + " | " + buyTimeAgo;
        instasellText = "Instasell: " + (sellPrice != null ? sellPrice : "-") + " | " + sellTimeAgo;
    }

    /**
     * Checks if the chart instance is valid and has data available.
     */
    private boolean hasChartData() {
        return chart != null && chart.hasData();
    }

}
