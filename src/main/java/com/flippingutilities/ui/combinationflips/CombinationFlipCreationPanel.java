package com.flippingutilities.ui.combinationflips;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.CombinationFlip;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.ui.statistics.OfferPanel;
import com.flippingutilities.ui.uiutilities.CustomColors;
import com.flippingutilities.ui.uiutilities.Icons;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.ui.uiutilities.UIUtilities;
import com.flippingutilities.utilities.Recipe;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.QuantityFormatter;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeEvent;
import java.awt.*;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
    Recipe recipe;
    LinkedHashMap<Integer, CombinationItemHeaderPanel> idToHeader;

    public CombinationFlipCreationPanel(FlippingPlugin plugin, FlippingItem item, OfferEvent parentOffer) {
        this.plugin = plugin;
        this.parentOffer = parentOffer;
        this.parentItem = item;

        Map<Integer, Optional<FlippingItem>> childItemsInCombination = plugin.getChildCombinationItems(parentOffer.getItemId(), parentOffer.isBuy());
        recipe = plugin.getApplicableRecipe(parentOffer.getItemId(), parentOffer.isBuy()).get();
        selectedOffers = initSelectedOffers(childItemsInCombination);
        idToHeader = createItemIcons(selectedOffers);

        setBackground(Color.BLACK);
        setLayout(new BorderLayout());

        add(createTitle(), BorderLayout.NORTH);
        add(createBody(childItemsInCombination, plugin.getStatPanel().getStartOfInterval()), BorderLayout.CENTER);
        add(createBottomPanel(childItemsInCombination), BorderLayout.SOUTH);

        setBorder(new EmptyBorder(8, 8, 8, 8));

    }

    private LinkedHashMap<Integer, Map<String, PartialOffer>> initSelectedOffers(Map<Integer, Optional<FlippingItem>> childItemsInCombination) {
        selectedOffers = new LinkedHashMap<>();
        //putting the parent offer in the selected offers map from the beginning as it will always be "selected"
        //by virtue of it being what caused the combination flip
        selectedOffers.put(parentOffer.getItemId(), Map.of(parentOffer.getUuid(), new PartialOffer(parentOffer, parentOffer.getCurrentQuantityInTrade())));
        //initializing the selected offers map with empty hashmaps for the constituent parts of the combination flip
        childItemsInCombination.keySet().forEach(id -> selectedOffers.put(id, new HashMap<>()));
        return selectedOffers;
    }

    /**
     * The body holds the item icons and multiplier labels in the first row and the offer panels with the number
     * pickers in the second row.
     */
    private JPanel createBody(Map<Integer, Optional<FlippingItem>> itemsInCombination, Instant startOfInterval) {
        Set<Integer> allIds = selectedOffers.keySet();

        JPanel bodyPanel = new JPanel();
        bodyPanel.setBackground(Color.BLACK);
        bodyPanel.setLayout(new DynamicGridLayout(2, allIds.size() + 1, 10, 5));

        addHeaderPanelRow(bodyPanel);
        addOfferPanelRow(
                bodyPanel,
                createItemIdToPartialOffers(itemsInCombination, startOfInterval)
        );

        return bodyPanel;
    }

    /**
     * Creates the offer panels with the number picker for all the offers passed in
     */
    private JComponent createOffersPanel(
            List<PartialOffer> partialOffers,
            CombinationItemHeaderPanel headerPanel,
            int targetSelectionValue) {
        JPanel offersPanel = new JPanel();
        offersPanel.setBackground(Color.BLACK);
        if (partialOffers.size() > 0) {
            offersPanel.setLayout(new BoxLayout(offersPanel, BoxLayout.Y_AXIS));

            JPanel wrapper = new JPanel(new BorderLayout());
            wrapper.setBackground(Color.BLACK);
            wrapper.add(offersPanel, BorderLayout.NORTH);

            JScrollPane scrollPane = new JScrollPane(wrapper);
            scrollPane.setBackground(Color.BLACK);
            scrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(2, 0));
            scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

            int maxHackySize = 0;
            for (int i = 0; i < partialOffers.size(); i++) {
                PartialOffer partialOffer = partialOffers.get(i);
                OfferPanel offerPanel = new OfferPanel(plugin, null, partialOffer.getOffer(), true);
                int amountToSelect;
                int actualQuantityInOffer = partialOffer.getOffer().getCurrentQuantityInTrade() - partialOffer.amountConsumed;
                amountToSelect = Math.min(actualQuantityInOffer, targetSelectionValue);
                targetSelectionValue -= amountToSelect;

                int numPickerSize = 40 + (String.valueOf(actualQuantityInOffer).length() * 10);
                maxHackySize = Math.max(maxHackySize, offerPanel.getHackySize() + numPickerSize);

                JPanel offerPanelWithPicker = createOfferPanelWithPicker(
                        offerPanel,
                        headerPanel,
                        partialOffer,
                        amountToSelect
                        );
                offersPanel.add(offerPanelWithPicker);

                if (i < Math.min(partialOffers.size(), 10) - 1) {
                    offersPanel.add(Box.createVerticalStrut(4));
                }

                //Prevents scoll pane from being unnecessarily large if there are few panels
                int scrollPaneHeight = Math.min(350, (partialOffers.size() * 65) + 40);
                scrollPane.setPreferredSize(new Dimension(maxHackySize, scrollPaneHeight));

                setDisplaysAndStateBasedOnSelection(amountToSelect, partialOffer.getOffer(), offerPanel, headerPanel);
            }

            return scrollPane;
        } else {
            String type = parentOffer.isBuy() ? "sell" : "buy";
            JLabel noTradesLabel = new JLabel(String.format("No recorded %s for this item", type));
            noTradesLabel.setForeground(Color.RED);
            offersPanel.add(noTradesLabel);
            headerPanel.setForeground(CustomColors.TOMATO);
            return offersPanel;
        }
    }

    private JPanel createOfferPanelWithPicker(
            OfferPanel offerPanel,
            CombinationItemHeaderPanel headerPanel,
            PartialOffer partialOffer,
            int amountToSelect) {
        JPanel offerPanelWithPicker = new JPanel(new BorderLayout());
        offerPanelWithPicker.setBackground(Color.BLACK);
        JSpinner numberPicker = new JSpinner(
                new SpinnerNumberModel(
                        amountToSelect,
                        0, //min val
                        partialOffer.getOffer().getCurrentQuantityInTrade() - partialOffer.amountConsumed, //max val
                        1));
        numberPicker.setForeground(CustomColors.CHEESE);
        numberPicker.setSize(new Dimension(0, 70));
        numberPicker.addChangeListener(e -> {
            this.numberPickerHandler(e, partialOffer.getOffer(), offerPanel, headerPanel);
        });

        offerPanelWithPicker.add(numberPicker, BorderLayout.WEST);
        offerPanelWithPicker.add(offerPanel, BorderLayout.CENTER);
        if (partialOffer.amountConsumed > 0) {
            boolean completelyConsumed = partialOffer.amountConsumed == partialOffer.getOffer().getCurrentQuantityInTrade();
            JPanel alreadyUsedPanel = new JPanel();
            alreadyUsedPanel.setBackground(Color.BLACK);

            JLabel alreadyUsedLabel = new JLabel(
                    String.format("<html><body width='150' style='text-align:center;'> " +
                                    "%d/%d items in this offer already used in other combo flips</body></html>",
                            partialOffer.amountConsumed,
                            partialOffer.getOffer().getCurrentQuantityInTrade())
                    , SwingConstants.CENTER);
            alreadyUsedLabel.setFont(new Font("Whitney", Font.PLAIN, 10));
            alreadyUsedLabel.setForeground(completelyConsumed? CustomColors.TOMATO.darker():CustomColors.CHEESE.darker());
            alreadyUsedPanel.add(alreadyUsedLabel);

            offerPanelWithPicker.add(alreadyUsedPanel, BorderLayout.SOUTH);
        }
        return offerPanelWithPicker;

    }

    /**
     * Creates all the item icons and text for the first row in the dynamic grid layout
     *
     * @return a mapping of item id to a label which contains the item icon and multiplier text.
     * We use a linked hashmap so we can preserve insertion order when reading, thus ensuring
     * the first element retrieved from the map is always the parent offer.
     */
    private LinkedHashMap<Integer, CombinationItemHeaderPanel> createItemIcons(LinkedHashMap<Integer, Map<String, PartialOffer>> selectedOffers) {
        Set<Integer> allIds = selectedOffers.keySet();
        LinkedHashMap<Integer, CombinationItemHeaderPanel> idToIconLabel = new LinkedHashMap<>();
        allIds.forEach(id -> {
            AsyncBufferedImage itemImage = plugin.getItemManager().getImage(id);
            idToIconLabel.put(id, new CombinationItemHeaderPanel(itemImage));
        });

        return idToIconLabel;
    }

    /**
     * Adds the labels containing the item icon and selected amount amount to the body panel
     *
     * @param bodyPanel the panel which the item labels are being added to
     */
    private void addHeaderPanelRow(JPanel bodyPanel) {
        idToHeader.keySet().forEach(itemId -> {
            JPanel headerPanel = idToHeader.get(itemId);
            bodyPanel.add(headerPanel);
            if (itemId == parentOffer.getItemId()) {
                //adding the arrow in the next column
                ImageIcon arrowIcon = parentOffer.isBuy() ? Icons.RIGHT_ARROW_LARGE : Icons.LEFT_ARROW_LARGE;
                bodyPanel.add(new JLabel(arrowIcon));
            }
        });
    }

    /**
     * @param childItemsInCombination the child items in the combination
     * @return a map of ALL the items in the combination and the partial offers that should be rendered. We don't
     * simply return OfferEvents because we want to know if any of the OfferEvents have some of their quantity
     * already consumed by other combination flips for these items. If that is the case, we want to render them as
     * such.
     */
    private Map<Integer, List<PartialOffer>> createItemIdToPartialOffers(
            Map<Integer, Optional<FlippingItem>> childItemsInCombination,
            Instant startOfInterval
    ) {
        Map<Integer, List<PartialOffer>> itemIdToPartialOffers = new HashMap<>();

        Map<String, PartialOffer> parentItemPartialOffers = parentItem.getOfferIdToPartialOfferInComboFlips();
        if (parentItemPartialOffers.containsKey(parentOffer.getUuid())) {
            PartialOffer parentPartialOffer = parentItemPartialOffers.get(parentOffer.getUuid());
            itemIdToPartialOffers.put(parentOffer.getItemId(), List.of(parentPartialOffer));
        }
        else {
            itemIdToPartialOffers.put(parentOffer.getItemId(), List.of(new PartialOffer(parentOffer, 0)));
        }

        childItemsInCombination.forEach((itemId, item) -> {
            List<PartialOffer> offers = item.map(fitem -> {
                Map<String, PartialOffer> itemPartialOffers = fitem.getOfferIdToPartialOfferInComboFlips();
                List<OfferEvent> history = fitem.getIntervalHistory(startOfInterval);
                Collections.reverse(history);
                //if the parent offer is a sell, it means the user created it from its constituent parts and
                //so we should only look for buys of the constituent parts. Hence, if there are no offers passed in to this
                //method, it means there were no buys.
                return history.stream().filter(o -> o.isBuy() != parentOffer.isBuy() && o.isComplete()).
                        map(o -> {
                            if (itemPartialOffers.containsKey(o.getUuid())) {
                                return itemPartialOffers.get(o.getUuid());
                            }
                            return new PartialOffer(o, 0);
                        }).
                        collect(Collectors.toList());
            }).orElse(new ArrayList<>());
            itemIdToPartialOffers.put(itemId, offers);
        });

        return itemIdToPartialOffers;
    }

    /**
     * Adds the second row to the body panel. The second row contains all the offer panels
     *
     * @param bodyPanel                   the panel the offer panels are being added to
     *                                    a reference to this label in this method so we can change the multiplier text
     * @param itemIdToPartialOffers              a map of all the items in the combination and the offers that should be rendered
     */
    private void addOfferPanelRow(JPanel bodyPanel,
                                  Map<Integer, List<PartialOffer>> itemIdToPartialOffers) {
        Map<Integer, Integer> targetValues = getMaxTargetValues(itemIdToPartialOffers);

        idToHeader.keySet().forEach(id -> {
            int targetValue = targetValues.get(id);
            List<PartialOffer> partialOffers = itemIdToPartialOffers.get(id);
            CombinationItemHeaderPanel combinationItemHeaderPanel = idToHeader.get(id);
            combinationItemHeaderPanel.setTargetValueDisplay(targetValue);
            bodyPanel.add(createOffersPanel(partialOffers, combinationItemHeaderPanel, targetValue));
            if (id == parentOffer.getItemId()) {
                //empty panel occupies the space under the arrow
                JPanel emptyPanel = new JPanel();
                emptyPanel.setBackground(Color.BLACK);
                bodyPanel.add(emptyPanel);
            }
        });

        handleItemsHittingTargetConsumptionValues();
    }

    /**
     * This method computes the initial target values for each of the items when the panel
     * first shows up. This is so that the user doesn't have to manually input them (tho they can still
     * adjust them if they want). The target values are selected such that the max amount of
     * combinations/recipes can be made. Here is an example:
     *
     * Lets say there is a recipe where for every item C you need 3 of item A and 5 of item B. We
     * can shorten it by saying the recipe is 1C, 3A, and 5B. Now, lets say we have a quantity of 5 for item C,
     * quantity of 9 for item A and a quantity of 25 for item B. What is the max amount of this recipe you can make?
     * The max amount is only 3 because it is limited by that fact you only have 9 A items, even though the amount
     * of C and B items you have can support more recipes.
     *
     * Once we know the max amount of recipes the offers can support, we can multiply the max amount by the
     * amount the recipe needs of each item to know how much of each item we will need to make the max amount of
     * recipes. Continuing with the example above, our max recipes is 3. To get the amount of C items, we would do
     * 1 * 3. To get the amount of B items, we would do 5 * 3. To get the amount of A items, we would do 3 * 3.
     *
     * In short:
     * recipe = 3A, 5B, 1C
     * quantities = 9A 25B 5C
     * max # of this recipe supported per item: A=3, B=5, C=5
     * # of recipe supported in actuality: 3, cause A is constraining it.
     * target values: A = 9(3 * 3), B = 15(3 * 5), C = 3(3 * 1)
     *
     * @param itemIdToPartialOffers all the suitable partial offers for each item
     */
    private Map<Integer, Integer> getMaxTargetValues(
            Map<Integer, List<PartialOffer>> itemIdToPartialOffers) {
        Map<Integer, Integer> itemIdToQuantity = recipe.getItemIdToQuantity();

        Map<Integer, Integer> itemIdToMaxRecipesThatCanBeMade = itemIdToPartialOffers.entrySet().stream().map(e -> {
                    int itemId = e.getKey();
                    long totalQuantity = e.getValue().stream().
                            mapToLong(po -> po.getOffer().getCurrentQuantityInTrade() - po.amountConsumed).sum();
                    long quantityNeededForRecipe = itemIdToQuantity.get(itemId);
                    int maxRecipesThatCanBeMade = (int) (totalQuantity / quantityNeededForRecipe);
                    return Map.entry(itemId, maxRecipesThatCanBeMade);
                }).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        int lowestRecipeCountThatCanBeMade = itemIdToMaxRecipesThatCanBeMade.values().stream().mapToInt(i -> i).min().getAsInt();

        return itemIdToQuantity.entrySet().stream().
                map(e -> Map.entry(e.getKey(), e.getValue() * lowestRecipeCountThatCanBeMade)).
                collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Handles the what happens when the number picker's value changes.
     */
    private void numberPickerHandler(ChangeEvent e, OfferEvent offer, OfferPanel offerPanel,
                                     CombinationItemHeaderPanel headerPanel) {
        JSpinner numberPicker = (JSpinner) e.getSource();
        int numberPickerValue = (int) numberPicker.getValue();

        setDisplaysAndStateBasedOnSelection(numberPickerValue, offer, offerPanel, headerPanel);
        handleItemsHittingTargetConsumptionValues();
    }

    /**
     * Sets the consumed amount and the selected offers based on the the number picker value. This is used in the
     * number picker handler method too.
     */
    private void setDisplaysAndStateBasedOnSelection(int numberPickerValue, OfferEvent offer, OfferPanel offerPanel,
                                                     CombinationItemHeaderPanel headerPanel) {
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
        headerPanel.setConsumedAmountDisplay(totalConsumedAmount);
    }

    /**
     * Enables/disables the finish button based on all items have hit their targets along with adjusting the
     * target value display.
     */
    private void handleItemsHittingTargetConsumptionValues() {
        int parentOfferConsumedAmount = selectedOffers.get(parentOffer.getItemId()).get(parentOffer.getUuid()).amountConsumed;
        //if the parent quantity in the recipe is not 1, gonna have to do the modding and stuff
        Map<Integer, Integer> idToTargetValues = recipe.getTargetValues(parentOfferConsumedAmount);

        AtomicBoolean allMatchTargetValues = new AtomicBoolean(true);
        selectedOffers.forEach((itemId, partialOfferMap) -> {
            CombinationItemHeaderPanel itemHeaderPanel = idToHeader.get(itemId);
            int amountConsumed = partialOfferMap.values().stream().mapToInt(o -> o.amountConsumed).sum();
            int targetConsumedAmount = idToTargetValues.get(itemId);

            itemHeaderPanel.setTargetValueDisplay(targetConsumedAmount);

            if (amountConsumed == targetConsumedAmount && targetConsumedAmount != 0) {
                itemHeaderPanel.setConsumedAmountDisplayColor(ColorScheme.GRAND_EXCHANGE_PRICE);
            } else {
                allMatchTargetValues.set(false);
                itemHeaderPanel.setConsumedAmountDisplayColor(CustomColors.CHEESE);
            }
        });

        if (allMatchTargetValues.get()) {
            finishButton.setEnabled(true);
            finishButton.setForeground(Color.GREEN);
            long profit = Math.round(calculateProfit());
            String prefix = profit < 0 ? "" : "+";
            profitNumberLabel.setText(prefix + QuantityFormatter.formatNumber(profit) + " gp");
            profitNumberLabel.setForeground(profit < 0 ? Color.RED : Color.GREEN);
        }
        else {
            finishButton.setEnabled(false);
            finishButton.setForeground(Color.GRAY);
            profitNumberLabel.setText("+0");
            profitNumberLabel.setForeground(CustomColors.CHEESE);
        }
    }

    private JPanel createBottomPanel(Map<Integer, Optional<FlippingItem>> itemsInCombination) {
        JPanel bottomPanel = new JPanel();
        bottomPanel.setBorder(new EmptyBorder(5, 0, 0, 0));
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));
        bottomPanel.setBackground(Color.BLACK);

        profitNumberLabel.setFont(new Font("Whitney", Font.PLAIN, 16));
        profitNumberLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        profitNumberLabel.setBorder(new EmptyBorder(10,0,5,0));

        finishButton.setBorder(new EmptyBorder(10, 10, 10, 10));
        finishButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        finishButton.setFont(new Font("Whitney", Font.PLAIN, 16));
        finishButton.setFocusPainted(false);
        finishButton.addActionListener(e -> {
            CombinationFlip combinationFlip = new CombinationFlip(parentOffer.getItemId(), parentOffer.getUuid(), selectedOffers);
            parentItem.addPersonalCombinationFlip(combinationFlip);
            itemsInCombination.values().forEach(item -> item.get().addParentCombinationFlip(combinationFlip));
            plugin.getStatPanel().rebuild(plugin.viewTradesForCurrentView());
            bottomPanel.removeAll();

            JPanel successPanel = new JPanel(new DynamicGridLayout(2, 1));
            successPanel.setBackground(Color.BLACK);

            JLabel successLabel = new JLabel("Success!", SwingConstants.CENTER);
            successLabel.setBorder(new EmptyBorder(0, 0, 10, 0));
            successLabel.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
            successLabel.setFont(new Font("Whitney", Font.PLAIN, 16));

            JLabel helpTextLabel = new JLabel("" +
                    "<html><body style='text-align: center'>" +
                    "You can find the flip under the \"combos\" tab of the trade history" +
                    "<br>" +
                    "section of <b>ANY</b> of the items seen here, <u><b>" +
                    UIUtilities.colorText("provided you are in a time ", CustomColors.SOFT_ALCH) +
                    "<br>" +
                    UIUtilities.colorText("interval that contains all the offers you selected here", CustomColors.SOFT_ALCH) +
                    "</b><u> <html>", SwingConstants.CENTER);
            helpTextLabel.setFont(new Font("Whitney", Font.PLAIN, 12));

            successPanel.add(successLabel);
            successPanel.add(helpTextLabel);

            bottomPanel.add(successPanel);
            revalidate();
            repaint();
        });

        bottomPanel.add(profitNumberLabel);
        bottomPanel.add(finishButton);

        return bottomPanel;
    }

    private JPanel createTitle() {
        JPanel titlePanel = new JPanel(new BorderLayout());
        titlePanel.setBorder(new EmptyBorder(5, 0, 25, 0));
        titlePanel.setBackground(Color.BLACK);

        String action = parentOffer.isBuy() ? "Breaking" : "Constructing";
        JLabel title = new JLabel(action, JLabel.CENTER);
        ImageIcon itemIcon = new ImageIcon(plugin.getItemManager().getImage(parentOffer.getItemId()));
        title.setBorder(new EmptyBorder(0, 0, 20, 0));
        title.setFont(new Font("Whitney", Font.PLAIN, 20));
        title.setIcon(itemIcon);
        title.setHorizontalTextPosition(SwingConstants.LEFT);
        title.setIconTextGap(8);

        JPanel intervalPanel = new JPanel();
        intervalPanel.setBackground(Color.BLACK);

        String intervalName = plugin.getStatPanel().getStartOfIntervalName();

        JLabel intervalDescLabel = new JLabel("You are looking at offers from the interval: ");
        intervalDescLabel.setFont(new Font("Whitney", Font.PLAIN, 14));

        JLabel intervalNameLabel = new JLabel(intervalName);
        intervalNameLabel.setFont(new Font("Whitney", Font.PLAIN, 16));
        intervalNameLabel.setForeground(CustomColors.CHEESE);
        UIUtilities.makeLabelUnderlined(intervalNameLabel);

        intervalPanel.add(intervalDescLabel);
        intervalPanel.add(intervalNameLabel);

        JLabel desc = new JLabel("Only completed offers show up here", SwingConstants.CENTER);
        desc.setForeground(CustomColors.CHEESE);
        desc.setFont(new Font("Whitney", Font.PLAIN, 12));

        titlePanel.add(title, BorderLayout.NORTH);
        titlePanel.add(intervalPanel, BorderLayout.CENTER);
        titlePanel.add(desc, BorderLayout.SOUTH);

        return titlePanel;
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
