package com.flippingutilities.ui.widgets;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.jobs.TimeseriesFetcher;
import com.flippingutilities.ui.uiutilities.GeSpriteLoader;
import com.flippingutilities.ui.uiutilities.UIUtilities;
import com.flippingutilities.utilities.SlotInfo;
import com.flippingutilities.utilities.SlotPredictedState;
import com.flippingutilities.utilities.WikiItemMargins;
import com.flippingutilities.utilities.WikiRequest;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.*;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;

import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * This class is responsible for enhancing slots in the GE interface by adding
 * color-coded borders and a "quick look" icon that displays a detailed tooltip on hover.
 * <p>
 * It is fed data (wiki margins, slot widgets) by the plugin. Upon receiving new data,
 * it builds a representation of what to draw and applies the visual enhancements.
 */
@Slf4j
public class SlotStateDrawer {

    private final FlippingPlugin plugin;
    private final Client client;
    private final TooltipManager tooltipManager;
    private final TimeseriesFetcher timeseriesFetcher;

    private WikiRequest wikiRequest;
    private Widget[] slotWidgets;
    private List<Optional<SlotInfo>> slotInfos = new ArrayList<>();
    private final Map<Integer, Widget> slotIdxToQuickLookWidget = new HashMap<>();

    private Integer hoveredSlotIndex = null;
    private QuickLookTooltip currentTooltip = null;
    private Integer currentlyFetchedItemId = null;

    public SlotStateDrawer(
            FlippingPlugin plugin,
            TooltipManager toolTipManager,
            Client client,
            TimeseriesFetcher timeseriesFetcher
    ) {
        this.plugin = plugin;
        this.client = client;
        this.tooltipManager = toolTipManager;
        this.timeseriesFetcher = timeseriesFetcher;
    }

    @Subscribe
    public void onBeforeRender(BeforeRender event) {
        if (hoveredSlotIndex == null || !plugin.shouldEnhanceSlots()) {
            currentTooltip = null;
            return;
        }

        final Widget geWindow = client.getWidget(InterfaceID.GeOffers.UNIVERSE);
        if (geWindow == null || geWindow.isHidden()) {
            hoveredSlotIndex = null;
            currentTooltip = null;
            return;
        }

        final Widget offerContainer = client.getWidget(
                InterfaceID.GeOffers.SETUP
        );
        if (offerContainer != null && !offerContainer.isHidden()) {
            hoveredSlotIndex = null;
            currentTooltip = null;
            return;
        }

        buildAndShowTooltip();
    }

    /**
     * Receives updated wiki margins and triggers a refresh of the slot visuals.
     */
    public void onWikiRequest(WikiRequest wikiRequest) {
        this.wikiRequest = wikiRequest;
        refreshSlotVisuals();
    }

    /**
     * Receives the GE slot widgets from the client and triggers a refresh.
     */
    public void setSlotWidgets(Widget[] slotWidgets) {
        if (slotWidgets == null) {
            return;
        }
        this.slotWidgets = slotWidgets;
        refreshSlotVisuals();
    }

    /**
     * Gatekeeper method to ensure all necessary conditions are met before drawing.
     */
    public void refreshSlotVisuals() {
        if (
                slotWidgets == null ||
                        plugin.getCurrentlyLoggedInAccount() == null ||
                        !plugin.getApiAuthHandler().isPremium() ||
                        !plugin.shouldEnhanceSlots()
        ) {
            return;
        }
        this.slotInfos = createSlotRepresentation();
        plugin.getClientThread().invokeLater(() -> draw(slotInfos));
    }

    /**
     * Draws the enhancements on all slots based on the generated SlotInfo.
     */
    private void draw(final List<Optional<SlotInfo>> slots) {
        for (int i = 0; i < slots.size(); i++) {
            final Optional<SlotInfo> slotOpt = slots.get(i);
            final Widget slotWidget = slotWidgets[i + 1];

            if (slotOpt.isPresent()) {
                drawOnSlot(slotOpt.get(), slotWidget);
            } else {
                resetSlot(i, slotWidget);
            }
        }
    }

    /**
     * Resets all visual enhancements on all slots.
     */
    public void resetAllSlots() {
        if (slotWidgets == null) {
            return;
        }
        for (int i = 0; i < 8; i++) {
            resetSlot(i, slotWidgets[i + 1]);
        }
    }

    /**
     * Reverts a single slot to its default appearance, hiding any enhancements.
     */
    private void resetSlot(int slotIdx, final Widget slotWidget) {
        // Revert borders to default color
        Map<Integer, Integer> spriteIdMap = GeSpriteLoader.CHILDREN_IDX_TO_DEFAULT_SPRITE_ID;
        GeSpriteLoader.DYNAMIC_CHILDREN_IDXS.forEach(idx -> {
            Widget child = slotWidget.getChild(idx);
            int spriteId = spriteIdMap.get(idx);
            if (child != null) {
                child.setSpriteId(spriteId);
            }
        });

        // Hide and remove the quick look widget
        Widget quickLookWidget = slotIdxToQuickLookWidget.remove(slotIdx);
        if (quickLookWidget != null) {
            quickLookWidget.setHidden(true);
        }
    }

    /**
     * Applies visual enhancements (colored border, quick look icon) to a slot.
     */
    private void drawOnSlot(final SlotInfo slot, final Widget slotWidget) {
        if (slotWidget.isHidden()) {
            return;
        }

        final Map<Integer, Integer> spriteMap = getSpriteMapForState(
                slot.getPredictedState()
        );

        GeSpriteLoader.DYNAMIC_CHILDREN_IDXS.forEach(idx -> {
            Widget child = slotWidget.getChild(idx);
            if (child != null) {
                child.setSpriteId(spriteMap.get(idx));
            }
        });

        addQuickLookWidget(slotWidget, slot);
    }

    /**
     * Gets the appropriate color-coded sprite map for the slot's predicted state.
     */
    private Map<Integer, Integer> getSpriteMapForState(
            SlotPredictedState state
    ) {
        switch (state) {
            case IN_RANGE:
                return GeSpriteLoader.CHILDREN_IDX_TO_BLUE_SPRITE_ID;
            case BETTER_THAN_WIKI:
                return GeSpriteLoader.CHILDREN_IDX_TO_GREEN_SPRITE_ID;
            case OUT_OF_RANGE:
            default:
                return GeSpriteLoader.CHILDREN_IDX_TO_RED_SPRITE_ID;
        }
    }

    /**
     * Adds or reveals the quick look (magnifying glass) icon on a slot.
     */
    private void addQuickLookWidget(Widget slotWidget, SlotInfo slot) {
        Widget existingWidget = slotIdxToQuickLookWidget.get(slot.getIndex());

        if (existingWidget == null || !isWidgetStillAttached(existingWidget)) {
            Widget newWidget = createQuickLookWidget(slotWidget, slot);
            slotIdxToQuickLookWidget.put(slot.getIndex(), newWidget);
        } else {
            existingWidget.setHidden(false);
        }
    }

    /**
     * Creates the quick look widget with its properties and mouse listeners.
     */
    private Widget createQuickLookWidget(Widget slotWidget, final SlotInfo slot) {
        Widget quickLookWidget = slotWidget.createChild(-1, WidgetType.GRAPHIC);
        quickLookWidget.setFontId(FontID.PLAIN_11);
        quickLookWidget.setOriginalX(90);
        quickLookWidget.setOriginalY(52);
        quickLookWidget.setSpriteId(SpriteID.BANK_SEARCH);
        quickLookWidget.setWidthMode(WidgetSizeMode.ABSOLUTE);
        quickLookWidget.setOriginalHeight(22);
        quickLookWidget.setOriginalWidth(22);
        quickLookWidget.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
        quickLookWidget.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
        quickLookWidget.setXTextAlignment(WidgetTextAlignment.LEFT);
        quickLookWidget.setTextShadowed(true);
        quickLookWidget.setHasListener(true);

        // Set mouse listeners to control hover state and trigger data fetching
        quickLookWidget.setOnMouseOverListener(
                (JavaScriptCallback) ev -> {
                    this.hoveredSlotIndex = slot.getIndex();

                    if (currentlyFetchedItemId == null || !currentlyFetchedItemId.equals(slot.getItemId())) {
                        currentlyFetchedItemId = slot.getItemId();

                        if (plugin.getConfig().priceGraphEnabled()) {
                            timeseriesFetcher.fetch(slot.getItemId(), plugin.getConfig().priceGraphTimestep(), tsResponse -> {
                                if (currentTooltip != null) {
                                    currentTooltip.setGraphData(tsResponse, plugin.getConfig().priceGraphTimestep(), slot.getOfferPrice());
                                }
                            });
                        }
                    }
                }
        );

        quickLookWidget.setOnMouseLeaveListener(
                (JavaScriptCallback) ev -> {
                    this.hoveredSlotIndex = null;
                    this.currentTooltip = null;
                    this.currentlyFetchedItemId = null;
                }
        );

        quickLookWidget.setOnClickListener(
                (JavaScriptCallback) ev -> this.hoveredSlotIndex = null
        );

        quickLookWidget.revalidate();
        return quickLookWidget;
    }

    /**
     * Checks if a widget is still valid and attached to its parent. This is crucial
     * for deciding whether to reuse an old widget or create a new one, especially
     * after client operations that might recreate widgets (like closing/opening the GE).
     */
    private boolean isWidgetStillAttached(Widget widget) {
        Widget parent = widget.getParent();
        if (parent == null) {
            return false;
        }
        Widget[] siblings = parent.getDynamicChildren();
        return (
                siblings != null &&
                        widget.getIndex() < siblings.length &&
                        siblings[widget.getIndex()] == widget
        );
    }

    /**
     * Creates a list of SlotInfo objects, which represent the state of each GE slot.
     * This abstracts the raw client and wiki data into a unified model for drawing.
     */
    private List<Optional<SlotInfo>> createSlotRepresentation() {
        List<Optional<SlotInfo>> slots = new ArrayList<>();
        GrandExchangeOffer[] currentOffers = client.getGrandExchangeOffers();

        for (int i = 0; i < currentOffers.length; i++) {
            GrandExchangeOffer localSlot = currentOffers[i];
            if (localSlot.getState() == GrandExchangeOfferState.EMPTY) {
                slots.add(Optional.empty());
            } else {
                slots.add(clientGeOfferToSlotInfo(i, localSlot));
            }
        }
        return slots;
    }

    /**
     * Converts a GrandExchangeOffer into a SlotInfo object using wiki margin data.
     */
    private Optional<SlotInfo> clientGeOfferToSlotInfo(
            int index,
            GrandExchangeOffer offer
    ) {
        if (wikiRequest == null || wikiRequest.getData() == null) {
            return Optional.empty();
        }

        int itemId = offer.getItemId();
        WikiItemMargins margins = this.wikiRequest.getData().get(itemId);
        if (margins == null) {
            return Optional.empty();
        }

        int listedPrice = offer.getPrice();
        boolean isBuy = offer.getState() == GrandExchangeOfferState.BUYING;
        SlotPredictedState predictedState = SlotPredictedState.getPredictedState(
                isBuy,
                listedPrice,
                margins.getLow(),
                margins.getHigh()
        );

        return Optional.of(
                new SlotInfo(index, predictedState, itemId, listedPrice, isBuy)
        );
    }

    private void buildAndShowTooltip() {
        if (
                hoveredSlotIndex >= slotInfos.size() ||
                        wikiRequest == null ||
                        wikiRequest.getData() == null
        ) {
            return;
        }

        Optional<SlotInfo> slotInfoOpt = slotInfos.get(hoveredSlotIndex);
        if (!slotInfoOpt.isPresent()) {
            return;
        }

        SlotInfo slotInfo = slotInfoOpt.get();
        WikiItemMargins margins = wikiRequest.getData().get(slotInfo.getItemId());

        if (currentTooltip == null) {
            currentTooltip = new QuickLookTooltip();
            currentTooltip.update(slotInfo, margins);
        }

        tooltipManager.add(new Tooltip(currentTooltip));
    }

}
