package com.flippingutilities.ui.login;

import com.flippingutilities.controller.ApiAuthHandler;
import com.flippingutilities.controller.FlippingPlugin;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LoginPanelTest {
    @Test
    public void disposeStopsHealthPolling() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FlippingPlugin plugin = new FlippingPlugin() {
                private final ApiAuthHandler authHandler = new ApiAuthHandler(this);

                @Override
                public ApiAuthHandler getApiAuthHandler() {
                    return authHandler;
                }
            };
            LoginPanel panel = new LoginPanel(plugin);
            assertTrue(panel.isHealthPollingActive());

            panel.dispose();

            assertFalse(panel.isHealthPollingActive());
        });
    }
}
