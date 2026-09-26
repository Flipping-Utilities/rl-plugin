package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.DataSource;
import com.flippingutilities.FlippingConfig;
import com.flippingutilities.model.Timestep;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Mutable session settings. The storage backend is selected when the sandbox opens. */
final class SandboxConfig implements FlippingConfig {
    private final DataSource storage;
    boolean quickLookup = FlippingConfig.super.quickLookupEnabled();
    boolean offerChart = FlippingConfig.super.offerPageChartEnabled();
    boolean tax = FlippingConfig.super.showTax();
    boolean verbose = FlippingConfig.super.verboseViewEnabled();
    boolean marginLoss = FlippingConfig.super.marginCheckLoss();
    boolean remainingLimit = FlippingConfig.super.geLimitProfit();
    boolean twelveHour = FlippingConfig.super.twelveHourFormat();
    Timestep timestep = FlippingConfig.super.priceGraphTimestep();
    private int roiGradient = FlippingConfig.super.roiGradientMax();

    SandboxConfig(DataSource storage) { this.storage = storage; }

    void read(Path settings) throws IOException {
        if (!Files.isRegularFile(settings)) return;
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(settings)) { values.load(input); }
        quickLookup = bool(values, "quickLookupEnabled", quickLookup);
        offerChart = bool(values, "offerPageChartEnabled", offerChart);
        tax = bool(values, "showTax", tax);
        verbose = bool(values, "verboseView", verbose);
        marginLoss = bool(values, "marginCheckLoss", marginLoss);
        remainingLimit = bool(values, "remainingGELimitProfit", remainingLimit);
        twelveHour = bool(values, "twelveHourFormat", twelveHour);
        try { timestep = Timestep.valueOf(values.getProperty("flipping.priceGraphTimestep", timestep.name()).trim()); }
        catch (IllegalArgumentException ignored) { /* A setting from another plugin version uses the current default. */ }
        try {
            int imported = Integer.parseInt(values.getProperty("flipping.roiGradientMax", Integer.toString(roiGradient)).trim());
            if (imported > 0) roiGradient = imported;
        } catch (NumberFormatException ignored) { /* Keep a usable gradient. */ }
    }

    private static boolean bool(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty("flipping." + key, "").trim();
        return "true".equalsIgnoreCase(value) ? true : "false".equalsIgnoreCase(value) ? false : fallback;
    }

    @Override public DataSource dataSource() { return storage; }
    @Override public boolean autoSaveEnabled() { return false; }
    @Override public boolean quickLookupEnabled() { return quickLookup; }
    @Override public boolean offerPageChartEnabled() { return offerChart; }
    @Override public boolean showTax() { return tax; }
    @Override public boolean verboseViewEnabled() { return verbose; }
    @Override public boolean marginCheckLoss() { return marginLoss; }
    @Override public boolean geLimitProfit() { return remainingLimit; }
    @Override public boolean twelveHourFormat() { return twelveHour; }
    @Override public Timestep priceGraphTimestep() { return timestep; }
    @Override public int roiGradientMax() { return roiGradient; }
}
