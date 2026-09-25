package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.utilities.SlotInfo;
import com.flippingutilities.utilities.SlotPredictedState;
import com.flippingutilities.utilities.WikiItemMargins;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.time.Instant;

import static org.junit.Assert.*;

public class QuickLookPanelTest {
    @Test
    public void unavailableDataClearsAdviceFromThePreviousOffer() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuickLookPanel panel = new QuickLookPanel();
            SlotInfo slot = new SlotInfo(0, SlotPredictedState.OUT_OF_RANGE, 1, 50, true, false);
            for (boolean missingSlot : new boolean[]{false, true}) {
                panel.updateDetails(slot, margins(200, 100));
                assertTrue(panel.offerCompetitivenessText.getText().contains("not competitive"));
                assertFalse(panel.toMakeOfferCompetitiveTest.getText().isEmpty());
                panel.updateDetails(missingSlot ? null : slot, missingSlot ? margins(200, 100) : null);
                assertEquals("No data", panel.wikiInstaBuy.getText());
                assertEquals("No data", panel.wikiInstaSell.getText());
                assertEquals("No data", panel.wikiInstaBuyAge.getText());
                assertEquals("No data", panel.wikiInstaSellAge.getText());
                assertEquals("", panel.offerCompetitivenessText.getText());
                assertEquals("", panel.toMakeOfferCompetitiveTest.getText());
            }
        });
    }

    @Test
    public void equalPricesHighlightBothRowsForBuyAndSellOffers() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (boolean buy : new boolean[]{true, false}) {
                QuickLookPanel panel = new QuickLookPanel();
                panel.updateDetails(new SlotInfo(0, SlotPredictedState.BETTER_THAN_WIKI, 1, 100, buy, false),
                    margins(100, 100));
                assertTrue(panel.wikiInstaBuy.getText().startsWith("<html>"));
                assertEquals(panel.wikiInstaBuy.getText(), panel.wikiInstaSell.getText());
                assertTrue(panel.offerCompetitivenessText.getText().contains("ultra competitive"));
            }
        });
    }

    @Test
    public void changingOfferRestoresPlainPricesAndClearsThePreviousCorrection() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuickLookPanel panel = new QuickLookPanel();
            panel.updateDetails(new SlotInfo(0, SlotPredictedState.OUT_OF_RANGE, 1, 50, true, false),
                margins(200, 100));
            panel.updateDetails(new SlotInfo(0, SlotPredictedState.BETTER_THAN_WIKI, 2, 200, true, false),
                margins(200, 100));
            assertTrue(panel.wikiInstaBuy.getText().startsWith("<html>"));
            assertEquals("100 gp", panel.wikiInstaSell.getText());
            assertEquals("", panel.toMakeOfferCompetitiveTest.getText());
        });
    }

    static WikiItemMargins margins(int high, int low) {
        WikiItemMargins margins = new WikiItemMargins();
        margins.setHigh(high);
        margins.setLow(low);
        margins.setHighTime(Instant.now().minusSeconds(60).getEpochSecond());
        margins.setLowTime(Instant.now().minusSeconds(120).getEpochSecond());
        return margins;
    }
}
