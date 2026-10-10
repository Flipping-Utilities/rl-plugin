package com.flippingutilities.ui.widgets;

import com.flippingutilities.FlippingConfig;
import com.flippingutilities.utilities.Constants;
import net.runelite.api.Client;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that a price chosen by clicking the offer chart is typed into the price prompt once it opens,
 * without depending on raw script ids (which game updates renumber).
 */
public class OfferGraphChartOverlayInputTest {
    private int inputMode;
    private String promptText = Constants.PRICE_PROMPT_TEXT;
    private int inputWidgetMissingTicks;
    private final List<String> typedTexts = new ArrayList<>();
    private final List<String> typedInputs = new ArrayList<>();

    @Test
    public void chartPriceIsTypedWhenPricePromptOpens() {
        OfferGraphChartOverlay overlay = overlay();
        overlay.priceToSet = 12345678901L;

        inputMode = Constants.MESLAYER_MODE_LONG_INPUT;
        overlay.onVarClientIntChanged(new VarClientIntChanged(VarClientID.MESLAYERMODE));

        assertEquals(List.of("12345678901*"), typedTexts);
        assertEquals(List.of("12345678901"), typedInputs);
        assertEquals(-1, overlay.priceToSet);
    }

    @Test
    public void quantityPromptDoesNotConsumeTheArmedPrice() {
        OfferGraphChartOverlay overlay = overlay();
        overlay.priceToSet = 500;
        promptText = "How many do you wish to buy?";

        inputMode = Constants.MESLAYER_MODE_INT_INPUT;
        overlay.onVarClientIntChanged(new VarClientIntChanged(VarClientID.MESLAYERMODE));

        assertTrue(typedTexts.isEmpty());
        assertEquals(500, overlay.priceToSet);

        // The price prompt opening afterwards still types the armed price.
        promptText = Constants.PRICE_PROMPT_TEXT;
        inputMode = Constants.MESLAYER_MODE_LONG_INPUT;
        overlay.onVarClientIntChanged(new VarClientIntChanged(VarClientID.MESLAYERMODE));

        assertEquals(List.of("500*"), typedTexts);
        assertEquals(-1, overlay.priceToSet);
    }

    @Test
    public void unrelatedInputModeDoesNotTypeThePrice() {
        OfferGraphChartOverlay overlay = overlay();
        overlay.priceToSet = 500;

        inputMode = 6; // name entry, not a numeric offer prompt
        overlay.onVarClientIntChanged(new VarClientIntChanged(VarClientID.MESLAYERMODE));

        assertTrue(typedTexts.isEmpty());
        assertEquals(500, overlay.priceToSet);
    }

    @Test
    public void priceIsTypedOnceThePromptWidgetIsBuilt() {
        OfferGraphChartOverlay overlay = overlay();
        overlay.priceToSet = 900;
        inputWidgetMissingTicks = 2;

        inputMode = Constants.MESLAYER_MODE_LONG_INPUT;
        overlay.onVarClientIntChanged(new VarClientIntChanged(VarClientID.MESLAYERMODE));

        assertEquals(List.of("900*"), typedTexts);
        assertEquals(List.of("900"), typedInputs);
        assertEquals(-1, overlay.priceToSet);
    }

    private OfferGraphChartOverlay overlay() {
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getVarcIntValue":
                        return inputMode;
                    case "getWidget":
                        if ((Integer) args[0] == InterfaceID.Chatbox.MES_TEXT) {
                            return widget(promptText);
                        }
                        if ((Integer) args[0] == InterfaceID.Chatbox.MES_TEXT2) {
                            if (inputWidgetMissingTicks > 0) {
                                inputWidgetMissingTicks--;
                                return null;
                            }
                            return inputWidget();
                        }
                        return null;
                    case "setVarcStrValue":
                        typedInputs.add((String) args[1]);
                        return null;
                    default:
                        return null;
                }
            });

        ClientThread clientThread = new ClientThread() {
            @Override
            public void invokeLater(Runnable r) {
                r.run();
            }
        };

        FlippingConfig config = (FlippingConfig) Proxy.newProxyInstance(FlippingConfig.class.getClassLoader(),
            new Class<?>[]{FlippingConfig.class},
            (proxy, method, args) -> null);

        return new OfferGraphChartOverlay(client, clientThread, null, new EventBus(), config, null);
    }

    private Widget widget(String text) {
        return (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class},
            (proxy, method, args) -> method.getName().equals("getText") ? text : null);
    }

    private Widget inputWidget() {
        return (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class},
            (proxy, method, args) -> {
                if (method.getName().equals("setText")) {
                    typedTexts.add((String) args[0]);
                    return null;
                }
                return null;
            });
    }
}
