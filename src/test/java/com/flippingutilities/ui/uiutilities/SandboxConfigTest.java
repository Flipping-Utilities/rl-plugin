package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.DataSource;
import com.flippingutilities.model.Timestep;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class SandboxConfigTest {
    @Test
    public void importsDisplaySettingsWithoutChangingTheChosenBackendOrEnablingAutosave() throws Exception {
        Path settings = Files.createTempFile("sandbox-settings-", ".properties");
        try {
            Files.write(settings, ("flipping.quickLookupEnabled=false\nflipping.offerPageChartEnabled=false\n"
                + "flipping.priceGraphTimestep=ONE_HOUR\nflipping.showTax=false\nflipping.verboseView=false\n"
                + "flipping.marginCheckLoss=false\nflipping.remainingGELimitProfit=true\nflipping.twelveHourFormat=false\n"
                + "flipping.dataSource=SQLITE\nflipping.autoSaveEnabled=true\nflipping.roiGradientMax=0\n")
                .getBytes(StandardCharsets.ISO_8859_1));
            SandboxConfig config = new SandboxConfig(DataSource.JSON);
            config.read(settings);
            assertFalse(config.quickLookupEnabled());
            assertFalse(config.offerPageChartEnabled());
            assertFalse(config.showTax());
            assertFalse(config.verboseViewEnabled());
            assertFalse(config.marginCheckLoss());
            assertTrue(config.geLimitProfit());
            assertFalse(config.twelveHourFormat());
            assertEquals(Timestep.ONE_HOUR, config.priceGraphTimestep());
            assertEquals(DataSource.JSON, config.dataSource());
            assertFalse(config.autoSaveEnabled());
            assertEquals(2, config.roiGradientMax());
            Files.write(settings, "flipping.quickLookupEnabled=unknown\nflipping.priceGraphTimestep=OLD_VALUE\n"
                .getBytes(StandardCharsets.ISO_8859_1));
            SandboxConfig defaults = new SandboxConfig(DataSource.JSON);
            defaults.read(settings);
            assertTrue(defaults.quickLookupEnabled());
            assertEquals(Timestep.FIVE_MINUTES, defaults.priceGraphTimestep());
        } finally { Files.deleteIfExists(settings); }
    }
}
