package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.utilities.SlotInfo;
import com.flippingutilities.utilities.SlotPredictedState;
import com.flippingutilities.utilities.WikiItemMargins;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.QuantityFormatter;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The panel that is displayed when you hover over the magnifying glass widget. This panel
 * contains the wiki margins of the item, and whether your offer is competitive or not
 * and what you can do to make it competitive. This essentially provides a faster way
 * to get margin info then finding the item in the sidebar (via searching or clicking on the offer).
 */
@Slf4j
public class QuickLookPanel extends JPanel {
    JLabel wikiInstaBuy = new JLabel("", JLabel.RIGHT);
    JLabel wikiInstaSell = new JLabel("", JLabel.RIGHT);
    JLabel wikiInstaBuyAge = new JLabel("", JLabel.RIGHT);
    JLabel wikiInstaSellAge = new JLabel("", JLabel.RIGHT);
    JLabel offerCompetitivenessText = new JLabel("", JLabel.CENTER);
    JLabel toMakeOfferCompetitiveTest = new JLabel("", JLabel.CENTER);

    public QuickLookPanel() {
        super();
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(10, 15, 10, 15));

        offerCompetitivenessText.setFont(FontManager.getRunescapeBoldFont());
        toMakeOfferCompetitiveTest.setFont(FontManager.getRunescapeSmallFont());
        toMakeOfferCompetitiveTest.setBorder(new EmptyBorder(3, 0, 0, 0));

        wikiInstaBuy.setForeground(Color.WHITE);
        wikiInstaSell.setForeground(Color.WHITE);

        JLabel title = new JLabel("Quick Look", JLabel.CENTER);
        title.setFont(new Font("Whitney", Font.BOLD + Font.ITALIC, 12));

        JLabel wikiInstaBuyDesc = new JLabel("Wiki Insta Buy", JLabel.LEFT);
        JLabel wikiInstaSellDesc = new JLabel("Wiki Insta Sell", JLabel.LEFT);
        JLabel wikiInstaBuyAgeDesc = new JLabel("Wiki Insta Buy Age", JLabel.LEFT);
        JLabel wikiInstaSellAgeDesc = new JLabel("Wiki Insta Sell Age", JLabel.LEFT);

        //being lazy...just want to separate the rows that hold the wiki price vals from the rows that hold the wiki time
        //vals
        wikiInstaBuyAgeDesc.setBorder(new EmptyBorder(5, 0, 0, 0));
        wikiInstaBuyAge.setBorder(new EmptyBorder(5, 0, 0, 0));

        Arrays.asList(wikiInstaBuyDesc, wikiInstaSellDesc, wikiInstaBuyAgeDesc, wikiInstaSellAgeDesc, wikiInstaBuy,
            wikiInstaSell, wikiInstaBuyAge, wikiInstaSellAge).forEach(l -> l.setFont(FontManager.getRunescapeSmallFont()));

        JPanel wikiPanel = new JPanel(new DynamicGridLayout(4, 2, 10, 2));
        wikiPanel.setBorder(new EmptyBorder(10, 10, 0, 10));
        wikiPanel.add(wikiInstaBuyDesc);
        wikiPanel.add(wikiInstaBuy);
        wikiPanel.add(wikiInstaSellDesc);
        wikiPanel.add(wikiInstaSell);

        wikiPanel.add(wikiInstaBuyAgeDesc);
        wikiPanel.add(wikiInstaBuyAge);
        wikiPanel.add(wikiInstaSellAgeDesc);
        wikiPanel.add(wikiInstaSellAge);

        JPanel summaryPanel = new JPanel(new DynamicGridLayout(2, 1));
        summaryPanel.setBorder(new EmptyBorder(10, 0, 0, 0));
        summaryPanel.add(offerCompetitivenessText);
        summaryPanel.add(toMakeOfferCompetitiveTest);

        add(title, BorderLayout.NORTH);
        add(wikiPanel, BorderLayout.CENTER);
        add(summaryPanel, BorderLayout.SOUTH);
    }

    public void updateDetails(SlotInfo slot, WikiItemMargins wikiItemInfo) {
        Arrays.asList(wikiInstaBuy, wikiInstaSell, wikiInstaBuyAge, wikiInstaSellAge).forEach(l -> l.setForeground(Color.WHITE));
        if (wikiItemInfo == null || slot == null) {
            Arrays.asList(wikiInstaBuy, wikiInstaSell, wikiInstaBuyAge, wikiInstaSellAge).forEach(l -> l.setText("No data"));
            return;
        }
        Map<Integer, JLabel> wikiMarginToLabel = new HashMap<>();
        wikiMarginToLabel.put(wikiItemInfo.getHigh(), wikiInstaBuy);
        wikiMarginToLabel.put(wikiItemInfo.getLow(), wikiInstaSell);

        wikiInstaBuyAge.setText(wikiItemInfo.getHighTime() == 0 ? "No data" : TimeFormatters.formatDuration(Instant.ofEpochSecond(wikiItemInfo.getHighTime())));
        wikiInstaSellAge.setText(wikiItemInfo.getLowTime() == 0 ? "No data" : TimeFormatters.formatDuration(Instant.ofEpochSecond(wikiItemInfo.getLowTime())));
        wikiInstaBuy.setText(wikiItemInfo.getHigh() == 0 ? "No data" : QuantityFormatter.formatNumber(wikiItemInfo.getHigh()) + " gp");
        wikiInstaSell.setText(wikiItemInfo.getLow() == 0 ? "No data" : QuantityFormatter.formatNumber(wikiItemInfo.getLow()) + " gp");

        toMakeOfferCompetitiveTest.setText("");
        offerCompetitivenessText.setText("");

        int max = Math.max(wikiItemInfo.getHigh(), wikiItemInfo.getLow());
        int min = Math.min(wikiItemInfo.getHigh(), wikiItemInfo.getLow());

        if (slot.isBuyOffer() && slot.getPredictedState() == SlotPredictedState.BETTER_THAN_WIKI) {
            UIUtilities.recolorLabel(wikiMarginToLabel.get(max), ColorScheme.GRAND_EXCHANGE_PRICE);
            offerCompetitivenessText.setText(
                String.format("<html> buy offer is ultra competitive: %s &gt= %s </html>",
                    UIUtilities.colorText(QuantityFormatter.formatNumber(slot.getOfferPrice()), Color.WHITE),
                    UIUtilities.colorText(QuantityFormatter.formatNumber(max), ColorScheme.GRAND_EXCHANGE_PRICE)
                ));
        } else if (slot.isBuyOffer() && slot.getPredictedState() == SlotPredictedState.IN_RANGE) {
            UIUtilities.recolorLabel(wikiMarginToLabel.get(min), CustomColors.IN_RANGE);
            UIUtilities.recolorLabel(wikiMarginToLabel.get(max), ColorScheme.GRAND_EXCHANGE_PRICE);
            offerCompetitivenessText.setText(
                String.format("<html> buy offer is competitive: %s &lt= %s &lt %s </html>",
                    UIUtilities.colorText(QuantityFormatter.formatNumber(min), CustomColors.IN_RANGE),
                    UIUtilities.colorText(QuantityFormatter.formatNumber(slot.getOfferPrice()), Color.WHITE),
                    UIUtilities.colorText(QuantityFormatter.formatNumber(max), ColorScheme.GRAND_EXCHANGE_PRICE)
                ));
        } else if (slot.isBuyOffer() && slot.getPredictedState() == SlotPredictedState.OUT_OF_RANGE) {
            UIUtilities.recolorLabel(wikiMarginToLabel.get(min), CustomColors.TOMATO);
            offerCompetitivenessText.setText(
                String.format("<html> buy offer is not competitive: %s &lt %s </html>",
                    UIUtilities.colorText(QuantityFormatter.formatNumber(slot.getOfferPrice()), Color.WHITE),
                    UIUtilities.colorText(QuantityFormatter.formatNumber(min), CustomColors.TOMATO)
                ));
            toMakeOfferCompetitiveTest.setText(
                String.format("<html> set price to &gt= %s </html>",
                    UIUtilities.colorText(QuantityFormatter.formatNumber(min), CustomColors.TOMATO)
                ));
        } else if (!slot.isBuyOffer() && slot.getPredictedState() == SlotPredictedState.BETTER_THAN_WIKI) {
            UIUtilities.recolorLabel(wikiMarginToLabel.get(min), ColorScheme.GRAND_EXCHANGE_PRICE);
            offerCompetitivenessText.setText(
                String.format("<html> sell offer is ultra competitive: %s &lt= %s </html>",
                    UIUtilities.colorText(QuantityFormatter.formatNumber(slot.getOfferPrice()), Color.WHITE),
                    UIUtilities.colorText(QuantityFormatter.formatNumber(min), ColorScheme.GRAND_EXCHANGE_PRICE)
                ));
        } else if (!slot.isBuyOffer() && slot.getPredictedState() == SlotPredictedState.IN_RANGE) {
            UIUtilities.recolorLabel(wikiMarginToLabel.get(min), ColorScheme.GRAND_EXCHANGE_PRICE);
            UIUtilities.recolorLabel(wikiMarginToLabel.get(max), CustomColors.IN_RANGE);
            offerCompetitivenessText.setText(
                String.format("<html> sell offer is competitive: %s &lt %s &lt= %s </html>",
                    UIUtilities.colorText(QuantityFormatter.formatNumber(min), ColorScheme.GRAND_EXCHANGE_PRICE),
                    UIUtilities.colorText(QuantityFormatter.formatNumber(slot.getOfferPrice()), Color.WHITE),
                    UIUtilities.colorText(QuantityFormatter.formatNumber(max), CustomColors.IN_RANGE)
                ));
        } else if (!slot.isBuyOffer() && slot.getPredictedState() == SlotPredictedState.OUT_OF_RANGE) {
            UIUtilities.recolorLabel(wikiMarginToLabel.get(max), CustomColors.TOMATO);
            offerCompetitivenessText.setText(
                String.format("<html> sell offer is not competitive: %s &gt %s</html>",
                    UIUtilities.colorText(QuantityFormatter.formatNumber(slot.getOfferPrice()), Color.WHITE),
                    UIUtilities.colorText(QuantityFormatter.formatNumber(max), CustomColors.TOMATO)
                ));
            toMakeOfferCompetitiveTest.setText(
                String.format("<html> set price to &lt= %s </html>",
                    UIUtilities.colorText(QuantityFormatter.formatNumber(max), CustomColors.TOMATO)
                ));
        }
    }
}