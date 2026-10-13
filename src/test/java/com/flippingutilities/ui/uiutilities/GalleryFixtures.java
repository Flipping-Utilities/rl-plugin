package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.utilities.SlotInfo;
import com.flippingutilities.utilities.SlotPredictedState;
import com.flippingutilities.utilities.WikiItemMargins;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Add fixtures here; every factory returns fresh components and synthetic data. */
public final class GalleryFixtures {
    private GalleryFixtures() {}

    public static List<GalleryFixture> all() {
        List<GalleryFixture> fixtures = new ArrayList<>();
        fixtures.addAll(StatisticsGalleryFixtures.all());
        fixtures.add(paginator("first", "First page", 60, 1));
        fixtures.add(paginator("middle", "Middle page", 60, 2));
        fixtures.add(paginator("last", "Last page", 60, 3));
        fixtures.add(paginator("empty", "No results", 0, 1));
        fixtures.add(paginator("many", "Large page count", 200000, 9999));
        fixtures.add(GalleryFixture.component("paginator-shrunk", "Paginator / Results shrink",
            "A previously selected last page clamps when results shrink.", "60 items, page 3 → 2 items, page 1", 80,
            () -> { Paginator p = new Paginator(() -> {}); p.updateTotalPages(60); p.setPageNumber(3); p.updateTotalPages(2); return p; }));
        for (boolean buy : new boolean[]{true, false}) {
            String side = buy ? "Buy" : "Sell";
            fixtures.add(quickLook(side, "Below range", buy, 50,
                buy ? SlotPredictedState.OUT_OF_RANGE : SlotPredictedState.BETTER_THAN_WIKI, 200, 100));
            fixtures.add(quickLook(side, "Within range", buy, 150, SlotPredictedState.IN_RANGE, 200, 100));
            fixtures.add(quickLook(side, "Above range", buy, 250,
                buy ? SlotPredictedState.BETTER_THAN_WIKI : SlotPredictedState.OUT_OF_RANGE, 200, 100));
            fixtures.add(quickLook(side, "Equal prices", buy, 100, SlotPredictedState.BETTER_THAN_WIKI, 100, 100));
        }
        for (boolean missingSlot : new boolean[]{false, true}) {
            fixtures.add(GalleryFixture.component("quick-look-missing-" + (missingSlot ? "offer" : "prices"),
                "Quick Look / Missing " + (missingSlot ? "offer" : "prices"),
                "Reuse a populated panel, then remove data. Previous advice must disappear.",
                "Buy offer 50 gp; wiki 100–200 gp → " + (missingSlot ? "null offer" : "null prices"), 240,
                () -> { QuickLookPanel p = new QuickLookPanel(); SlotInfo slot = slot(true, 50, SlotPredictedState.OUT_OF_RANGE);
                    p.updateDetails(slot, margins(200, 100));
                    p.updateDetails(missingSlot ? null : slot, missingSlot ? margins(200, 100) : null); return p; }));
        }
        fixtures.add(GalleryFixture.component("quick-look-offer-changes", "Quick Look / Buy changes to sell",
            "Reuse a panel after changing offer direction and competitiveness. Old correction text must disappear.",
            "Buy 50 gp, below range → sell 50 gp, better than wiki; wiki low=100, high=200", 240,
            () -> { QuickLookPanel p = new QuickLookPanel();
                p.updateDetails(slot(true, 50, SlotPredictedState.OUT_OF_RANGE), margins(200, 100));
                p.updateDetails(slot(false, 50, SlotPredictedState.BETTER_THAN_WIKI), margins(200, 100)); return p; }));
        fixtures.add(GalleryFixture.component("toggles", "Controls / Toggle states",
            "Production toggle-button factory; enabled examples respond to mouse and keyboard.",
            "Off / on / disabled off / disabled on", 160, GalleryFixtures::toggles));
        return Collections.unmodifiableList(fixtures);
    }

    private static GalleryFixture paginator(String id, String name, int items, int page) {
        return GalleryFixture.component("paginator-" + id, "Paginator / " + name,
            "Use arrows or type a page and press Enter. Reset restores the initial state.",
            "items=" + items + ", pageSize=20, selectedPage=" + page, 80,
            () -> { Paginator p = new Paginator(() -> {}); p.updateTotalPages(items); p.setPageNumber(page); return p; });
    }

    private static GalleryFixture quickLook(String side, String state, boolean buy, int price,
                                            SlotPredictedState prediction, int high, int low) {
        String id = ("quick-look-" + side + "-" + state).toLowerCase(java.util.Locale.ROOT).replace(' ', '-');
        return GalleryFixture.component(id, "Quick Look / " + side + " / " + state,
            "Real Quick Look layout and price advice. Timestamp fields are absent to keep captures reproducible.",
            "offer=" + price + " gp, high=" + high + " gp, low=" + low + " gp, prediction=" + prediction,
            240, () -> { QuickLookPanel p = new QuickLookPanel(); p.updateDetails(slot(buy, price, prediction), margins(high, low)); return p; });
    }

    private static SlotInfo slot(boolean buy, int price, SlotPredictedState prediction) {
        return new SlotInfo(0, prediction, 1, price, buy, false);
    }

    private static WikiItemMargins margins(int high, int low) {
        WikiItemMargins margins = new WikiItemMargins();
        margins.setHigh(high);
        margins.setLow(low);
        return margins;
    }

    private static JComponent toggles() {
        JPanel panel = new JPanel(new GridLayout(4, 2, 8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        for (int i = 0; i < 4; i++) {
            String name = (i < 2 ? "Enabled / " : "Disabled / ") + (i % 2 == 0 ? "off" : "on");
            JToggleButton toggle = UIUtilities.createToggleButton();
            toggle.setSelected(i % 2 != 0);
            toggle.setEnabled(i < 2);
            toggle.getAccessibleContext().setAccessibleName(name);
            panel.add(new JLabel(name));
            panel.add(toggle);
        }
        return panel;
    }
}
