package com.flippingutilities.ui.widgets;

import com.flippingutilities.model.TimeseriesResponse;
import com.flippingutilities.model.Timestep;
import com.google.gson.Gson;
import net.runelite.client.ui.FontManager;
import org.junit.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;

import static com.flippingutilities.ui.widgets.OfferGraphLoadStatesTest.point;
import static org.junit.Assert.*;

public class QuickLookEmptyHistoryTest {
    @Test
    public void olderOnlyHistoryRendersTheSameEmptyMessageAsAnEmptyResponse() {
        String old = "{\"data\":[" + point(2 * 24 * 3600, true) + "]}";
        assertArrayEquals(render("{\"data\":[]}", Timestep.FIVE_MINUTES), render(old, Timestep.FIVE_MINUTES));
    }

    @Test
    public void recentNullPricesCannotMakeOlderPricesVisibleButALongerIntervalCan() {
        String mixed = "{\"data\":[" + point(2 * 24 * 3600, true) + "," + point(60, false) + "]}";
        int[] empty = render("{\"data\":[]}", Timestep.FIVE_MINUTES);
        assertArrayEquals(empty, render(mixed, Timestep.FIVE_MINUTES));
        assertFalse("A one-week interval must show its two-day-old prices",
            Arrays.equals(empty, render(mixed, Timestep.ONE_HOUR)));
    }

    @Test
    public void currentPricesRenderAChartEvenWithOlderHistoryInTheResponse() {
        String mixed = "{\"data\":[" + point(2 * 24 * 3600, true) + "," + point(60, true) + "]}";
        assertFalse(Arrays.equals(render("{\"data\":[]}", Timestep.FIVE_MINUTES),
            render(mixed, Timestep.FIVE_MINUTES)));
    }

    private int[] render(String json, Timestep timestep) {
        QuickLookTooltip tooltip = new QuickLookTooltip();
        tooltip.addCenteredRow("Price history", Color.WHITE, FontManager.getRunescapeSmallFont());
        tooltip.setGraphData(new Gson().fromJson(json, TimeseriesResponse.class), timestep, 104_500);
        BufferedImage image = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setFont(FontManager.getRunescapeSmallFont());
            tooltip.render(graphics);
        } finally {
            graphics.dispose();
        }
        return image.getRGB(0, 0, 320, 240, null, 0, 320);
    }
}
