package com.flippingutilities.ui.widgets;

import com.flippingutilities.FlippingConfig;
import com.flippingutilities.controller.ApiAuthHandler;
import com.flippingutilities.controller.ApiRequestHandlerTest.ManualClient;
import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.jobs.TimeseriesFetcher;
import com.flippingutilities.model.Timestep;
import com.flippingutilities.utilities.WikiItemMargins;
import com.flippingutilities.utilities.WikiRequest;
import com.google.gson.Gson;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class SlotStateDrawerTest {
    @Test
    public void reusedQuickLookWidgetRequestsCurrentOfferInsteadOfCreationTimeOffer() {
        Fixture fixture = new Fixture();
        WidgetBoundary originalIcon = fixture.slot.child;
        fixture.itemId = 2;
        fixture.drawer.refreshSlotVisuals();
        assertSame("The test must exercise reuse of the existing icon", originalIcon, fixture.slot.child);
        originalIcon.mouseOver.run(null);
        fixture.drawer.onBeforeRender(new BeforeRender());
        assertEquals(1, fixture.http.calls.size());
        assertEquals("2", fixture.http.calls.get(0).request().url().queryParameter("id"));
    }

    @Test
    public void activeHoverReloadsWhenOfferOrConfiguredIntervalChanges() {
        Fixture fixture = new Fixture();
        fixture.slot.child.mouseOver.run(null);
        fixture.drawer.onBeforeRender(new BeforeRender());
        fixture.drawer.onBeforeRender(new BeforeRender());
        assertEquals("Rendering another frame must not request the same graph again", 1, fixture.http.calls.size());
        fixture.itemId = 2;
        fixture.drawer.refreshSlotVisuals();
        fixture.drawer.onBeforeRender(new BeforeRender());
        assertEquals("2", fixture.http.calls.get(1).request().url().queryParameter("id"));
        fixture.timestep = Timestep.FIVE_MINUTES;
        fixture.drawer.onBeforeRender(new BeforeRender());
        assertEquals("5m", fixture.http.calls.get(2).request().url().queryParameter("timestep"));
    }

    @Test
    public void failedQuickLookCanRetryFromTheMagnifierWithoutLeavingTheSlot() throws Exception {
        Fixture fixture = new Fixture();
        fixture.slot.child.mouseOver.run(null);
        fixture.drawer.onBeforeRender(new BeforeRender());
        fixture.http.calls.get(0).fail();
        QuickLookTooltip tooltip = (QuickLookTooltip) fixture.tooltips.getTooltips().get(0).getComponent();
        assertTrue(tooltip.canRetryGraph());
        fixture.slot.child.click.run(null);
        assertFalse(tooltip.canRetryGraph());
        fixture.tooltips.clear();
        fixture.drawer.onBeforeRender(new BeforeRender());
        assertEquals(2, fixture.http.calls.size());
        fixture.http.calls.get(1).respond(200, "{\"data\":[]}");
        QuickLookTooltip retried = (QuickLookTooltip) fixture.tooltips.getTooltips().get(0).getComponent();
        assertFalse(retried.canRetryGraph());
        fixture.drawer.onBeforeRender(new BeforeRender());
        assertEquals("An empty history must not retry on every frame", 2, fixture.http.calls.size());
    }

    private static class Fixture {
        int itemId = 4151;
        Timestep timestep = Timestep.ONE_HOUR;
        final ManualClient http = new ManualClient();
        final TooltipManager tooltips = new TooltipManager();
        final WidgetBoundary slot = new WidgetBoundary(null);
        final WidgetBoundary geWindow = new WidgetBoundary(null);
        final SlotStateDrawer drawer;
        Fixture() {
            ClientThread thread = new ClientThread() {
                @Override public void invokeLater(Runnable runnable) { runnable.run(); }
            };
            FlippingConfig config = proxy(FlippingConfig.class, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "quickLookupEnabled": return true;
                    case "priceGraphTimestep": return timestep;
                    default: return defaultValue(method.getReturnType());
                }
            });
            ApiAuthHandler auth = new ApiAuthHandler(null);
            auth.setPremium(true);
            FlippingPlugin plugin = new FlippingPlugin() {
                @Override public ClientThread getClientThread() { return thread; }
                @Override public FlippingConfig getConfig() { return config; }
                @Override public ApiAuthHandler getApiAuthHandler() { return auth; }
                @Override public String getCurrentlyLoggedInAccount() { return "test"; }
                @Override public boolean shouldEnhanceSlots() { return true; }
            };
            plugin.gson = new Gson();
            GrandExchangeOffer offer = proxy(GrandExchangeOffer.class, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getItemId": return itemId;
                    case "getPrice": return 100;
                    case "getState": return GrandExchangeOfferState.BUYING;
                    default: return defaultValue(method.getReturnType());
                }
            });
            Client client = proxy(Client.class, (proxy, method, args) -> {
                if (method.getName().equals("getGrandExchangeOffers")) {
                    return new GrandExchangeOffer[]{offer};
                }
                if (method.getName().equals("getWidget") && (Integer) args[0] == InterfaceID.GeOffers.UNIVERSE) {
                    return geWindow.widget;
                }
                return defaultValue(method.getReturnType());
            });
            drawer = new SlotStateDrawer(plugin, tooltips, client, new TimeseriesFetcher(http, plugin));
            WikiRequest wiki = new WikiRequest();
            Map<Integer, WikiItemMargins> margins = new HashMap<>();
            margins.put(4151, new WikiItemMargins());
            margins.put(2, new WikiItemMargins());
            wiki.setData(margins);
            drawer.onWikiRequest(wiki);
            drawer.setSlotWidgets(new Widget[]{null, slot.widget});
        }
    }

    /** Minimal RuneLite widget boundary; production slot and tooltip logic stays real. */
    private static class WidgetBoundary {
        final Widget widget;
        WidgetBoundary child;
        JavaScriptCallback mouseOver;
        JavaScriptCallback click;
        WidgetBoundary(WidgetBoundary parent) {
            widget = proxy(Widget.class, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getParent": return parent == null ? null : parent.widget;
                    case "getDynamicChildren": return child == null ? new Widget[0] : new Widget[]{child.widget};
                    case "getIndex": return 0;
                    case "createChild":
                        child = new WidgetBoundary(this);
                        return child.widget;
                    case "setOnClickListener":
                        click = (JavaScriptCallback) ((Object[]) args[0])[0];
                        return null;
                    case "setOnMouseOverListener":
                        mouseOver = (JavaScriptCallback) ((Object[]) args[0])[0];
                        return null;
                    default: return defaultValue(method.getReturnType());
                }
            });
        }
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
}
