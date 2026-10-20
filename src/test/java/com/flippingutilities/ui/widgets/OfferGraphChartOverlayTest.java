package com.flippingutilities.ui.widgets;

import com.flippingutilities.FlippingConfig;
import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.jobs.TimeseriesFetcher;
import com.flippingutilities.utilities.Constants;
import net.runelite.api.Client;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;

public class OfferGraphChartOverlayTest {
    private int inputMode;
    private String enteredText;
    private String enteredVarcString;

    @Test
    public void pricePromptOpeningEntersPendingPrice() throws Exception {
        OfferGraphChartOverlay overlay = overlay();
        setPendingPrice(overlay, 12345);

        inputMode = Constants.MESLAYER_MODE_LONG_INPUT;
        overlay.onVarClientIntChanged(new VarClientIntChanged(VarClientID.MESLAYERMODE));

        assertEquals("12345*", enteredText);
        assertEquals("12345", enteredVarcString);

        //the pending price is consumed, so reopening the quantity prompt must not enter it again
        enteredText = null;
        enteredVarcString = null;
        inputMode = Constants.MESLAYER_MODE_INT_INPUT;
        overlay.onVarClientIntChanged(new VarClientIntChanged(VarClientID.MESLAYERMODE));
        assertEquals(null, enteredText);
        assertEquals(null, enteredVarcString);
    }

    @Test
    public void quantityPromptDoesNotConsumePendingPrice() throws Exception {
        OfferGraphChartOverlay overlay = overlay();
        setPendingPrice(overlay, 12345);

        inputMode = Constants.MESLAYER_MODE_INT_INPUT;
        overlay.onVarClientIntChanged(new VarClientIntChanged(VarClientID.MESLAYERMODE));
        assertEquals(null, enteredText);

        //opening the price prompt afterwards still enters the price
        inputMode = Constants.MESLAYER_MODE_LONG_INPUT;
        overlay.onVarClientIntChanged(new VarClientIntChanged(VarClientID.MESLAYERMODE));
        assertEquals("12345*", enteredText);
        assertEquals("12345", enteredVarcString);
    }

    @Test
    public void unrelatedVarcChangesDoNotEnterPendingPrice() throws Exception {
        OfferGraphChartOverlay overlay = overlay();
        setPendingPrice(overlay, 500);

        inputMode = Constants.MESLAYER_MODE_LONG_INPUT;
        overlay.onVarClientIntChanged(new VarClientIntChanged(VarClientID.MESLAYERMODE + 1));
        assertEquals(null, enteredText);
    }

    @Test
    public void script108StillEntersPendingPrice() throws Exception {
        OfferGraphChartOverlay overlay = overlay();
        setPendingPrice(overlay, 500);

        overlay.onScriptPostFired(new ScriptPostFired(108));

        assertEquals("500*", enteredText);
        assertEquals("500", enteredVarcString);
    }

    private OfferGraphChartOverlay overlay() {
        Widget chatWidget = (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class},
            (proxy, method, args) -> {
                if (method.getName().equals("setText")) {
                    enteredText = (String) args[0];
                    return null;
                }
                throw new UnsupportedOperationException(method.getName());
            });
        //run deferred client thread work synchronously so the assertions see the entered price
        ClientThread clientThread = new ClientThread() {
            @Override
            public void invokeLater(Runnable runnable) {
                runnable.run();
            }
        };
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getVarcIntValue":
                        assertEquals(VarClientID.MESLAYERMODE, args[0]);
                        return inputMode;
                    case "getWidget":
                        return (int) args[0] == InterfaceID.Chatbox.MES_TEXT2 ? chatWidget : null;
                    case "setVarcStrValue":
                        assertEquals(VarClientID.MESLAYERINPUT, args[0]);
                        enteredVarcString = (String) args[1];
                        return null;
                    default:
                        throw new UnsupportedOperationException(method.getName());
                }
            });
        EventBus eventBus = new EventBus();
        FlippingConfig config = (FlippingConfig) Proxy.newProxyInstance(FlippingConfig.class.getClassLoader(), new Class<?>[]{FlippingConfig.class},
            (proxy, method, args) -> null);
        TimeseriesFetcher timeseriesFetcher = new TimeseriesFetcher(null, null);
        FlippingPlugin plugin = new FlippingPlugin();

        OfferGraphChartOverlay overlay = new OfferGraphChartOverlay(client, clientThread, timeseriesFetcher, eventBus, config, plugin);
        return overlay;
    }

    private void setPendingPrice(OfferGraphChartOverlay overlay, long price) throws Exception {
        Field field = OfferGraphChartOverlay.class.getDeclaredField("priceToSet");
        field.setAccessible(true);
        field.setLong(overlay, price);
    }
}
