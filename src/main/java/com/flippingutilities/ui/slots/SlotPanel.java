package com.flippingutilities.ui.slots;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.ui.uiutilities.CustomColors;
import com.flippingutilities.ui.uiutilities.Icons;
import com.flippingutilities.ui.uiutilities.QuickLookPanel;
import com.flippingutilities.ui.uiutilities.UIUtilities;
import com.flippingutilities.utilities.SlotInfo;
import com.flippingutilities.utilities.SlotPredictedState;
import com.flippingutilities.utilities.WikiItemMargins;
import com.flippingutilities.utilities.WikiRequest;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.components.ThinProgressBar;
import net.runelite.client.util.QuantityFormatter;


import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;

public class SlotPanel extends JPanel {
    public int itemId;
    public OfferEvent offerEvent;
    private ThinProgressBar progressBar = new ThinProgressBar();
    private JLabel itemName = new JLabel();
    private JLabel itemIcon = new JLabel();
    private JLabel price = new JLabel();
    private JLabel state = new JLabel();
    private JLabel action = new JLabel();
    private JLabel timer = new JLabel();
    private Component verticalGap;
    WikiRequest wikiRequest;
    List<JPanel> panelsToColor;
    boolean hasDrawnStatus = false;
    JPopupMenu popup;
    QuickLookPanel quickLookPanel;
    FlippingPlugin plugin;

    public SlotPanel(FlippingPlugin plugin, Component verticalGap, JPopupMenu popup, QuickLookPanel quickLookPanel) {
        this.plugin = plugin;
        this.popup = popup;
        this.verticalGap = verticalGap;
        this.quickLookPanel = quickLookPanel;
        setVisible(false);
        setLayout(new BorderLayout());
        setBackground(CustomColors.DARK_GRAY);
        setBorder(new CompoundBorder(
            new MatteBorder(2, 2, 2, 2, ColorScheme.DARKER_GRAY_COLOR.darker()),
            new EmptyBorder(10, 10, 0, 10)
        ));
        price.setHorizontalAlignment(SwingConstants.CENTER);
        price.setFont(FontManager.getRunescapeSmallFont());
        price.setBorder(new EmptyBorder(0, 16, 0, 0)); //to center it bc of the 16px magnifying glass icon
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
        //progressBar.setBorder(new EmptyBorder(0, 25, 0, 25));
        progressBar.setBorder(new CompoundBorder(
            new MatteBorder(1, 1, 1, 1, ColorScheme.DARKER_GRAY_COLOR.darker()),
            new EmptyBorder(0, 23, 0, 23)
        ));
        add(itemIcon, BorderLayout.WEST);

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBorder(new EmptyBorder(0, 0, 10, 0));
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
        bottomPanel.setBorder(new EmptyBorder(10, 5, 5, 5));
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));
        bottomPanel.setBackground(CustomColors.DARK_GRAY);
        bottomPanel.add(progressBar);
        bottomPanel.add(Box.createVerticalStrut(10));

        JPanel priceAndMagnifyingGlassPanel = new JPanel(new BorderLayout());
        priceAndMagnifyingGlassPanel.setBackground(CustomColors.DARK_GRAY);

        JLabel magnifyingGlassIcon = new JLabel(Icons.MAGNIFYING_GLASS);
        magnifyingGlassIcon.setVisible(false);
        magnifyingGlassIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                updateQuickLookPanel();
                magnifyingGlassIcon.setIcon(Icons.MAGNIFYING_GLASS_HOVER);
                Point location  = magnifyingGlassIcon.getLocationOnScreen();
                int y = location.y - popup.getHeight();
                popup.setLocation(location.x, y);
                popup.setVisible(true);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                magnifyingGlassIcon.setIcon(Icons.MAGNIFYING_GLASS);
                popup.setVisible(false);
            }
        });

        priceAndMagnifyingGlassPanel.add(price, BorderLayout.CENTER);
        priceAndMagnifyingGlassPanel.add(magnifyingGlassIcon, BorderLayout.EAST);

        bottomPanel.add(priceAndMagnifyingGlassPanel);
        add(bottomPanel, BorderLayout.SOUTH);

        panelsToColor = Arrays.asList(topPanel, centerPanel, bottomPanel, priceAndMagnifyingGlassPanel);

        plugin.getApiAuthHandler().subscribeToPremiumChecking(magnifyingGlassIcon::setVisible);
    }

    public void updateTimer(String timeString) {
        if (offerEvent == null || offerEvent.isCausedByEmptySlot() || timeString == null) {
            return;
        }
        timer.setText(timeString);
    }

    public boolean shouldNotUpdate(OfferEvent newOfferEvent) {
        return offerEvent != null && offerEvent.isDuplicate(newOfferEvent);
    }

    public void onWikiRequest(WikiRequest wikiRequest) {
        this.wikiRequest = wikiRequest;
        drawSlotStatus();
    }

    public void updateQuickLookPanel() {
        if (offerEvent == null || offerEvent.isCausedByEmptySlot() || wikiRequest == null) {
            quickLookPanel.updateDetails(null, null);
            return;
        }
        WikiItemMargins margins = wikiRequest.getData().get(offerEvent.getItemId());

        if (margins == null) {
            quickLookPanel.updateDetails(null, null);
            return;
        }

        SlotPredictedState predictedState = SlotPredictedState.getPredictedState(offerEvent.isBuy(),
            offerEvent.getListedPrice(), margins.getLow(), margins.getHigh());
        SlotInfo slotInfo = new SlotInfo(offerEvent.getSlot(), predictedState, offerEvent.getItemId(),
            offerEvent.getListedPrice(), offerEvent.isBuy());
        quickLookPanel.updateDetails(slotInfo, margins);
    }

    public void drawSlotStatus() {
        if (!plugin.getApiAuthHandler().isPremium()) {
            return;
        }
        if (offerEvent == null || offerEvent.isCausedByEmptySlot() || wikiRequest == null) {
            return;
        }

        WikiItemMargins margins = wikiRequest.getData().get(offerEvent.getItemId());
        if (margins == null) {
            return;
        }

        SlotPredictedState predictedState = SlotPredictedState.getPredictedState(offerEvent.isBuy(),
            offerEvent.getListedPrice(),  margins.getLow(), margins.getHigh());
        if (predictedState == SlotPredictedState.IN_RANGE) {
            setColor(CustomColors.IN_RANGE_SLOTS_TAB);
        } else if (predictedState == SlotPredictedState.OUT_OF_RANGE) {
            setColor(CustomColors.OUT_OF_RANGE_SLOTS_TAB);
        } else if (predictedState == SlotPredictedState.BETTER_THAN_WIKI) {
            setColor(CustomColors.BETTER_THAN_WIKI_SLOTS_TAB);
        }
        hasDrawnStatus = true;
    }

    public void reset() {
        setVisible(false);
        verticalGap.setVisible(false);
        itemId = 0;
        itemIcon.setIcon(null);
        itemName.setText("");
        progressBar.setMaximumValue(0);
        progressBar.setValue(100);
        progressBar.setForeground(CustomColors.DARK_GRAY);
        progressBar.setBackground(CustomColors.DARK_GRAY);
        setColor(CustomColors.DARK_GRAY);
        hasDrawnStatus = false;
    }

    private void setColor(Color color) {
        setBackground(color);
        panelsToColor.forEach(p -> p.setBackground(color));
    }

    public void update(BufferedImage itemImage, String name, OfferEvent newOfferEvent) {
        setVisible(true);
        verticalGap.setVisible(true);
        itemId = newOfferEvent.getItemId();
        if (name != null) {
            itemName.setText(name);
        }
        if (itemImage != null) {
            itemIcon.setIcon(new ImageIcon(itemImage));
        }
        String stateText = QuantityFormatter.quantityToRSDecimalStack(newOfferEvent.getCurrentQuantityInTrade()) + " / "
            + QuantityFormatter.quantityToRSDecimalStack(newOfferEvent.getTotalQuantityInTrade());

        action.setText(newOfferEvent.isBuy() ? "Buy" : "Sell");
        state.setText(stateText);
        price.setText(QuantityFormatter.formatNumber(newOfferEvent.getListedPrice()) + " coins");
        progressBar.setMaximumValue(newOfferEvent.getTotalQuantityInTrade());
        progressBar.setValue(newOfferEvent.getCurrentQuantityInTrade());
        if (newOfferEvent.isCancelled()) {
            progressBar.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
        } else if (newOfferEvent.isComplete()) {
            progressBar.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
        } else {
            progressBar.setForeground(ColorScheme.PROGRESS_INPROGRESS_COLOR);
        }

        //when the slot panel gets an offer event, either because its the first one or because a new offer was created
        //we should draw the slot status immediately if we have a wiki request on hand rather than waiting for
        //drawSlotStatus to trigger on the next wiki fetch.
        if (!hasDrawnStatus) {
            drawSlotStatus();
        }
    }
}
