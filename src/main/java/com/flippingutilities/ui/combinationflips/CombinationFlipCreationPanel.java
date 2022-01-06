package com.flippingutilities.ui.combinationflips;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.CombinationFlip;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.ui.statistics.OfferPanel;
import com.flippingutilities.ui.uiutilities.CustomColors;
import com.flippingutilities.ui.uiutilities.Icons;
import com.flippingutilities.model.PartialOffer;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.QuantityFormatter;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeEvent;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The panel the user interacts with when creating a combination flip
 */
public class CombinationFlipCreationPanel extends JPanel {
    FlippingPlugin plugin;
    //the offer the user clicked on to bring up this panel. This will be the "parent" offer such
    //as the set that was sold after buying the individual pieces and constructing the set and selling it.
    OfferEvent parentOffer;
    FlippingItem parentItem;
    //used to track which offers a user has selected to be in the combination flip and how much of the
    //offer is contributed.
    //This is a map of item id to a map of offer id to selected offer.
    LinkedHashMap<Integer, Map<String, PartialOffer>> selectedOffers;
    JButton finishButton = new JButton("Combine!");
    JLabel profitNumberLabel = new JLabel("+0");
    ;

    public CombinationFlipCreationPanel(FlippingPlugin plugin, FlippingItem item, OfferEvent parentOffer) {
        this.plugin = plugin;
        this.parentOffer = parentOffer;
        this.parentItem = item;

        selectedOffers = new LinkedHashMap<>();

        //putting the parent offer in the selected offers map from the beginning as it will always be "selected"
        //by virtue of it being what caused the combination flip
        Map<String, PartialOffer> parentOfferMap = new HashMap<>();
        parentOfferMap.put(parentOffer.getUuid(), new PartialOffer(parentOffer, parentOffer.getCurrentQuantityInTrade()));

        selectedOffers.put(parentOffer.getItemId(), parentOfferMap);

        //initializing the selected offers map with empty hashmaps for the constituent parts of the combination flip
        Map<Integer, Optional<FlippingItem>> childItemsInCombination = plugin.getItemsInCombination(parentOffer.getItemId());
        childItemsInCombination.keySet().forEach(id -> selectedOffers.put(id, new HashMap<>()));

        setBackground(Color.BLACK);
        setLayout(new BorderLayout());

        add(createTitle(), BorderLayout.NORTH);
        add(createBody(childItemsInCombination), BorderLayout.CENTER);
        add(createBottomPanel(childItemsInCombination), BorderLayout.SOUTH);

        setBorder(new EmptyBorder(8, 8, 8, 8));
    }

    private JPanel createBody(Map<Integer, Optional<FlippingItem>> itemsInCombination) {
        Set<Integer> allIds = selectedOffers.keySet();

        JPanel bodyPanel = new JPanel();
        bodyPanel.setBackground(Color.BLACK);
        bodyPanel.setLayout(new DynamicGridLayout(2, allIds.size() + 1, 10, 5));

        LinkedHashMap<Integer, JLabel> idToIconLabel = createItemIcons();

        addItemLabelRow(bodyPanel, idToIconLabel);
        addOfferPanelRow(bodyPanel, idToIconLabel, createItemIdToOffers(itemsInCombination));

        return bodyPanel;
    }

    /**
     * Creates the offer panels with the number picker for all the offers passed in
     */
    private JComponent createOffersPanel(List<OfferEvent> offers, JLabel iconLabel, int targetSelectionValue) {
        JPanel offersPanel = new JPanel();
        offersPanel.setBackground(Color.BLACK);
        if (offers.size() > 0) {
            offersPanel.setLayout(new BoxLayout(offersPanel, BoxLayout.Y_AXIS));

            JPanel wrapper = new JPanel(new BorderLayout());
            wrapper.setBackground(Color.BLACK);
            wrapper.add(offersPanel, BorderLayout.NORTH);

            JScrollPane scrollPane = new JScrollPane(wrapper);
            scrollPane.setBackground(Color.BLACK);
            scrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(2, 0));
            scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            //Prevents scoll pane from being too large if there are few panels
            int scrollPaneHeight = Math.min(350, offers.size() * 65);
            scrollPane.setPreferredSize(new Dimension(230, scrollPaneHeight));

            for (int i = 0; i < Math.min(offers.size(), 10); i++) {
                OfferEvent offer = offers.get(i);
                OfferPanel offerPanel = new OfferPanel(plugin, null, offer, true);
                JPanel offerPanelWithPicker = new JPanel(new BorderLayout());
                offerPanelWithPicker.setBackground(Color.BLACK);
                boolean isParentOffer = offer.equals(parentOffer);
                int amountToSelect;
                if (offer.getCurrentQuantityInTrade() <= targetSelectionValue) {
                    amountToSelect = offer.getCurrentQuantityInTrade();
                    targetSelectionValue -= amountToSelect;
                } else {
                    amountToSelect = targetSelectionValue;
                    targetSelectionValue = 0;
                }

                JSpinner numberPicker = new JSpinner(
                        new SpinnerNumberModel(
                                amountToSelect,
                                isParentOffer ? 1 : 0, //min val
                                offer.getCurrentQuantityInTrade(), //max val
                                1));
                numberPicker.setForeground(CustomColors.CHEESE);
                numberPicker.setSize(new Dimension(0, 70));
                numberPicker.addChangeListener(e -> {
                    this.numberPickerHandler(e, offer, offerPanel, iconLabel);
                });

                offerPanelWithPicker.add(numberPicker, BorderLayout.WEST);
                offerPanelWithPicker.add(offerPanel, BorderLayout.CENTER);
                offersPanel.add(offerPanelWithPicker);
                if (i < Math.min(offers.size(), 10) - 1) {
                    offersPanel.add(Box.createVerticalStrut(4));
                }

                setDisplaysAndStateBasedOnSelection(amountToSelect, offer, offerPanel, iconLabel);
            }

            return scrollPane;
        } else {
            String type = parentOffer.isBuy() ? "sell" : "buy";
            JLabel noTradesLabel = new JLabel(String.format("No recorded %s for this item", type));
            noTradesLabel.setForeground(Color.RED);
            offersPanel.add(noTradesLabel);
            return offersPanel;
        }
    }

    /**
     * Creates all the item icons and text for the first row in the dynamic grid layout
     *
     * @return a mapping of item id to a label which contains the item icon and multiplier text.
     * We use a linked hashmap so we can preserve insertion order when reading, thus ensuring
     * the first element retrieved from the map is always the parent offer.
     */
    private LinkedHashMap<Integer, JLabel> createItemIcons() {
        Set<Integer> allIds = selectedOffers.keySet();
        LinkedHashMap<Integer, JLabel> idToIconLabel = new LinkedHashMap<>();
        allIds.forEach(id -> {
            AsyncBufferedImage itemImage = plugin.getItemManager().getImage(id);
            Icon itemIcon = new ImageIcon(itemImage);
            JLabel iconLabel = new JLabel(itemIcon);
            iconLabel.setHorizontalTextPosition(SwingConstants.RIGHT);
            iconLabel.setFont(new Font("Whitney", Font.PLAIN, 20));
            iconLabel.setText("x0");
            idToIconLabel.put(id, iconLabel);
            if (id == parentOffer.getItemId()) {
                iconLabel.setText(String.format("x%d", parentOffer.getCurrentQuantityInTrade()));
                iconLabel.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
            }
        });

        return idToIconLabel;
    }

    /**
     * Adds the labels containing the item icon and multiplier text to the body panel
     *
     * @param bodyPanel the panel which the item labels are being added to
     * @param idToLabel a mapping of item id to label containing the item icon and multiplier text
     */
    private void addItemLabelRow(JPanel bodyPanel, LinkedHashMap<Integer, JLabel> idToLabel) {
        idToLabel.keySet().forEach(itemId -> {
            JLabel itemLabel = idToLabel.get(itemId);
            bodyPanel.add(itemLabel);
            if (itemId == parentOffer.getItemId()) {
                //adding the arrow in the next column
                ImageIcon arrowIcon = parentOffer.isBuy() ? Icons.RIGHT_ARROW_LARGE : Icons.LEFT_ARROW_LARGE;
                bodyPanel.add(new JLabel(arrowIcon));
            }
        });
    }

    /**
     * @param childItemsInCombination the child items in the combination
     * @return a map of all the items in the combination and the offers that should be rendered
     */
    private Map<Integer, List<OfferEvent>> createItemIdToOffers(Map<Integer, Optional<FlippingItem>> childItemsInCombination) {
        Map<Integer, List<OfferEvent>> itemIdToOffers = new HashMap<>();
        itemIdToOffers.put(parentOffer.getItemId(), Collections.singletonList(parentOffer));

        childItemsInCombination.entrySet().forEach(entry -> {
            Optional<FlippingItem> item = entry.getValue();
            List<OfferEvent> offers = item.map(fitem -> {
                List<OfferEvent> history = new ArrayList<>(fitem.getHistory().getCompressedOfferEvents());
                Collections.reverse(history);
                //if the parent offer is a sell, it means the user created it from its constituent parts and
                //so we should only look for buys of the constituent parts. Hence, if there are no offers passed in to this
                //method, it means there were no buys.
                return history.stream().filter(o -> o.isBuy() != parentOffer.isBuy()).collect(Collectors.toList());
            }).orElse(new ArrayList<>());
            itemIdToOffers.put(entry.getKey(), offers);
        });

        return itemIdToOffers;
    }

    /**
     * Adds the second row to the body panel. The second row contains all the offer panels
     *
     * @param bodyPanel          the panel the offer panels are being added to
     * @param idToLabel          a mapping of item id to label which contains the icon and the multiplier text. We need
     *                           a reference to this label in this method so we can change the multiplier text
     * @param itemIdToOffers     a map of all the items in the combination and the offers that should be rendered
     */
    private void addOfferPanelRow(JPanel bodyPanel,
                                  LinkedHashMap<Integer, JLabel> idToLabel,
                                  Map<Integer, List<OfferEvent>> itemIdToOffers) {
        int leastItemCount = itemIdToOffers.values().stream().
                mapToInt(offerEvents -> offerEvents.stream().
                                mapToInt(offerEvent -> offerEvent.getCurrentQuantityInTrade()).sum()).
                min().orElse(0);

        idToLabel.keySet().forEach(id -> {
            List<OfferEvent> offers = itemIdToOffers.get(id);
            if (id == parentOffer.getItemId()) {
                bodyPanel.add(createOffersPanel(offers, idToLabel.get(id), leastItemCount));
                JPanel emptyPanel = new JPanel();
                emptyPanel.setBackground(Color.BLACK);
                bodyPanel.add(emptyPanel);
            } else {
                bodyPanel.add(createOffersPanel(offers, idToLabel.get(id), leastItemCount));
            }
        });

        setDisplaysOnCompletion();
    }

    /**
     * Handles the what happens when the number picker's value changes.
     */
    private void numberPickerHandler(ChangeEvent e, OfferEvent offer, OfferPanel offerPanel, JLabel iconLabel) {
        JSpinner numberPicker = (JSpinner) e.getSource();
        int numberPickerValue = (int) numberPicker.getValue();

        setDisplaysAndStateBasedOnSelection(numberPickerValue, offer, offerPanel, iconLabel);
        setDisplaysOnCompletion();
    }

    private void setDisplaysAndStateBasedOnSelection(int numberPickerValue, OfferEvent offer, OfferPanel offerPanel, JLabel iconLabel) {
        int itemId = offer.getItemId();

        offerPanel.setSelected(numberPickerValue > 0);
        Map<String, PartialOffer> selectedOffersForThisItem = selectedOffers.get(itemId);

        //if the user has already selected this offer, just make the amount consumed for that offer what they
        //just selected
        if (selectedOffersForThisItem.containsKey(offer.getUuid())) {
            selectedOffersForThisItem.get(offer.getUuid()).amountConsumed = numberPickerValue;
        } else {
            selectedOffersForThisItem.put(offer.getUuid(), new PartialOffer(offer, numberPickerValue));
        }

        int totalConsumedAmount = selectedOffersForThisItem.values().stream().mapToInt(o -> o.amountConsumed).sum();
        iconLabel.setText("x" + totalConsumedAmount);

        //if amount consumed for this item is the same as the parent offer, make the multiplier text green
        int parentOfferConsumedAmount = selectedOffers.get(parentOffer.getItemId()).get(parentOffer.getUuid()).amountConsumed;
        if (totalConsumedAmount == parentOfferConsumedAmount) {
            iconLabel.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
        } else {
            iconLabel.setForeground(CustomColors.CHEESE);
        }
    }

    private void setDisplaysOnCompletion() {
        //if all items have the same amount consumed as the parent offer, enable the finish button
        int parentOfferConsumedAmount = selectedOffers.get(parentOffer.getItemId()).get(parentOffer.getUuid()).amountConsumed;
        long itemsWithoutCorrectSelectedAmount = selectedOffers.values().stream().
                map(m -> m.values().stream().mapToInt(o -> o.amountConsumed).sum()).
                filter(sum -> sum != parentOfferConsumedAmount).count();
        if (itemsWithoutCorrectSelectedAmount == 0) {
            finishButton.setEnabled(true);
            finishButton.setForeground(Color.GREEN);
            long profit = Math.round(calculateProfit());
            String prefix = profit < 0 ? "" : "+";
            profitNumberLabel.setText(prefix + QuantityFormatter.formatNumber(profit) + " gp");
            profitNumberLabel.setForeground(profit < 0 ? Color.RED : Color.GREEN);
        } else {
            finishButton.setEnabled(false);
            finishButton.setForeground(Color.GRAY);
            profitNumberLabel.setText("+0");
            profitNumberLabel.setForeground(CustomColors.CHEESE);
        }
    }

    private JPanel createBottomPanel(Map<Integer, Optional<FlippingItem>> itemsInCombination) {
        JPanel bottomPanel = new JPanel();
        bottomPanel.setBorder(new EmptyBorder(5, 0, 0, 0));
        bottomPanel.setBackground(Color.BLACK);

        profitNumberLabel.setFont(new Font("Whitney", Font.PLAIN, 16));

        finishButton.setBorder(new EmptyBorder(10, 10, 10, 10));
        finishButton.setFont(new Font("Whitney", Font.PLAIN, 16));
        finishButton.setFocusPainted(false);
        finishButton.addActionListener(e -> {
            CombinationFlip combinationFlip = new CombinationFlip(parentOffer.getItemId(), parentOffer.getUuid(), selectedOffers);
            parentItem.addCombinationFlip(combinationFlip);
            itemsInCombination.values().forEach(item -> item.get().addCombinationFlipThatDependsOnThisItem(combinationFlip));
        });

        bottomPanel.add(finishButton);
        bottomPanel.add(profitNumberLabel);

        return bottomPanel;
    }

    private JLabel createTitle() {
        String action = parentOffer.isBuy() ? "Breaking" : "Constructing";
        JLabel title = new JLabel(action, JLabel.CENTER);
        ImageIcon itemIcon = new ImageIcon(plugin.getItemManager().getImage(parentOffer.getItemId()));
        title.setBorder(new EmptyBorder(0, 0, 20, 0));
        title.setFont(new Font("Whitney", Font.PLAIN, 20));
        title.setIcon(itemIcon);
        title.setHorizontalTextPosition(SwingConstants.LEFT);
        title.setIconTextGap(8);
        return title;
    }

    private long calculateProfit() {
        PartialOffer parentPartialOffer = selectedOffers.get(parentOffer.getItemId()).get(parentOffer.getUuid());
        Map<Integer, Map<String, PartialOffer>> children = selectedOffers.entrySet().stream().
                filter(e -> e.getKey() != parentOffer.getItemId()).
                collect(
                        Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return CombinationFlip.calculateProfit(parentPartialOffer, children);
    }
}
