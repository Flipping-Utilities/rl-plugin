package com.flippingutilities;

import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.Timestep;
import com.flippingutilities.model.TimeseriesPoint;
import com.flippingutilities.model.TimeseriesResponse;
import com.flippingutilities.ui.uiutilities.UIUtilities;
import com.flippingutilities.ui.widgets.graph.ChartConfig;
import com.flippingutilities.ui.widgets.graph.TickIntervalCalculator;
import com.flippingutilities.ui.widgets.graph.TimeSeriesChart;
import com.flippingutilities.ui.widgets.graph.chart.ChartBounds;
import com.flippingutilities.ui.widgets.graph.chart.PriceRange;
import com.flippingutilities.utilities.GeHistoryTabExtractor;
import com.flippingutilities.utilities.WikiItemMargins;
import com.google.gson.Gson;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LongPriceDisplayTest {
    @Test
    public void compactMoneyFormattingSupportsSignedLongRange() {
        assertEquals("3B", UIUtilities.quantityToRSDecimalStack(3_000_000_001L, false));
        assertEquals("-3B", UIUtilities.quantityToRSDecimalStack(-3_000_000_001L, false));
        assertEquals("1Qa", UIUtilities.quantityToRSDecimalStack(1_000_000_000_000_000L, true));
        assertEquals("9.223Qi", UIUtilities.quantityToRSDecimalStack(Long.MAX_VALUE, true));
        assertEquals("-9.223Qi", UIUtilities.quantityToRSDecimalStack(Long.MIN_VALUE, true));
    }

    @Test
    public void historyImportParsesLongUnitPricesAndTaxedTotals() {
        assertEquals(3_000_000_001L, historyOffer("<col=ffffff>3,000,000,001 coins", 1).getPreTaxPrice());
        assertEquals(3_000_000_001L,
            historyOffer("<col=ffffff>(9,000,000,003 - 15,000,000)</col>", 3).getPreTaxPrice());
    }

    @Test
    public void chartUsesLongPricesWhileKeepingPixelCoordinates() {
        TimeSeriesChart chart = new TimeSeriesChart(ChartConfig.builder().build());
        ChartBounds bounds = new ChartBounds(0, 0, 200, 100);
        PriceRange range = new PriceRange(3_000_000_001L, 5_000_000_001L);
        assertEquals(5_000_000_001L, chart.calculatePriceFromY(0, bounds, range));
        assertEquals(4_000_000_001L, chart.calculatePriceFromY(50, bounds, range));
        assertEquals(3_000_000_001L, chart.calculatePriceFromY(100, bounds, range));
        assertEquals(5_000_000_000L, new TickIntervalCalculator().calculate(30_000_000_000L));

        chart.setDataSeries(new TimeseriesResponse(Collections.singletonList(
            new TimeseriesPoint(Instant.now().getEpochSecond(), 5_000_000_001L, 3_000_000_001L))),
            Timestep.values()[0], 4_000_000_001L);
        Graphics2D graphics = new BufferedImage(400, 250, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try {
            assertTrue(chart.render(graphics).width > 0);
            chart.setOfferPrice(Long.MAX_VALUE);
            assertTrue(chart.render(graphics).width > 0);
        } finally {
            graphics.dispose();
        }
    }

    @Test
    public void marketPriceJsonRetainsValuesAboveIntRange() {
        Gson gson = new Gson();
        WikiItemMargins margins = gson.fromJson("{\"high\":5000000001,\"low\":3000000001}", WikiItemMargins.class);
        assertEquals(5_000_000_001L, margins.getHigh());
        TimeseriesPoint point = gson.fromJson("{\"timestamp\":1,\"avgHighPrice\":5000000001,\"avgLowPrice\":null}", TimeseriesPoint.class);
        assertEquals(Long.valueOf(5_000_000_001L), point.getAvgHighPrice());
    }

    private OfferEvent historyOffer(String price, int quantity) {
        Widget action = widget("Bought", quantity);
        Widget item = widget("", quantity);
        Widget money = widget(price, quantity);
        return GeHistoryTabExtractor.createOfferEventFromWidgetGroup(Arrays.asList(null, null, action, null, item, money));
    }

    private Widget widget(String text, int quantity) {
        return (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getText": return text;
                    case "getItemQuantity": return quantity;
                    case "getItemId": return 4151;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
    }
}
