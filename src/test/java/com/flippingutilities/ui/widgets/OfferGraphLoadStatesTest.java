package com.flippingutilities.ui.widgets;

import com.flippingutilities.ui.uiutilities.GraphLoadState;
import com.flippingutilities.ui.widgets.graph.ChartConfig;
import com.flippingutilities.ui.widgets.graph.TimeSeriesChart;
import com.flippingutilities.model.TimeseriesResponse;
import com.flippingutilities.model.Timestep;
import com.google.gson.Gson;
import org.junit.Test;

import javax.swing.JPanel;
import java.awt.Graphics2D;
import java.awt.Dimension;
import java.time.Instant;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

import static org.junit.Assert.*;

public class OfferGraphLoadStatesTest {
    @Test
    public void retryButtonStartsANewRequestAndStopsAcceptingClicksWhileLoading() {
        ChartStateFixtures.OverlayFixture fixture = new ChartStateFixtures.OverlayFixture();
        fixture.complete(GraphLoadState.FAILED);
        render(fixture);
        MouseEvent retry = click(250, 140);
        fixture.overlay.mousePressed(retry);
        assertTrue(retry.isConsumed());
        assertEquals(2, fixture.http.calls.size());
        fixture.overlay.mousePressed(click(250, 140));
        assertEquals("Loading must not issue repeated retry requests", 2, fixture.http.calls.size());
        fixture.complete(GraphLoadState.READY);
        render(fixture);
    }

    @Test
    public void emptyHistoryDoesNotRetryOrSetAnOfferPriceWhenClicked() {
        ChartStateFixtures.OverlayFixture fixture = new ChartStateFixtures.OverlayFixture();
        fixture.complete(GraphLoadState.EMPTY);
        render(fixture);
        MouseEvent click = click(250, 140);
        fixture.overlay.mousePressed(click);
        assertFalse(click.isConsumed());
        assertEquals(1, fixture.http.calls.size());
    }

    @Test
    public void resizedChartKeepsItsRenderedBoundsAndHoverAcrossTheFullWidth() {
        TimeSeriesChart chart = new TimeSeriesChart(ChartConfig.builder().build());
        chart.setDataSeries(new Gson().fromJson(ChartStateFixtures.history(), TimeseriesResponse.class),
            Timestep.FIVE_MINUTES, 104_500);
        chart.setPreferredSize(new Dimension(480, 180));
        BufferedImage image = new BufferedImage(480, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            assertEquals(new Dimension(480, 180), chart.render(graphics));
            assertEquals(480, chart.getBounds().width);
            assertEquals(180, chart.getBounds().height);
            assertNotNull(chart.getHoveredDataPoint(400, 80));
        } finally {
            graphics.dispose();
        }
    }

    @Test
    public void oldPricesAndUnpricedCurrentPointsAreEmptyUntilTheIntervalIncludesAPrice() throws Exception {
        ChartStateFixtures.OverlayFixture fixture = new ChartStateFixtures.OverlayFixture();
        String response = "{\"data\":[" + point(2 * 24 * 3600, true) + "," + point(60, false) + "]}";
        fixture.http.calls.get(0).respond(200, response);
        render(fixture);
        MouseEvent emptyClick = click(250, 140);
        fixture.overlay.mousePressed(emptyClick);
        assertFalse("Old prices must not make a one-day plot actionable", emptyClick.isConsumed());
        assertEquals(1, fixture.http.calls.size());

        fixture.overlay.setSelectedDuration(OfferGraphChartOverlay.GraphDuration.ONE_WEEK);
        fixture.http.calls.get(1).respond(200, response);
        render(fixture);
        MouseEvent pricedClick = click(250, 140);
        fixture.overlay.mousePressed(pricedClick);
        assertTrue("The same two-day-old price is usable in a one-week plot", pricedClick.isConsumed());
        assertEquals(2, fixture.http.calls.size());
    }

    @Test
    public void mixedOldAndCurrentPricesKeepTheCurrentPlotActionable() throws Exception {
        ChartStateFixtures.OverlayFixture fixture = new ChartStateFixtures.OverlayFixture();
        fixture.http.calls.get(0).respond(200, "{\"data\":[" + point(2 * 24 * 3600, true)
            + "," + point(60, true) + "]}");
        render(fixture);
        MouseEvent currentClick = click(250, 140);
        fixture.overlay.mousePressed(currentClick);
        assertTrue(currentClick.isConsumed());
        assertEquals(1, fixture.http.calls.size());
    }

    static String point(long ageSeconds, boolean priced) {
        return "{\"timestamp\":" + (Instant.now().getEpochSecond() - ageSeconds)
            + ",\"avgHighPrice\":" + (priced ? "105500" : "null")
            + ",\"avgLowPrice\":" + (priced ? "104000" : "null") + "}";
    }

    private void render(ChartStateFixtures.OverlayFixture fixture) {
        BufferedImage image = new BufferedImage(500, 230, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try { fixture.overlay.render(graphics); } finally { graphics.dispose(); }
    }

    private MouseEvent click(int x, int y) {
        return new MouseEvent(new JPanel(), MouseEvent.MOUSE_PRESSED, 0, 0, x, y, 1, false);
    }
}
