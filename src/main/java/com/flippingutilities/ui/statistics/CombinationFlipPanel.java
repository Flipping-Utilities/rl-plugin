package com.flippingutilities.ui.statistics;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.CombinationFlip;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.ui.uiutilities.CustomColors;
import com.flippingutilities.ui.uiutilities.Icons;
import com.flippingutilities.ui.uiutilities.TimeFormatters;
import com.flippingutilities.ui.uiutilities.UIUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.QuantityFormatter;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The visual representation of a CombinationFlip. CombinationFlipPanels
 * are shown in the "combos" tab of the trade history section of an item in the
 * statistics tab.
 */
public class CombinationFlipPanel extends JPanel {
    private JLabel timeDisplay;
    private CombinationFlip combinationFlip;
    private FlippingPlugin plugin;
    private FlippingItem item;

    public CombinationFlipPanel(CombinationFlip combinationFlip, FlippingPlugin plugin, FlippingItem item) {
        this.combinationFlip = combinationFlip;
        this.plugin = plugin;
        this.item = item;
        setBackground(CustomColors.DARK_GRAY);
        setBorder(new EmptyBorder(5,5,5,5));
        setLayout(new BorderLayout());

        timeDisplay = createTimeDisplay(combinationFlip);

        add(createTitlePanel(), BorderLayout.NORTH);
        add(createProfitPanel(), BorderLayout.CENTER);
        add(createDetailsPanel(), BorderLayout.SOUTH);
    }


    private JPanel createTitlePanel() {
        JPanel titlePanel = new JPanel();
        titlePanel.setLayout(new DynamicGridLayout(2,1));
        titlePanel.setBackground(CustomColors.DARK_GRAY);

        String actionText = combinationFlip.getParent().offer.isBuy()? "Broke" : "Constructed";
        String parentName = combinationFlip.getParent().getOffer().getItemName();
        String quantityInCombination = QuantityFormatter.formatNumber(combinationFlip.getParent().amountConsumed);

        JLabel quantityLabel = new JLabel(quantityInCombination + "x");
        quantityLabel.setFont(FontManager.getRunescapeSmallFont());

        JPanel quantityAndTimePanel = new JPanel();
        quantityAndTimePanel.setBackground(CustomColors.DARK_GRAY);
        quantityAndTimePanel.add(quantityLabel);
        quantityAndTimePanel.add(timeDisplay);

        JLabel itemNameAndActionLabel = new JLabel(actionText + " " +  parentName, SwingConstants.CENTER);
        itemNameAndActionLabel.setFont(FontManager.getRunescapeSmallFont());
        itemNameAndActionLabel.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);

        titlePanel.add(itemNameAndActionLabel);
        titlePanel.add(quantityAndTimePanel);
        return titlePanel;
    }

    private JLabel createTimeDisplay(CombinationFlip combinationFlip) {
        OfferEvent offer = combinationFlip.getParent().offer;
        JLabel timeDisplay = new JLabel(
                "(" + TimeFormatters.formatDurationTruncated(offer.getTime()) + " ago)",
                SwingConstants.CENTER);
        timeDisplay.setFont(FontManager.getRunescapeSmallFont());
        return timeDisplay;
    }

    private JPanel createDetailsPanel() {
        List<JPanel> partialOfferPanels = new ArrayList<>();
        partialOfferPanels.add(createPartialOfferPanel(List.of(combinationFlip.getParent()))); //parent po panel
        partialOfferPanels.addAll( //children po panels
                combinationFlip.getChildren().values().stream().map(
                m -> createPartialOfferPanel(new ArrayList<>(m.values()))).
                collect(Collectors.toList()));

        JPanel extraDetailsPanel = UIUtilities.stackPanelsVertically(partialOfferPanels, 2);
        extraDetailsPanel.setBorder(new EmptyBorder(5,0,0,0));
        extraDetailsPanel.setBackground(CustomColors.DARK_GRAY);
        extraDetailsPanel.setVisible(false);

        JLabel expandDetailsLabel = new JLabel("Expand Details", SwingConstants.CENTER);
        Color c = expandDetailsLabel.getForeground();
        expandDetailsLabel.setFont(FontManager.getRunescapeSmallFont());
        Font font=new Font(expandDetailsLabel.getFont().getName(),Font.ITALIC,expandDetailsLabel.getFont().getSize());
        expandDetailsLabel.setFont(font);
        UIUtilities.makeLabelUnderlined(expandDetailsLabel);
        expandDetailsLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                extraDetailsPanel.setVisible(!extraDetailsPanel.isVisible());
                expandDetailsLabel.setText(extraDetailsPanel.isVisible()? "Collapse Details": "Expand Details");
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                expandDetailsLabel.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                expandDetailsLabel.setForeground(c);
            }
        });

        JPanel expandDetailsPanel = new JPanel(new BorderLayout());
        expandDetailsPanel.setBorder(new EmptyBorder(8,0,0,0));
        expandDetailsPanel.setBackground(CustomColors.DARK_GRAY);
        expandDetailsPanel.add(expandDetailsLabel, BorderLayout.CENTER);
        expandDetailsPanel.add(createDeleteIcon(), BorderLayout.WEST);

        JPanel detailsPanel = new JPanel(new BorderLayout());
        detailsPanel.setBackground(CustomColors.DARK_GRAY);
        detailsPanel.setBorder(new EmptyBorder(5,0,0,0));
        detailsPanel.add(expandDetailsPanel, BorderLayout.NORTH);
        detailsPanel.add(extraDetailsPanel, BorderLayout.CENTER);

        return detailsPanel;
    }

    private JLabel createDeleteIcon() {
        JLabel deleteIcon = new JLabel(Icons.TRASH_CAN_OFF);
        deleteIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (plugin.getAccountCurrentlyViewed().equals(FlippingPlugin.ACCOUNT_WIDE)) {
                    JOptionPane.showMessageDialog(null, "You cannot delete combo flips in the Accountwide view");
                    return;
                }
                final int result = JOptionPane.showOptionDialog(deleteIcon, "Are you sure you want to delete this combo flip?",
                        "Are you sure?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
                        null, new String[]{"Yes", "No"}, "No");

                if (result == JOptionPane.YES_OPTION) {
                    item.deleteCombinationFlip(combinationFlip, plugin.viewTradesForCurrentView());
                    plugin.getStatPanel().rebuild(plugin.viewTradesForCurrentView());
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                deleteIcon.setIcon(Icons.TRASH_CAN_ON);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                deleteIcon.setIcon(Icons.TRASH_CAN_OFF);
            }
        });

        return deleteIcon;
    }

    private JPanel createPartialOfferPanel(List<PartialOffer> partialOffers) {
        String itemName = partialOffers.get(0).offer.getItemName();
        int quantity = partialOffers.stream().mapToInt(po -> po.amountConsumed).sum();
        int itemPrice = partialOffers.stream().mapToInt(po -> po.offer.getPrice() * po.amountConsumed).sum()/quantity;

        JLabel itemNameLabel = new JLabel(itemName, SwingConstants.CENTER);
        itemNameLabel.setFont(FontManager.getRunescapeSmallFont());

        JLabel quantityDescLabel = new JLabel("Quantity:");
        JLabel quantityValueLabel = new JLabel(String.valueOf(quantity));

        JLabel priceDescLabel = new JLabel("Price ea:");
        JLabel priceValueLabel = new JLabel(QuantityFormatter.formatNumber(itemPrice) + " gp");

        List.of(quantityDescLabel, quantityValueLabel, priceDescLabel, priceValueLabel).forEach(l -> l.setFont(FontManager.getRunescapeSmallFont()));

        JPanel quantityPanel = new JPanel(new BorderLayout());
        quantityPanel.setBackground(CustomColors.DARK_GRAY);
        quantityPanel.add(quantityDescLabel, BorderLayout.WEST);
        quantityPanel.add(quantityValueLabel, BorderLayout.EAST);

        JPanel pricePanel = new JPanel(new BorderLayout());
        pricePanel.setBackground(CustomColors.DARK_GRAY);
        pricePanel.add(priceDescLabel, BorderLayout.WEST);
        pricePanel.add(priceValueLabel, BorderLayout.EAST);

        JPanel partialOfferPanel = new JPanel(new DynamicGridLayout(3,1));
        partialOfferPanel.add(itemNameLabel);
        partialOfferPanel.add(quantityPanel);
        partialOfferPanel.add(pricePanel);

        partialOfferPanel.setBackground(CustomColors.DARK_GRAY);

        return partialOfferPanel;
    }

    private JPanel createProfitPanel() {
        long quantity = combinationFlip.getParent().amountConsumed;
        long profit = combinationFlip.getProfit();
        long profitEach = profit/quantity;
        String profitString = UIUtilities.quantityToRSDecimalStack(profit, true) + " gp";
        String profitEachString = quantity == 1? "": " (" + UIUtilities.quantityToRSDecimalStack(profitEach, false) + " gp ea)";
        String profitDescription = profit < 0? "Loss": "Profit:";

        JLabel profitValLabel = new JLabel(profitString + profitEachString);
        profitValLabel.setFont(FontManager.getRunescapeSmallFont());

        JLabel profitDescriptionLabel = new JLabel(profitDescription);
        profitDescriptionLabel.setFont(FontManager.getRunescapeSmallFont());

        profitDescriptionLabel.setForeground(profit >= 0? ColorScheme.GRAND_EXCHANGE_PRICE : CustomColors.OUTDATED_COLOR);
        profitValLabel.setForeground(profit >= 0? ColorScheme.GRAND_EXCHANGE_PRICE : CustomColors.OUTDATED_COLOR);

        JPanel profitPanel = new JPanel(new BorderLayout());
        profitPanel.setBackground(CustomColors.DARK_GRAY);

        profitPanel.add(profitDescriptionLabel, BorderLayout.WEST);
        profitPanel.add(profitValLabel, BorderLayout.EAST);

        return profitPanel;
    }

    public void updateTimeDisplay() {
        OfferEvent offer = combinationFlip.getParent().offer;
        timeDisplay.setText("(" + TimeFormatters.formatDurationTruncated(offer.getTime()) + " ago)");
    }
 }