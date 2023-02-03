package com.flippingutilities.ui.recipeflips;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.ui.statistics.items.OfferPanel;
import com.flippingutilities.ui.uiutilities.CustomColors;
import com.flippingutilities.ui.uiutilities.Icons;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.ui.uiutilities.UIUtilities;
import com.flippingutilities.utilities.Recipe;
import lombok.extern.slf4j.Slf4j;
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
 * The panel the user interacts with when creating a recipe flip
 */
@Slf4j
public class RecipeOfferSelectionPanel extends JPanel {
    FlippingPlugin plugin;
    //the offer the user clicked on to bring up this panel.
    OfferEvent sourceOffer;
    //used to track which offers a user has selected to be in the recipe flip and how much of the
    //offer is contributed.
    //This is a map of item id to a map of offer id to selected offer.
    Map<Integer, Map<String, PartialOffer>> selectedOffers;
    JButton finishButton = new JButton("Combine!");
    JLabel profitNumberLabel = new JLabel("+0");
    Recipe recipe;
    Map<Integer, RecipeItemHeaderPanel> idToHeader;
    List<JSpinner> numberPickers = new ArrayList<>();

    public RecipeOfferSelectionPanel(FlippingPlugin plugin, OfferEvent sourceOffer, Recipe recipe) {
        this.plugin = plugin;
        this.sourceOffer = sourceOffer;
        this.recipe = recipe;

        Map<Integer, Optional<FlippingItem>> itemsInRecipe = plugin.getItemsInRecipe(recipe);

        selectedOffers = initSelectedOffers(itemsInRecipe);
        idToHeader = createRecipeItemHeaderPanel(selectedOffers);

        setBackground(Color.BLACK);
        setLayout(new BorderLayout());

        //All the partial offers for every item in the recipe in this time range
        Map<Integer, List<PartialOffer>> itemIdToPartialOffers = createItemIdToPartialOffers(
                itemsInRecipe,
                plugin.getStatPanel().getStartOfInterval());
        add(createTitle(), BorderLayout.NORTH);
        add(createBody(itemIdToPartialOffers), BorderLayout.CENTER);
        add(createBottomPanel(itemIdToPartialOffers), BorderLayout.SOUTH);

        setBorder(new EmptyBorder(8, 8, 8, 8));

    }

    private Map<Integer, Map<String, PartialOffer>> initSelectedOffers(Map<Integer, Optional<FlippingItem>> itemsInRecipe) {
        selectedOffers = new HashMap<>();
        itemsInRecipe.keySet().forEach(id -> {
            selectedOffers.put(id, new HashMap<>());
        });
        return selectedOffers;
    }

    /**
     * The body holds the item icons and multiplier labels in the first row and the offer panels with the number
     * pickers in the second row.
     */
    private JPanel createBody(
            Map<Integer, List<PartialOffer>> itemIdToPartialOffers
    ) {
        Set<Integer> allIds = selectedOffers.keySet();

        JPanel bodyPanel = new JPanel();
        bodyPanel.setBackground(Color.BLACK);
        bodyPanel.setLayout(new DynamicGridLayout(2, allIds.size() + 1, 10, 5));

        addHeaderPanelRow(bodyPanel);
        addOfferPanelRow(
                bodyPanel,
                itemIdToPartialOffers
        );

        return bodyPanel;
    }

    /**
     * Creates the offer panels with the number picker for all the offers passed in
     */
    private JComponent createOffersPanel(
            int itemId,
            List<PartialOffer> partialOffers,
            RecipeItemHeaderPanel headerPanel,
            int targetSelectionValue) {
        JPanel offersPanel = new JPanel();
        offersPanel.setBackground(Color.BLACK);

        if (partialOffers.size() > 0) {
            return createOffersScrollPane(partialOffers, headerPanel, targetSelectionValue, offersPanel);
        }

        if (itemId == 995) {
            JLabel coinsLabel = new JLabel("coins automatically accounted for");
            coinsLabel.setForeground(Color.GREEN);
            offersPanel.add(coinsLabel);
        }
        else {
            String type = sourceOffer.isBuy() ? "sell" : "buy";
            JLabel noTradesLabel = new JLabel(String.format("No recorded %s for this item", type));
            noTradesLabel.setForeground(Color.RED);
            offersPanel.add(noTradesLabel);
        }

        headerPanel.setForeground(CustomColors.TOMATO);
        return offersPanel;
    }

    /**
     * Creates the scroll pane which contains the offer panels with the number pickers.
     * @param partialOffers the item's offers
     * @param headerPanel the panel containing the item icon, consumed amount, and target value.
     * @param targetSelectionValue the initial amount to select
     * @param offersPanel the panel the offer panels are placed on
     */
    private JScrollPane createOffersScrollPane(
        List<PartialOffer> partialOffers,
        RecipeItemHeaderPanel headerPanel,
        int targetSelectionValue,
        JPanel offersPanel
    ) {
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
    }

    /**
     * Creates the offer panel with the number selector used to select how much of
     * an offer you want to consume
     */
    private JPanel createOfferPanelWithPicker(
            OfferPanel offerPanel,
            RecipeItemHeaderPanel headerPanel,
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
        numberPickers.add(numberPicker);

        offerPanelWithPicker.add(numberPicker, BorderLayout.WEST);
        offerPanelWithPicker.add(offerPanel, BorderLayout.CENTER);
        if (partialOffer.amountConsumed > 0) {
            boolean completelyConsumed = partialOffer.amountConsumed == partialOffer.getOffer().getCurrentQuantityInTrade();
            JPanel alreadyUsedPanel = new JPanel();
            alreadyUsedPanel.setBackground(Color.BLACK);

            JLabel alreadyUsedLabel = new JLabel(
                    String.format("<html><body width='150' style='text-align:center;'> " +
                                    "%d/%d items in this offer already used in other recipe flips</body></html>",
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
     * @return a mapping of item id to a label which contains the item icon and consumed amount text, and
     * target consumption text.
     */
    private Map<Integer, RecipeItemHeaderPanel> createRecipeItemHeaderPanel(Map<Integer, Map<String, PartialOffer>> selectedOffers) {
        Set<Integer> allIds = selectedOffers.keySet();
        Map<Integer, RecipeItemHeaderPanel> idToIconLabel = new HashMap<>();
        allIds.forEach(id -> {
            AsyncBufferedImage itemImage = plugin.getItemManager().getImage(id);
            idToIconLabel.put(id, new RecipeItemHeaderPanel(itemImage));
        });

        return idToIconLabel;
    }

    /**
     * Adds the labels containing the item icon and selected amount amount to the body panel
     *
     * @param bodyPanel the panel which the item labels are being added to
     */
    private void addHeaderPanelRow(JPanel bodyPanel) {
        recipe.getInputIds().forEach(itemId -> {
            JPanel headerPanel = idToHeader.get(itemId);
            bodyPanel.add(headerPanel);
        });

        bodyPanel.add(new JLabel(Icons.RIGHT_ARROW_LARGE));

        recipe.getOutputIds().forEach(itemId -> {
            JPanel headerPanel = idToHeader.get(itemId);
            bodyPanel.add(headerPanel);
        });
    }

    /**
     * @param itemIdToItem all the items in the recipe
     * @return a map of ALL the items in the recipe and the partial offers that should be rendered. We don't
     * simply return OfferEvents because we want to know if any of the OfferEvents have some of their quantity
     * already consumed by other recipe flips for these items. If that is the case, we want to render them as
     * such.
     */
    private Map<Integer, List<PartialOffer>> createItemIdToPartialOffers(
            Map<Integer, Optional<FlippingItem>> itemIdToItem,
            Instant startOfInterval
    ) {
        Map<Integer, List<PartialOffer>> itemIdToPartialOffers = new HashMap<>();

        itemIdToItem.forEach((itemId, item) -> {
            List<PartialOffer> partialOffers = item.map(fitem -> {
                Map<String, PartialOffer> offerIdToPartialOffer = plugin.getOfferIdToPartialOffer(itemId);
                List<OfferEvent> offers = itemId == sourceOffer.getItemId()? new ArrayList<>(Arrays.asList(sourceOffer)): fitem.getIntervalHistory(startOfInterval);
                Collections.reverse(offers);

                return offers.stream().filter(o -> o.isBuy() == recipe.isInput(itemId) && o.isComplete()).
                        map(o -> {
                            if (offerIdToPartialOffer.containsKey(o.getUuid())) {
                                return offerIdToPartialOffer.get(o.getUuid());
                            }
                            return new PartialOffer(o, 0);
                        }).
                        collect(Collectors.toList());
            }).orElse(new ArrayList<>());
            itemIdToPartialOffers.put(itemId, partialOffers);
        });

        return itemIdToPartialOffers;
    }

    /**
     * Adds the second row to the body panel. The second row contains all the offer panels
     *
     * @param bodyPanel                   the panel the offer panels are being added to
     *                                    a reference to this label in this method so we can change the multiplier text
     * @param itemIdToPartialOffers              a map of all the items in the recipe and the offers that should be rendered
     */
    private void addOfferPanelRow(JPanel bodyPanel,
                                  Map<Integer, List<PartialOffer>> itemIdToPartialOffers) {
        Map<Integer, Integer> targetValues = plugin.getTargetValuesForMaxRecipeCount(recipe, itemIdToPartialOffers, true);

        recipe.getInputIds().forEach(id -> addOfferPanel(bodyPanel, id, itemIdToPartialOffers, targetValues));

        //empty panel occupies the space under the arrow
        JPanel emptyPanel = new JPanel();
        emptyPanel.setBackground(Color.BLACK);
        bodyPanel.add(emptyPanel);

        recipe.getOutputIds().forEach(id -> addOfferPanel(bodyPanel, id, itemIdToPartialOffers, targetValues));

        handleItemsHittingTargetConsumptionValues();
    }

    private void addOfferPanel(
        JPanel bodyPanel,
        int itemId,
        Map<Integer, List<PartialOffer>> itemIdToPartialOffers,
        Map<Integer, Integer> targetValues
    ) {
            int targetValue = targetValues.get(itemId);
            List<PartialOffer> partialOffers = itemIdToPartialOffers.get(itemId);
            RecipeItemHeaderPanel recipeItemHeaderPanel = idToHeader.get(itemId);
            recipeItemHeaderPanel.setTargetValueDisplay(targetValue);
            if (itemId == 995) {
                recipeItemHeaderPanel.setConsumedAmountDisplay(targetValue);
            }
            bodyPanel.add(createOffersPanel(itemId, partialOffers, recipeItemHeaderPanel, targetValue));
    }

    /**
     * Handles the what happens when the number picker's value changes.
     */
    private void numberPickerHandler(ChangeEvent e, OfferEvent offer, OfferPanel offerPanel,
                                     RecipeItemHeaderPanel headerPanel) {
        JSpinner numberPicker = (JSpinner) e.getSource();
        int numberPickerValue = (int) numberPicker.getValue();

        setDisplaysAndStateBasedOnSelection(numberPickerValue, offer, offerPanel, headerPanel);
        handleItemsHittingTargetConsumptionValues();
    }

    /**
     * Sets the consumed amount display and the selected offers based on the the number picker value.
     * This is used in the number picker handler method too.
     */
    private void setDisplaysAndStateBasedOnSelection(int numberPickerValue, OfferEvent offer, OfferPanel offerPanel,
                                                     RecipeItemHeaderPanel headerPanel) {
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
     * target value display for all the items.
     */
    private void handleItemsHittingTargetConsumptionValues() {
        //if the parent quantity in the recipe is not 1, gonna have to do the modding and stuff
        Map<Integer, List<PartialOffer>> idToPartialOffersSelected = selectedOffers.entrySet().stream().
            map(e -> new AbstractMap.SimpleEntry<>(e.getKey(), new ArrayList<>(e.getValue().values()))).
            collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        Map<Integer, Integer> idToTargetValues = plugin.getTargetValuesForMaxRecipeCount(recipe, idToPartialOffersSelected, false);

        AtomicBoolean allMatchTargetValues = new AtomicBoolean(true);
        selectedOffers.forEach((itemId, partialOfferMap) -> {
            RecipeItemHeaderPanel itemHeaderPanel = idToHeader.get(itemId);
            int amountConsumed = partialOfferMap.values().stream().mapToInt(o -> o.amountConsumed).sum();
            int targetConsumedAmount = idToTargetValues.get(itemId);

            itemHeaderPanel.setTargetValueDisplay(targetConsumedAmount);
            if (itemId == 995) {
                itemHeaderPanel.setConsumedAmountDisplay(targetConsumedAmount);
            }

            if ((amountConsumed == targetConsumedAmount && targetConsumedAmount != 0) || itemId == 995) {
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

    /**
     * The bottom panel holds the "Combine!" button, the profit label, and the text that
     * shows when a recipe flip can't be created due to insufficient items.
     */
    private JPanel createBottomPanel(
            Map<Integer, List<PartialOffer>> itemIdToPartialOffers) {
        JPanel bottomPanel = new JPanel();
        bottomPanel.setBorder(new EmptyBorder(5, 0, 0, 0));
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));
        bottomPanel.setBackground(Color.BLACK);

        int itemsThatCanMakeZeroRecipes = (int) plugin.getItemIdToMaxRecipesThatCanBeMade(recipe, itemIdToPartialOffers, true).entrySet().stream().
                filter(e -> e.getValue() == 0).count();

        if (itemsThatCanMakeZeroRecipes > 0) {
            JLabel missingItemsLabel = new JLabel("No recipe flip can be made as some items don't have enough" +
                    " trades", SwingConstants.CENTER);
            missingItemsLabel.setForeground(CustomColors.TOMATO);
            missingItemsLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            missingItemsLabel.setFont(new Font("Whitney", Font.PLAIN, 16));
            missingItemsLabel.setBorder(new EmptyBorder(0,0,10,0));
            bottomPanel.add(missingItemsLabel);
        }

        profitNumberLabel.setFont(new Font("Whitney", Font.PLAIN, 16));
        profitNumberLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        profitNumberLabel.setBorder(new EmptyBorder(10,0,5,0));

        finishButton.setBorder(new EmptyBorder(10, 10, 10, 10));
        finishButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        finishButton.setFont(new Font("Whitney", Font.PLAIN, 16));
        finishButton.setFocusPainted(false);
        finishButton.addActionListener(e -> {
            RecipeFlip recipeFlip = new RecipeFlip(recipe, selectedOffers, getCoinsCost());
            plugin.addRecipeFlip(recipeFlip, recipe);
            plugin.getStatPanel().rebuildRecipesDisplay(plugin.viewRecipeFlipGroupsForCurrentView());
            plugin.getStatPanel().rebuildItemsDisplay(plugin.viewItemsForCurrentView());

            numberPickers.forEach(picker -> picker.setEnabled(false));

            bottomPanel.removeAll();

            JLabel successLabel = new JLabel("Success! This flip will now show up in the Recipes tab", SwingConstants.CENTER);
            successLabel.setBorder(new EmptyBorder(0, 0, 10, 0));
            successLabel.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
            successLabel.setFont(new Font("Whitney", Font.PLAIN, 16));
            successLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

            bottomPanel.add(successLabel);
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

        JLabel title = new JLabel(recipe.getName(), JLabel.CENTER);
        title.setBorder(new EmptyBorder(0, 0, 20, 0));
        title.setFont(new Font("Whitney", Font.PLAIN, 20));

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
        return RecipeFlip.calculateProfit(selectedOffers) - getCoinsCost();
    }

    private long getCoinsCost() {
        Map<Integer, List<PartialOffer>> idToPartialOffersSelected = selectedOffers.entrySet().stream().
            map(e -> new AbstractMap.SimpleEntry<>(e.getKey(), new ArrayList<>(e.getValue().values()))).
            collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Map<Integer, Integer> idToTargetValues = plugin.getTargetValuesForMaxRecipeCount(recipe, idToPartialOffersSelected, false);
        if (idToTargetValues.containsKey(995)) {
            return idToTargetValues.get(995);
        }
        return 0;
    }
}
