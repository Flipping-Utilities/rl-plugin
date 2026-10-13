package com.flippingutilities.ui.widgets;

import com.flippingutilities.FlippingConfig;
import com.flippingutilities.controller.ApiAuthHandler;
import com.flippingutilities.controller.ApiRequestHandlerTest.ManualClient;
import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.jobs.TimeseriesFetcher;
import com.flippingutilities.model.TimeseriesResponse;
import com.flippingutilities.model.Timestep;
import com.flippingutilities.ui.uiutilities.CustomColors;
import com.flippingutilities.ui.uiutilities.GraphLoadState;
import com.flippingutilities.utilities.SlotInfo;
import com.flippingutilities.utilities.SlotPredictedState;
import com.flippingutilities.utilities.WikiItemMargins;
import com.google.gson.Gson;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.ui.FontManager;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.function.Consumer;

/** Native preview fixtures: real chart consumers with RuneLite and HTTP boundaries controlled. */
public final class ChartStateFixtures {
    private ChartStateFixtures() { }

    /** A failed preview's Retry button recovers to the loaded fixture. */
    public static JComponent overlay(GraphLoadState state) {
        OverlayFixture fixture = new OverlayFixture();
        fixture.complete(state);
        JPanel panel = canvas(500, 230, fixture.overlay::render);
        panel.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                int before = fixture.http.calls.size();
                fixture.overlay.mousePressed(event);
                if (fixture.http.calls.size() > before) {
                    Timer response = new Timer(600, ignored -> {
                        fixture.complete(GraphLoadState.READY);
                        panel.repaint();
                    });
                    response.setRepeats(false);
                    response.start();
                }
                panel.repaint();
            }
        });
        return panel;
    }

    public static JComponent tooltip(GraphLoadState state) {
        QuickLookTooltip tooltip = new QuickLookTooltip();
        WikiItemMargins margins = new WikiItemMargins();
        margins.setHigh(105_500);
        margins.setLow(104_000);
        margins.setHighTime(Instant.now().getEpochSecond() - 120);
        margins.setLowTime(Instant.now().getEpochSecond() - 180);
        tooltip.update(new SlotInfo(0, SlotPredictedState.IN_RANGE, 4151, 104_500, true, false), margins);
        if (state == GraphLoadState.FAILED) {
            tooltip.showGraphFailure();
        } else if (state != GraphLoadState.LOADING) {
            tooltip.setGraphData(new Gson().fromJson(state == GraphLoadState.EMPTY
                ? "{\"data\":[]}" : history(), TimeseriesResponse.class), Timestep.FIVE_MINUTES, 104_500);
        }
        return canvas(420, 350, tooltip::render);
    }

    private static JPanel canvas(int width, int height, Consumer<Graphics2D> render) {
        return new JPanel() {
            private final Timer animation = new Timer(80, event -> repaint());
            {
                setPreferredSize(new Dimension(width, height));
                setBackground(CustomColors.CHART_PANEL_BG);
            }
            @Override public void addNotify() { super.addNotify(); animation.start(); }
            @Override public void removeNotify() { animation.stop(); super.removeNotify(); }
            @Override protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                Graphics2D copy = (Graphics2D) graphics.create();
                try {
                    copy.setFont(FontManager.getRunescapeSmallFont());
                    render.accept(copy);
                } finally {
                    copy.dispose();
                }
            }
        };
    }

    static final class OverlayFixture {
        final ManualClient http = new ManualClient();
        final OfferGraphChartOverlay overlay;
        OverlayFixture() {
            ClientThread thread = new ClientThread() {
                @Override public void invoke(Runnable runnable) { runnable.run(); }
                @Override public void invokeLater(Runnable runnable) { runnable.run(); }
            };
            FlippingConfig config = proxy(FlippingConfig.class, (proxy, method, args) -> {
                if (method.getName().equals("priceGraphTimestep")) { return Timestep.FIVE_MINUTES; }
                if (method.getName().equals("offerPageChartEnabled")) { return true; }
                return defaultValue(method.getReturnType());
            });
            ApiAuthHandler auth = new ApiAuthHandler(null);
            auth.setPremium(true);
            FlippingPlugin plugin = new FlippingPlugin() {
                @Override public ApiAuthHandler getApiAuthHandler() { return auth; }
            };
            plugin.gson = new Gson();
            Widget title = proxy(Widget.class, (proxy, method, args) ->
                method.getName().equals("getText") ? "Grand Exchange: Set up offer" : defaultValue(method.getReturnType()));
            Widget frame = proxy(Widget.class, (proxy, method, args) ->
                method.getName().equals("getDynamicChildren") ? new Widget[]{title} : defaultValue(method.getReturnType()));
            Widget chatbox = proxy(Widget.class, (proxy, method, args) -> {
                if (method.getName().equals("getBounds")) { return new Rectangle(0, 0, 500, 220); }
                if (method.getName().equals("getText")) { return ""; }
                return defaultValue(method.getReturnType());
            });
            Client client = proxy(Client.class, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getVarpValue": return 4151;
                    case "getVarbitValue": return (Integer) args[0] == VarbitID.GE_NEWOFFER_PRICE ? 104_500 : 0;
                    case "getGrandExchangeOffers": return new GrandExchangeOffer[0];
                    case "getWidget": return (Integer) args[0] == InterfaceID.GeOffers.FRAME ? frame : chatbox;
                    default: return defaultValue(method.getReturnType());
                }
            });
            overlay = new OfferGraphChartOverlay(client, thread, new TimeseriesFetcher(http, plugin),
                new EventBus(), config, plugin);
            VarbitChanged selection = new VarbitChanged();
            selection.setVarbitId(-1);
            selection.setVarpId(VarPlayerID.TRADINGPOST_SEARCH);
            overlay.onVarbitChanged(selection);
        }

        void complete(GraphLoadState state) {
            try {
                if (state == GraphLoadState.FAILED) {
                    http.calls.get(http.calls.size() - 1).fail();
                } else if (state != GraphLoadState.LOADING) {
                    http.calls.get(http.calls.size() - 1).respond(200,
                        state == GraphLoadState.EMPTY ? "{\"data\":[]}" : history());
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static String history() {
        long now = Instant.now().getEpochSecond();
        StringBuilder json = new StringBuilder("{\"data\":[");
        for (int i = 0; i < 48; i++) {
            if (i > 0) { json.append(','); }
            int low = 102_000 + i * 45 + (int) (Math.sin(i / 3.0) * 600);
            json.append("{\"timestamp\":").append(now - (48 - i) * 1800L)
                .append(",\"avgHighPrice\":").append(low + 1500)
                .append(",\"avgLowPrice\":").append(low).append('}');
        }
        return json.append("]}").toString();
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) { return false; }
        if (type == int.class) { return 0; }
        if (type == long.class) { return 0L; }
        return null;
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    public static void main(String[] args) throws Exception {
        File directory = new File(args.length == 0 ? "build/price-history-previews" : args[0]);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create preview directory: " + directory);
        }
        SwingUtilities.invokeAndWait(() -> {
            BufferedImage sheet = new BufferedImage(960, 4 * 410, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = sheet.createGraphics();
            graphics.setColor(new Color(20, 20, 20));
            graphics.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
            GraphLoadState[] states = {GraphLoadState.LOADING, GraphLoadState.EMPTY, GraphLoadState.FAILED, GraphLoadState.READY};
            for (int i = 0; i < states.length; i++) {
                graphics.setColor(Color.WHITE);
                graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
                graphics.drawString("Offer graph · " + states[i], 15, i * 410 + 25);
                graphics.drawString("Quick look · " + states[i], 535, i * 410 + 25);
                JComponent[] previews = {overlay(states[i]), tooltip(states[i])};
                for (int j = 0; j < previews.length; j++) {
                    JComponent preview = previews[j];
                    Dimension size = preview.getPreferredSize();
                    preview.setSize(size);
                    BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_RGB);
                    Graphics2D imageGraphics = image.createGraphics();
                    preview.paint(imageGraphics);
                    // The overlay derives its latest-price labels during each frame.
                    preview.paint(imageGraphics);
                    imageGraphics.dispose();
                    graphics.drawImage(image, j == 0 ? 15 : 535, i * 410 + 45, null);
                    try {
                        ImageIO.write(image, "png", new File(directory,
                            (j == 0 ? "offer-" : "tooltip-") + states[i].name().toLowerCase() + ".png"));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
            graphics.dispose();
            try {
                ImageIO.write(sheet, "png", new File(directory, "price-history-states.png"));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
