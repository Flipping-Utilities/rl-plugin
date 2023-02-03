package com.flippingutilities.ui.statistics.items;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.ui.MasterPanel;
import com.flippingutilities.ui.recipeflips.RecipeFlipCreationPanel;
import com.flippingutilities.ui.uiutilities.CustomColors;
import com.flippingutilities.ui.uiutilities.Icons;
import com.flippingutilities.ui.uiutilities.TimeFormatters;
import com.flippingutilities.ui.uiutilities.UIUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.QuantityFormatter;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * The offer panel displays an offer's price, quantity, time bought, total cost,
 * and gives the option to delete it.
 */
public class OfferPanel extends JPanel {
    private final JLabel timeDisplay;
    private final OfferEvent offer;
    private final FlippingPlugin plugin;
    private final FlippingItem item;
    //flag used to decide whether the icons such as the delete icon and recipe flip icon should be shown.
    //when these panels are being displayed in the recipeFlipPanel, we don't want to show those icons.
    private boolean plainMode;
    private JLabel offerDescriptionLabel;

    public OfferPanel(FlippingPlugin plugin, FlippingItem item, OfferEvent offer, boolean plainMode) {
        setLayout(new BorderLayout());
        this.offer = offer;
        this.plugin = plugin;
        this.item = item;
        this.plainMode = plainMode;
        this.timeDisplay = createTimeDisplayLabel();
        this.offerDescriptionLabel = createOfferDescriptionLabel();
        add(createTitlePanel(offerDescriptionLabel, timeDisplay), BorderLayout.NORTH);
        add(createBodyPanel(), BorderLayout.CENTER);

        setBackground(CustomColors.DARK_GRAY);
        setBorder(createBorder(false));
    }

    /**
     * Is called by a background task to continuously update the time display
     */
    public void updateTimeDisplay() {
        if (offer.isComplete()) {
            timeDisplay.setText("(" + TimeFormatters.formatDurationTruncated(offer.getTime()) + " ago)");
        }
        else {
            timeDisplay.setText(TimeFormatters.formatDurationTruncated(offer.getTime()) + " ago");
        }
    }

    private JPanel createTitlePanel(JLabel offerDescription, JLabel timeDisplay) {
        JPanel titlePanel = new JPanel();
        titlePanel.setBorder(new EmptyBorder(0,0,2,0));
        titlePanel.setBackground(CustomColors.DARK_GRAY);
        if (!offer.isComplete()) {
            //idk why but when i set the dynamic grid layout the offer description label has
            //no distance from the top, but this doesn't happen when the title panel is the default layout (flowlayout)
            titlePanel.setBorder(new EmptyBorder(5,0,2,0));
            titlePanel.setLayout(new DynamicGridLayout(2, 1));
        }

        titlePanel.add(offerDescription);
        titlePanel.add(timeDisplay);
        return titlePanel;
    }

    private JLabel createTimeDisplayLabel() {
        JLabel timeDisplay = new JLabel("", SwingConstants.CENTER);
        timeDisplay.setBackground(CustomColors.DARK_GRAY);
        timeDisplay.setOpaque(true);
        timeDisplay.setFont(FontManager.getRunescapeSmallFont());
        timeDisplay.setForeground(offer.isBuy() ? CustomColors.OUTDATED_COLOR : ColorScheme.GRAND_EXCHANGE_PRICE);
        if (!offer.isComplete()) {
            timeDisplay.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);
        }

        if (offer.isComplete()) {
            timeDisplay.setText("(" + TimeFormatters.formatDurationTruncated(offer.getTime()) + " ago)");
        }
        else {
            timeDisplay.setText(TimeFormatters.formatDurationTruncated(offer.getTime()) + " ago");
        }

        return timeDisplay;
    }
    private JLabel createOfferDescriptionLabel() {
        JLabel offerDescriptionLabel = new JLabel("", SwingConstants.CENTER);
        offerDescriptionLabel.setBackground(CustomColors.DARK_GRAY);
        offerDescriptionLabel.setOpaque(true);
        offerDescriptionLabel.setFont(FontManager.getRunescapeSmallFont());

        if (!offer.isComplete()) {
            offerDescriptionLabel.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);
            offerDescriptionLabel.setText(
                    QuantityFormatter.formatNumber(offer.getCurrentQuantityInTrade()) + " " + getOfferDescription() + " (ongoing)");
        }
        else {
            offerDescriptionLabel.setForeground(offer.isBuy() ? CustomColors.OUTDATED_COLOR : ColorScheme.GRAND_EXCHANGE_PRICE);
            offerDescriptionLabel.setText(
                    QuantityFormatter.formatNumber(offer.getCurrentQuantityInTrade()) + " " + getOfferDescription()
            );
        }
        return offerDescriptionLabel;
    }

    /**
     * The body panel contains everything but the title, currently this means
     * it holds the prices and the icons
     */
    private JPanel createBodyPanel() {
        JPanel body = new JPanel(new DynamicGridLayout(3, 0, 0, 2));
        body.setBackground(CustomColors.DARK_GRAY);
        body.setBorder(new EmptyBorder(0, 2, 1, 2));

        JLabel priceLabel = new JLabel("Price:");
        JLabel priceVal = new JLabel(QuantityFormatter.formatNumber(offer.getPrice()) + " gp", SwingConstants.RIGHT);

        JLabel totalPriceLabel = new JLabel("Total:");
        JLabel totalPriceVal = new JLabel(QuantityFormatter.formatNumber(offer.getPrice() * offer.getCurrentQuantityInTrade()) + " gp", SwingConstants.RIGHT);

        JLabel[] descriptions = {priceLabel, totalPriceLabel};
        JLabel[] vals = {priceVal, totalPriceVal};

        for (int i = 0; i < descriptions.length; i++) {
            JLabel descriptionLabel = descriptions[i];
            JLabel valLabel = vals[i];

            descriptionLabel.setFont(FontManager.getRunescapeSmallFont());
            descriptionLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            valLabel.setFont(FontManager.getRunescapeSmallFont());
            valLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

            JPanel infoPanel = new JPanel(new BorderLayout());
            infoPanel.setBackground(CustomColors.DARK_GRAY);
            infoPanel.add(descriptionLabel, BorderLayout.WEST);
            infoPanel.add(valLabel, BorderLayout.EAST);
            body.add(infoPanel);
        }

        if (!plainMode) {
            body.add(createIconPanel());
        }

        return body;
    }

    /**
     * Creates the panel which holds the delete icon and recipe flip
     * icon (if needed)
     */
    private JPanel createIconPanel() {
        JPanel iconPanel = new JPanel(new BorderLayout());
        iconPanel.setBackground(CustomColors.DARK_GRAY);
        boolean hasRecipe = !plugin.getApplicableRecipes(offer.getItemId(), offer.isBuy()).isEmpty();
        if (hasRecipe && offer.isComplete()) {
            JLabel deleteIcon = createDeleteIcon();
            deleteIcon.setBorder(new EmptyBorder(0,5,0,0));
            iconPanel.add(createRecipeFlipIcon(), BorderLayout.EAST);
            iconPanel.add(deleteIcon, BorderLayout.WEST);
        }
        else {
            iconPanel.add(createDeleteIcon(), BorderLayout.CENTER);
        }

        return iconPanel;
    }

    private JComponent createRecipeFlipIcon() {
        PartialOffer po = plugin.getOfferIdToPartialOffer(item.getItemId()).get(offer.getUuid());
        if (po != null && po.amountConsumed == offer.getCurrentQuantityInTrade()) {
            JLabel recipeFlipLabel = new JLabel("Fully consumed");
            recipeFlipLabel.setFont(new Font("Whitney", Font.PLAIN, 10));
            recipeFlipLabel.setForeground(CustomColors.TOMATO);
            return recipeFlipLabel;
        }
        JButton recipeFlipButton = new JButton("Recipe Flip +");
        recipeFlipButton.setToolTipText("<html>A recipe flip is when you combine several items into one to sell it, <br>" +
                "or break apart an item into parts to sell them. <br>If that's what you did with this offer, click me!</html>");
        recipeFlipButton.setFocusPainted(false);
        recipeFlipButton.setFont(new Font("Whitney", Font.PLAIN, 10));
        recipeFlipButton.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
        recipeFlipButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (plugin.getAccountCurrentlyViewed().equals(FlippingPlugin.ACCOUNT_WIDE)) {
                    JOptionPane.showMessageDialog(null, "You cannot create recipe flips in the Accountwide view");
                    return;
                }
                MasterPanel m = plugin.getMasterPanel();
                RecipeFlipCreationPanel recipeFlipCreationPanel = new RecipeFlipCreationPanel(plugin, offer);
                JDialog recipeFlipCreationModal = UIUtilities.createModalFromPanel(m, recipeFlipCreationPanel);
                recipeFlipCreationPanel.setModal(recipeFlipCreationModal);
                recipeFlipCreationModal.pack();
                recipeFlipCreationModal.setLocation(
                    Math.max(20, m.getLocationOnScreen().x - recipeFlipCreationModal.getWidth() - 10),
                    Math.max(m.getLocationOnScreen().y - recipeFlipCreationModal.getHeight()/2, 0) + 100);
                recipeFlipCreationModal.setVisible(true);
            }
        });
        return recipeFlipButton;
    }

    /**
     * Creates the panel which holds the delete icon to delete that specific offer
     */
    private JLabel createDeleteIcon() {
        JLabel deleteIcon = new JLabel(Icons.TRASH_CAN_OFF);
        deleteIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (plugin.getAccountCurrentlyViewed().equals(FlippingPlugin.ACCOUNT_WIDE)) {
                    JOptionPane.showMessageDialog(null, "You cannot delete offers in the Accountwide view");
                    return;
                }
                //Display warning message
                final int result = JOptionPane.showOptionDialog(deleteIcon, "Are you sure you want to delete this offer?",
                        "Are you sure?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
                        null, new String[]{"Yes", "No"}, "No");

                //If the user pressed "Yes"
                if (result == JOptionPane.YES_OPTION) {
                    plugin.deleteOffers(new ArrayList<>(Arrays.asList(offer)), item);
                    plugin.getStatPanel().rebuildItemsDisplay(plugin.viewItemsForCurrentView());
                    plugin.getStatPanel().rebuildRecipesDisplay(plugin.viewRecipeFlipGroupsForCurrentView());

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

    private String getOfferDescription() {
        if (offer.isBuy() && offer.isMarginCheck()) {
            return "Insta Bought";
        } else if (offer.isBuy() && !offer.isMarginCheck()) {
            return "Bought";
        } else if (!offer.isBuy() && offer.isMarginCheck()) {
            return "Insta Sold";
        } else if (!offer.isBuy() && !offer.isMarginCheck()) {
            return "Sold";
        } else {
            return "";
        }
    }

    /**
     * Used in the RecipeFlipPanel to show that this offer panel is selected or not
     */
    public void setSelected(boolean selected) {
        setBorder(createBorder(selected));
    }

    private Border createBorder(boolean selected) {
        Color outerBorderColor = selected? ColorScheme.GRAND_EXCHANGE_PRICE.darker():ColorScheme.DARKER_GRAY_COLOR.darker();
        return new CompoundBorder(
                BorderFactory.createMatteBorder(1,1,1,1, outerBorderColor),
                new EmptyBorder(1,3,3,3));
    }

    /**
     * I really hate java swing, having to approximate the damn width of this using this really stupid code. For
     * some reason the layout manager is not respecting sizes and i have to set it manually on the scrollpane.
     * I'm probably doing something wrong, but don't feel like going any deeper into java swing....
     */
    public int getHackySize() {
        int timeDisplayWidth = (timeDisplay.getText().length() * 8) + 5;
        int gapBetweenDescAndTimeDisplays = 10;
        int descriptionDisplayWidth = (offerDescriptionLabel.getText().length() * 6) + 5;
        return timeDisplayWidth + gapBetweenDescAndTimeDisplays + descriptionDisplayWidth;
    }
}
