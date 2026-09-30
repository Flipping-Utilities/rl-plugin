package com.flippingutilities.controller;

import net.runelite.api.Client;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import org.junit.Test;

import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GameUiChangesHandlerTest {
    private int inputMode;
    private boolean geSetupOpen = true;

    @Test
    public void longPricePromptEnablesHotkeysAndClosingDisablesThem() {
        GameUiChangesHandler handler = handler();
        inputMode = 30;
        handler.onVarClientIntChanged(new VarClientIntChanged(VarClientID.MESLAYERMODE));
        assertTrue(handler.quantityOrPriceChatboxOpen);

        inputMode = 0;
        handler.onVarClientIntChanged(new VarClientIntChanged(VarClientID.MESLAYERMODE));
        assertFalse(handler.quantityOrPriceChatboxOpen);
    }

    @Test
    public void quantityPromptStillEnablesHotkeys() {
        GameUiChangesHandler handler = handler();
        inputMode = 7;
        handler.onVarClientIntChanged(new VarClientIntChanged(VarClientID.MESLAYERMODE));
        assertTrue(handler.quantityOrPriceChatboxOpen);
    }

    @Test
    public void unrelatedPromptDoesNotEnableOfferHotkeys() {
        GameUiChangesHandler handler = handler();
        inputMode = 6;
        handler.onVarClientIntChanged(new VarClientIntChanged(VarClientID.MESLAYERMODE));
        assertFalse(handler.quantityOrPriceChatboxOpen);
    }

    @Test
    public void longPromptOutsideGeDoesNotEnableOfferHotkeys() {
        GameUiChangesHandler handler = handler();
        inputMode = 30;
        geSetupOpen = false;
        handler.onVarClientIntChanged(new VarClientIntChanged(VarClientID.MESLAYERMODE));
        assertFalse(handler.quantityOrPriceChatboxOpen);
    }

    private GameUiChangesHandler handler() {
        Widget widget = (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class},
            (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getVarcIntValue":
                        assertEquals(VarClientID.MESLAYERMODE, args[0]);
                        return inputMode;
                    case "getWidget":
                        int id = (int) args[0];
                        if (id == InterfaceID.Chatbox.MES_TEXT) {
                            return widget;
                        }
                        if (id == InterfaceID.GeOffers.SETUP_DESC) {
                            return geSetupOpen ? widget : null;
                        }
                        throw new UnsupportedOperationException("Unexpected widget: " + id);
                    default:
                        throw new UnsupportedOperationException(method.getName());
                }
            });
        // Keep RuneLite's deferred widget updates queued while testing prompt state transitions.
        ClientThread clientThread = new ClientThread();
        FlippingPlugin plugin = new FlippingPlugin() {
            @Override
            public Client getClient() {
                return client;
            }

            @Override
            public ClientThread getClientThread() {
                return clientThread;
            }
        };
        return new GameUiChangesHandler(plugin, new EventBus());
    }
}
