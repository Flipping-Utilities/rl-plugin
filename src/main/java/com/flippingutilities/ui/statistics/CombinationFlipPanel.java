package com.flippingutilities.ui.statistics;

import com.flippingutilities.model.CombinationFlip;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.ui.uiutilities.CustomColors;
import com.flippingutilities.ui.uiutilities.TimeFormatters;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.QuantityFormatter;

import javax.swing.*;
import java.awt.*;

public class CombinationFlipPanel extends JPanel {
    private JLabel timeDisplay;
    private CombinationFlip combinationFlip;

    public CombinationFlipPanel(CombinationFlip combinationFlip) {
        this.combinationFlip = combinationFlip;
        setBackground(CustomColors.DARK_GRAY);
        setLayout(new BorderLayout());

        timeDisplay = createTimeDisplay(combinationFlip);

        add(createTitlePanel(), BorderLayout.NORTH);
        add(createBodyPanel(), BorderLayout.CENTER);
    }


    private JPanel createTitlePanel() {
        JPanel titlePanel = new JPanel();
        titlePanel.setLayout(new DynamicGridLayout(2,1));
        titlePanel.setBackground(CustomColors.DARK_GRAY);

        String actionText = combinationFlip.getParent().offer.isBuy()? "Broke" : "Constructed";
        String parentName = combinationFlip.getParent().getOffer().getItemName();
        String quantityInCombination = QuantityFormatter.formatNumber(combinationFlip.getParent().offer.getCurrentQuantityInTrade());

        JLabel quantityLabel = new JLabel(quantityInCombination + "x");
        quantityLabel.setFont(FontManager.getRunescapeSmallFont());

        JPanel quantityAndTimePanel = new JPanel();
        quantityAndTimePanel.setBackground(CustomColors.DARK_GRAY);
        quantityAndTimePanel.add(quantityLabel);
        quantityAndTimePanel.add(timeDisplay);

        titlePanel.add(new JLabel(actionText + " " +  parentName, SwingConstants.CENTER));
        titlePanel.add(quantityAndTimePanel);
        return titlePanel;
    }

    private JLabel createTimeDisplay(CombinationFlip combinationFlip) {
        OfferEvent offer = combinationFlip.getParent().offer;
        JLabel timeDisplay = new JLabel(
                "(" + TimeFormatters.formatDurationTruncated(offer.getTime()) + " ago)",
                SwingConstants.CENTER);
        timeDisplay.setBackground(CustomColors.DARK_GRAY);
        timeDisplay.setOpaque(true);
        timeDisplay.setFont(FontManager.getRunescapeSmallFont());
        timeDisplay.setForeground(offer.isBuy() ? CustomColors.OUTDATED_COLOR : ColorScheme.GRAND_EXCHANGE_PRICE);

        return timeDisplay;
    }

    private JPanel createBodyPanel() {
        JPanel bodyPanel = new JPanel();

        return bodyPanel;
    }

    public void updateTimeDisplay() {
        OfferEvent offer = combinationFlip.getParent().offer;
        timeDisplay.setText("(" + TimeFormatters.formatDurationTruncated(offer.getTime()) + " ago)");
    }
 }
