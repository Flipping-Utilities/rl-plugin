package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.model.Timestep;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** Exercise visible controls against deterministic HTTP responses, without reaching the network. */
public class SandboxPricePanelTest {
    @Test
    public void rendersRealWidgetsAndHoverPricesThenChangesPeriod() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (SandboxWikiData wiki = wiki(chain -> {
            calls.incrementAndGet();
            return response(chain, isLatest(chain) ? latest() : series(200, 100));
        })) {
            SandboxPricePanel panel = panel(wiki);
            try {
                assertEquals("Construction must not fetch a default item", 0, calls.get());
                onEdt(() -> panel.showItem(4151, "Abyssal whip", 150, true));
                await(() -> status(panel).startsWith("Wiki Insta Buy") && text(panel, "Latest price status").contains("loaded"));
                onEdt(() -> {
                    QuickLookPanel prices = find(panel, QuickLookPanel.class, "Latest Wiki prices");
                    assertTrue(prices.wikiInstaBuy.getText().contains("200"));
                    assertTrue(prices.wikiInstaSell.getText().contains("100"));
                    assertTrue(prices.offerCompetitivenessText.getText().contains("competitive"));
                    assertTrue(text(panel, "Offer reference").contains("150 gp"));
                    BufferedImage image = UiGallery.capture(panel, 660, 560);
                    assertTrue("Production chart lines render", pixelsOfColor(image, CustomColors.CHART_INSTABUY) > 10);
                    assertTrue("Production offer line renders", pixelsOfColor(image, CustomColors.CHART_ACCENT) > 10);
                    hover(panel);
                    assertTrue(text(panel, "Chart hover prices").contains("200 gp"));
                    assertTrue(text(panel, "Chart hover prices").contains("100 gp"));
                    try {
                        Path screenshot = Files.createTempFile("sandbox-price-chart-", ".png");
                        ImageIO.write(image, "png", screenshot.toFile());
                        System.out.println("Sandbox price chart screenshot: " + screenshot);
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                    find(panel, JComboBox.class, "Chart period").setSelectedItem(Timestep.ONE_HOUR);
                });
                await(() -> status(panel).startsWith("Wiki Insta Buy"));
                assertEquals("Latest is cached, but another period fetches its own series", 3, calls.get());
                onEdt(() -> panel.showItem(4151, "Abyssal whip", 0, true));
                await(() -> status(panel).startsWith("Wiki Insta Buy"));
                onEdt(() -> {
                    QuickLookPanel prices = find(panel, QuickLookPanel.class, "Latest Wiki prices");
                    assertEquals("Market prices", text(panel, "Offer reference"));
                    assertEquals("", prices.offerCompetitivenessText.getText());
                    assertEquals("", prices.toMakeOfferCompetitiveTest.getText());
                    BufferedImage image = UiGallery.capture(panel, 660, 560);
                    assertEquals("Market-only view has no offer line", 0, pixelsOfColor(image, CustomColors.CHART_ACCENT));
                });
            } finally {
                onEdt(panel::close);
            }
        }
    }

    @Test
    public void retriesFailuresAndKeepsLatestPricesWhenHistoryIsEmpty() throws Exception {
        AtomicInteger seriesCalls = new AtomicInteger();
        AtomicInteger latestCalls = new AtomicInteger();
        try (SandboxWikiData wiki = wiki(chain -> {
            if (isLatest(chain)) {
                if (latestCalls.incrementAndGet() == 1) throw new IOException("offline latest");
                return response(chain, latest());
            }
            if (seriesCalls.incrementAndGet() == 1) throw new IOException("offline history");
            return response(chain, "{\"data\":[]}");
        })) {
            SandboxPricePanel panel = panel(wiki);
            try {
                onEdt(() -> panel.showItem(4151, "Abyssal whip", 150, true));
                await(() -> status(panel).contains("unavailable") && text(panel, "Latest price status").contains("unavailable"));
                onEdt(() -> find(panel, JButton.class, "Refresh prices").doClick());
                await(() -> status(panel).startsWith("No price history") && text(panel, "Latest price status").contains("loaded"));
                onEdt(() -> {
                    QuickLookPanel prices = find(panel, QuickLookPanel.class, "Latest Wiki prices");
                    assertTrue(prices.wikiInstaBuy.getText().contains("200"));
                    assertTrue(prices.wikiInstaSell.getText().contains("100"));
                });
                assertEquals(2, seriesCalls.get());
                assertEquals(2, latestCalls.get());
            } finally {
                onEdt(panel::close);
            }
        }
    }

    @Test
    public void discardsDelayedResultsAfterItemOrPeriodChanges() throws Exception {
        for (boolean changeItem : new boolean[]{true, false}) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try (SandboxWikiData wiki = wiki(chain -> {
                if (isLatest(chain)) return response(chain, latest());
                boolean original = "4151".equals(chain.request().url().queryParameter("id"))
                    && "5m".equals(chain.request().url().queryParameter("timestep"));
                if (original) {
                    started.countDown();
                    waitFor(release);
                }
                return response(chain, original ? series(999, 998) : series(300, 250));
            })) {
                SandboxPricePanel panel = panel(wiki);
                try {
                    onEdt(() -> panel.showItem(4151, "First item", 150, true));
                    assertTrue(started.await(5, TimeUnit.SECONDS));
                    onEdt(() -> {
                        if (changeItem) panel.showItem(2, "Second item", 260, false);
                        else find(panel, JComboBox.class, "Chart period").setSelectedItem(Timestep.ONE_HOUR);
                    });
                    await(() -> status(panel).startsWith("Wiki Insta Buy"));
                    release.countDown();
                    wiki.timeseries(4151, Timestep.FIVE_MINUTES).get(5, TimeUnit.SECONDS);
                    onEdt(() -> {
                        UiGallery.capture(panel, 660, 560);
                        hover(panel);
                        assertTrue(text(panel, "Chart hover prices").contains("300 gp"));
                        assertFalse(text(panel, "Chart hover prices").contains("999 gp"));
                        if (changeItem) {
                            assertEquals("Second item (2)", text(panel, "Price item"));
                            assertTrue(text(panel, "Offer reference").contains("260 gp"));
                        }
                    });
                } finally {
                    release.countDown();
                    onEdt(panel::close);
                }
            }
        }
    }

    @Test
    public void closeIgnoresPendingCallbacksAndDisablesControls() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (SandboxWikiData wiki = wiki(chain -> {
            started.countDown();
            waitFor(release);
            return response(chain, isLatest(chain) ? latest() : series(200, 100));
        })) {
            SandboxPricePanel panel = panel(wiki);
            try {
                onEdt(() -> panel.showItem(4151, "Abyssal whip", 150, true));
                assertTrue(started.await(5, TimeUnit.SECONDS));
                onEdt(panel::close);
                release.countDown();
                wiki.timeseries(4151, Timestep.FIVE_MINUTES).get(5, TimeUnit.SECONDS);
                wiki.latest().get(5, TimeUnit.SECONDS);
                onEdt(() -> {
                    assertEquals("Loading price history…", status(panel));
                    assertEquals("Loading latest Wiki prices…", text(panel, "Latest price status"));
                    assertFalse(find(panel, JButton.class, "Refresh prices").isEnabled());
                    assertFalse(find(panel, JComboBox.class, "Chart period").isEnabled());
                });
            } finally {
                release.countDown();
                onEdt(panel::close);
            }
        }
    }

    private static SandboxWikiData wiki(Interceptor fixture) {
        return new SandboxWikiData(new OkHttpClient.Builder().addInterceptor(fixture).build());
    }

    private static Response response(Interceptor.Chain chain, String json) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(ResponseBody.create(MediaType.parse("application/json"), json)).build();
    }

    private static boolean isLatest(Interceptor.Chain chain) {
        return chain.request().url().encodedPath().endsWith("/latest");
    }

    private static String latest() {
        long now = Instant.now().minusSeconds(60).getEpochSecond();
        return "{\"data\":{\"4151\":{\"high\":200,\"low\":100,\"highTime\":" + now + ",\"lowTime\":" + now + "},"
            + "\"2\":{\"high\":300,\"low\":250,\"highTime\":" + now + ",\"lowTime\":" + now + "}}}";
    }

    private static String series(int high, int low) {
        long now = Instant.now().getEpochSecond();
        return "{\"data\":[{\"timestamp\":" + (now - 3600) + ",\"avgHighPrice\":" + high + ",\"avgLowPrice\":" + low + "},"
            + "{\"timestamp\":" + (now - 60) + ",\"avgHighPrice\":" + high + ",\"avgLowPrice\":" + low + "}]}";
    }

    private static SandboxPricePanel panel(SandboxWikiData wiki) throws Exception {
        SandboxPricePanel[] result = new SandboxPricePanel[1];
        onEdt(() -> result[0] = new SandboxPricePanel(wiki));
        return result[0];
    }

    private static void hover(SandboxPricePanel panel) {
        JPanel chart = find(panel, JPanel.class, "Price chart");
        chart.dispatchEvent(new MouseEvent(chart, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0,
            chart.getWidth() / 2, chart.getHeight() / 2, 0, false));
    }

    private static int pixelsOfColor(BufferedImage image, Color color) {
        int count = 0;
        for (int x = 0; x < image.getWidth(); x++) for (int y = 0; y < image.getHeight(); y++) {
            if (image.getRGB(x, y) == color.getRGB()) count++;
        }
        return count;
    }

    private static String status(SandboxPricePanel panel) { return text(panel, "Chart status"); }
    private static String text(Container panel, String name) { return find(panel, JLabel.class, name).getText(); }

    private static <T extends Component> T find(Component component, Class<T> type, String name) {
        if (type.isInstance(component) && name.equals(component.getName())) return type.cast(component);
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                T result = find(child, type, name);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static void waitFor(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("Fixture response timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean[] ready = {false};
        do {
            onEdt(() -> ready[0] = condition.getAsBoolean());
            if (ready[0]) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("Timed out waiting for visible price state");
    }

    private static void onEdt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
}
