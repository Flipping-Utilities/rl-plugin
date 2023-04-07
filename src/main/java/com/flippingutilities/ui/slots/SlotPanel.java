package com.flippingutilities.ui.slots;

import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.ui.uiutilities.CustomColors;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.components.ThinProgressBar;
import net.runelite.client.util.QuantityFormatter;


import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.Arrays;

public class SlotPanel extends JPanel {
    private FlippingPlugin plugin;
    public int itemId;
    private OfferEvent offerEvent;
    private ThinProgressBar progressBar = new ThinProgressBar();
    private JLabel itemName = new JLabel();
    private JLabel itemIcon = new JLabel();
    private JLabel price = new JLabel();
    private JLabel state = new JLabel();
    private JLabel action = new JLabel();
    private JLabel timer = new JLabel();
    private Component verticalGap;

    WikiRequest wikiRequest;
    Instant timeOfRequestCompletion;
    JLabel wikiBuyText = new JLabel("Wiki insta buy: ");
    JLabel wikiSellText = new JLabel("Wiki insta sell: ");
    JLabel wikiBuyTimeText = new JLabel("Wiki insta buy age: ");
    JLabel wikiSellTimeText = new JLabel("Wiki insta sell age: ");
    JLabel wikiBuyVal = new JLabel();
    JLabel wikiSellVal = new JLabel();
    JLabel wikiBuyTimeVal = new JLabel();
    JLabel wikiSellTimeVal = new JLabel();
    JLabel wikiRequestCountDownTimer = new JLabel();
    JLabel refreshIconLabel = new JLabel();
    JPopupMenu popup;

    public SlotPanel(FlippingPlugin plugin, Component verticalGap) {
        this.plugin = plugin;
        this.verticalGap = verticalGap;
        setVisible(false);
        setLayout(new BorderLayout());
        setBackground(CustomColors.DARK_GRAY);
        setBorder(new CompoundBorder(
                new MatteBorder(2, 2, 2, 2, ColorScheme.DARKER_GRAY_COLOR.darker()),
                new EmptyBorder(10, 10, 0, 10)
        ));
        price.setAlignmentX(JLabel.CENTER_ALIGNMENT);
        price.setFont(FontManager.getRunescapeSmallFont());
        state.setFont(FontManager.getRunescapeSmallFont());
        action.setFont(FontManager.getRunescapeBoldFont());
        action.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);
        timer.setFont(FontManager.getRunescapeSmallFont());

        progressBar.setForeground(CustomColors.DARK_GRAY);
        progressBar.setMaximumValue(100);
        progressBar.setValue(0);
        progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 10));
        progressBar.setMinimumSize(new Dimension(0, 10));
        progressBar.setPreferredSize(new Dimension(0, 10));
        progressBar.setSize(new Dimension(0, 10));
        add(itemIcon, BorderLayout.WEST);

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBorder(new EmptyBorder(0,0,10,0));
        topPanel.setBackground(CustomColors.DARK_GRAY);
        topPanel.add(action, BorderLayout.WEST);
        topPanel.add(timer, BorderLayout.EAST);
        add(topPanel, BorderLayout.NORTH);


        JPanel centerPanel = new JPanel();
        centerPanel.setBackground(CustomColors.DARK_GRAY);
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.add(itemName);
        centerPanel.add(Box.createVerticalStrut(3));
        centerPanel.add(state);
        add(centerPanel, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel();
        bottomPanel.setBorder(new EmptyBorder(10, 20,5,20));
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));
        bottomPanel.setBackground(CustomColors.DARK_GRAY);

        JPanel wikiBuyPricesPanel = new JPanel(new BorderLayout());
        wikiBuyPricesPanel.setBackground(CustomColors.DARK_GRAY);
        wikiBuyText.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
        wikiBuyText.setFont(plugin.getFont());
        wikiBuyPricesPanel.add(wikiBuyText, BorderLayout.WEST);
        wikiBuyPricesPanel.add(wikiBuyVal, BorderLayout.EAST);
        wikiBuyPricesPanel.setBorder(new EmptyBorder(6,0,3,0));

        JPanel wikiSellPricesPanel = new JPanel(new BorderLayout());
        wikiSellPricesPanel.setBackground(CustomColors.DARK_GRAY);
        wikiSellText.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
        wikiSellText.setFont(plugin.getFont());
        wikiSellPricesPanel.add(wikiSellText, BorderLayout.WEST);
        wikiSellPricesPanel.add(wikiSellVal, BorderLayout.EAST);
        wikiSellPricesPanel.setBorder(new EmptyBorder(2,0,8,0));
        styleValueLabels();

        bottomPanel.add(wikiBuyPricesPanel);
        bottomPanel.add(wikiSellPricesPanel);
        bottomPanel.add(progressBar);
        bottomPanel.add(Box.createVerticalStrut(10));
        bottomPanel.add(price);
        add(bottomPanel, BorderLayout.SOUTH);

    }

    public void updateTimer(String timeString) {
        if (offerEvent == null || offerEvent.isCausedByEmptySlot() || timeString == null) {
            return;
        }
        timer.setText(timeString);

        // update wiki prices
        updateWikiLabels(plugin.getLastWikiRequest(), plugin.getTimeOfLastWikiRequest());
    }

    public boolean shouldUpdate(OfferEvent newOfferEvent) {
        return offerEvent == null || !offerEvent.isDuplicate(newOfferEvent);
    }

    public void update(BufferedImage itemImage, String name, OfferEvent newOfferEvent) {
        if (newOfferEvent.isCausedByEmptySlot()) {
            setVisible(false);
            verticalGap.setVisible(false);
            itemId = 0;
            itemIcon.setIcon(null);
            itemName.setText("");
            progressBar.setMaximumValue(0);
            progressBar.setValue(100);
            progressBar.setForeground(CustomColors.DARK_GRAY);
            progressBar.setBackground(CustomColors.DARK_GRAY);
            offerEvent = null;
        } else {
            setVisible(true);
            verticalGap.setVisible(true);
            offerEvent = newOfferEvent;
            itemId = newOfferEvent.getItemId();
            if (name != null) {
                itemName.setText(name);
            }
            if (itemImage != null) {
                itemIcon.setIcon(new ImageIcon(itemImage));
            }
            String stateText = QuantityFormatter.quantityToRSDecimalStack(newOfferEvent.getCurrentQuantityInTrade()) + " / "
                    + QuantityFormatter.quantityToRSDecimalStack(newOfferEvent.getTotalQuantityInTrade());

            action.setText(newOfferEvent.isBuy()? "Buy":"Sell");
            state.setText(stateText);
            price.setText(QuantityFormatter.formatNumber(newOfferEvent.getListedPrice()) + " coins");
            progressBar.setMaximumValue(newOfferEvent.getTotalQuantityInTrade());
            progressBar.setValue(newOfferEvent.getCurrentQuantityInTrade());
            if (newOfferEvent.isCancelled()) {
                progressBar.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
            }
            else if (newOfferEvent.isComplete()) {
                progressBar.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
            }
            else {
                progressBar.setForeground(ColorScheme.PROGRESS_INPROGRESS_COLOR);
            }

        }
    }

    public void updateWikiLabels(WikiRequest wr, Instant requestCompletionTime) {
        timeOfRequestCompletion = requestCompletionTime;
        wikiRequest = wr;

        if (wikiRequest == null) {
            wikiBuyVal.setText("N/A");
            wikiSellVal.setText("N/A");
            return;
        }

        WikiItemMargins wikiItemInfo = wikiRequest.getData().get(this.itemId);
        if (wikiItemInfo == null) {
            return;
        }
        wikiBuyVal.setText(wikiItemInfo.getHigh()==0? "No data":QuantityFormatter.formatNumber(wikiItemInfo.getHigh()) + " gp");
        wikiSellVal.setText(wikiItemInfo.getLow()==0? "No data":QuantityFormatter.formatNumber(wikiItemInfo.getLow()) + " gp");

        updateWikiTimeLabels();
    }

    public void updateWikiTimeLabels() {
        //can be called before wikiRequest is set cause is is called in the repeating task which can start before the
        //request is completed
        if (wikiRequest == null) {
            wikiBuyTimeVal.setText("Request not made yet");
            wikiSellTimeVal.setText("Request not made yet");
            wikiRequestCountDownTimer.setText("N/A");
            return;
        }
        //probably don't need this. Should always be non null if wikiRequest is not null
        if (timeOfRequestCompletion != null) {
            long secondsSinceLastRequestCompleted = Instant.now().getEpochSecond() - timeOfRequestCompletion.getEpochSecond();
            if (secondsSinceLastRequestCompleted >= WikiDataFetcherJob.requestInterval) {
                wikiRequestCountDownTimer.setText("0");
                refreshIconLabel.setEnabled(true);
            }
            else {
                refreshIconLabel.setEnabled(false);
                wikiRequestCountDownTimer.setText(String.valueOf(WikiDataFetcherJob.requestInterval - secondsSinceLastRequestCompleted));
            }
        }

        WikiItemMargins wikiItemInfo = wikiRequest.getData().get(this.itemId);
        if (wikiItemInfo == null) {
            return;
        }
        if (wikiItemInfo.getHighTime() == 0) {
            wikiBuyTimeVal.setText("No data");
        }
        else {
            wikiBuyTimeVal.setText(TimeFormatters.formatDuration(Instant.ofEpochSecond(wikiItemInfo.getHighTime())));
        }
        if (wikiItemInfo.getLowTime() == 0) {
            wikiBuyTimeVal.setText("No data");
        }
        else {
            wikiSellTimeVal.setText(TimeFormatters.formatDuration(Instant.ofEpochSecond(wikiItemInfo.getLowTime())));
        }
    }

    //panel that is shown when someone hovers over the wiki buy/sell value labels
    private JPanel createWikiHoverTimePanel() {
        wikiBuyTimeText.setFont(FontManager.getRunescapeSmallFont());
        wikiBuyTimeText.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
        wikiSellTimeText.setFont(FontManager.getRunescapeSmallFont());
        wikiSellTimeText.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);

        wikiSellTimeVal.setFont(FontManager.getRunescapeSmallFont());
        wikiBuyTimeVal.setFont(FontManager.getRunescapeSmallFont());

        JPanel wikiTimePanel = new JPanel();
        wikiTimePanel.setLayout(new BoxLayout(wikiTimePanel, BoxLayout.Y_AXIS));
        wikiTimePanel.setBorder(new EmptyBorder(5,5,5,5));

        JPanel buyTimePanel = new JPanel(new BorderLayout());
        buyTimePanel.add(wikiBuyTimeText, BorderLayout.WEST);
        buyTimePanel.add(wikiBuyTimeVal, BorderLayout.EAST);

        JPanel sellTimePanel = new JPanel(new BorderLayout());
        sellTimePanel.add(wikiSellTimeText, BorderLayout.WEST);
        sellTimePanel.add(wikiSellTimeVal, BorderLayout.EAST);

        wikiTimePanel.add(buyTimePanel);
        wikiTimePanel.add(Box.createVerticalStrut(5));
        wikiTimePanel.add(sellTimePanel);

        return wikiTimePanel;
    }

    private void styleValueLabels() {

        wikiBuyVal.setFont(CustomFonts.SMALLER_RS_BOLD_FONT);
        wikiBuyVal.setForeground(Color.WHITE);
        wikiSellVal.setFont(CustomFonts.SMALLER_RS_BOLD_FONT);
        wikiSellVal.setForeground(Color.WHITE);

        wikiRequestCountDownTimer.setAlignmentY(JLabel.TOP);
        wikiRequestCountDownTimer.setFont(new Font(Font.SERIF, Font.PLAIN, 9));

        popup = new JPopupMenu();
        popup.add(createWikiHoverTimePanel());
        UIUtilities.addPopupOnHover(wikiBuyVal, popup, true);
        UIUtilities.addPopupOnHover(wikiSellVal, popup, true);
    }
}
