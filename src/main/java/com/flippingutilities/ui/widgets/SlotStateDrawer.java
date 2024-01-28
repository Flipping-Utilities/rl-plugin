package com.flippingutilities.ui.widgets;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.ui.uiutilities.*;
import com.flippingutilities.utilities.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.FontID;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.SpriteID;
import net.runelite.api.widgets.*;
import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * This class is responsible for enhancing slots in the ge interface. It
 * 1. adds color to slots to mark the offers as competitive or not
 * 2. adds a widget that shows some preview info on the competitiveness of the offer
 * <p>
 * <p>
 * This class requires two pieces/types of data for drawing on/enhancing the slots which are change frequently:
 * 1. wiki margins
 * 2. actual slot widgets
 * <p>
 * It is fed this data everytime it changes by the plugin (see onWikiRequest and setSlotWidgets). Everytime
 * it is fed new data, it builds a representation of what it should draw (see createSlotRepresentation),
 * and then draws it on the slot (see drawWrapper)
 *
 * Also note that this doesn't make requests to our api for the predicted slot state, it just uses the local slots.
 */
@Slf4j
public class SlotStateDrawer {
    FlippingPlugin plugin;
    WikiRequest wikiRequest;
    Widget[] slotWidgets;
    Map<Integer, Widget> slotIdxToQuickLookWidget = new HashMap<>();
    JPopupMenu popup = new JPopupMenu();
    QuickLookPanel quickLookPanel = new QuickLookPanel();
    List<Optional<SlotInfo>> slotInfos = new ArrayList<>();

    public SlotStateDrawer(FlippingPlugin plugin) {
        this.plugin = plugin;
        popup.add(quickLookPanel);
    }

    public void onWikiRequest(WikiRequest wikiRequest) {
        this.wikiRequest = wikiRequest;
        drawWrapper();
    }

    public void setSlotWidgets(Widget[] slotWidgets) {
        if (slotWidgets == null) {
            return;
        }
        this.slotWidgets = slotWidgets;
        drawWrapper();
    }

    public void hideQuickLookPanel() {
        popup.setVisible(false);
    }

    /**
     * Thin wrapper around draw to decide if drawing should take place.
     */
    public void drawWrapper() {
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
     * Draws the enhancements on the slot
     */
    private void draw(List<Optional<SlotInfo>> slots) {
        for (int i = 0; i < slots.size(); i++) {
            Optional<SlotInfo> slot = slots.get(i);
            Widget slotWidget = slotWidgets[i + 1];
            if (!slot.isPresent()) {
                resetSlot(i, slotWidget);
            } else {
                drawOnSlot(slot.get(), slotWidget);
            }
        }
    }

    public void resetAllSlots() {
        if (slotWidgets == null) {
            return;
        }
        for (int i = 0; i < 8; i++) {
            Widget slotWidget = slotWidgets[i + 1];
            resetSlot(i, slotWidget);
        }
    }

    /**
     * Hides all the slot enhancements that were previously drawn on the slot. This is
     * done when the slot was once populated with an offer but is now empty.
     */
    private void resetSlot(int slotIdx, Widget slotWidget) {
        Map<Integer, Integer> spriteIdMap = GeSpriteLoader.CHILDREN_IDX_TO_DEFAULT_SPRITE_ID;
        GeSpriteLoader.DYNAMIC_CHILDREN_IDXS.forEach(idx -> {
            Widget child = slotWidget.getChild(idx);
            int spriteId = spriteIdMap.get(idx);
            if (child != null) {
                child.setSpriteId(spriteId);
            }
        });

        Widget quickLookWidget = slotIdxToQuickLookWidget.get(slotIdx);
        if (quickLookWidget != null) {
            quickLookWidget.setHidden(true);
            slotIdxToQuickLookWidget.remove(slotIdx);
        }
    }

    private void drawOnSlot(SlotInfo slot, Widget slotWidget) {
        if (slotWidget.isHidden()) {
            return;
        }
        Map<Integer, Integer> spriteMap = GeSpriteLoader.CHILDREN_IDX_TO_RED_SPRITE_ID;
        if (slot.getPredictedState() == SlotPredictedState.IN_RANGE) {
            spriteMap = GeSpriteLoader.CHILDREN_IDX_TO_BLUE_SPRITE_ID;
        } else if (slot.getPredictedState() == SlotPredictedState.OUT_OF_RANGE) {
            spriteMap = GeSpriteLoader.CHILDREN_IDX_TO_RED_SPRITE_ID;
        } else if (slot.getPredictedState() == SlotPredictedState.BETTER_THAN_WIKI) {
            spriteMap = GeSpriteLoader.CHILDREN_IDX_TO_GREEN_SPRITE_ID;
        }

        Map<Integer, Integer> finalSpriteMap = spriteMap;
        GeSpriteLoader.DYNAMIC_CHILDREN_IDXS.forEach(idx -> {
            Widget child = slotWidget.getChild(idx);
            int spriteId = finalSpriteMap.get(idx);
            child.setSpriteId(spriteId);
        });

        addQuicklookWidget(slotWidget, slot);
    }

    /**
     * This is the image widget (the magnifying glass widget) that a user can hover over to
     * see some quick details about the competitiveness of their offer.
     */
    private void addQuicklookWidget(Widget slotWidget, SlotInfo slot) {
        Widget existingQuickLookWidget = slotIdxToQuickLookWidget.get(slot.getIndex());
        if (existingQuickLookWidget == null || !isWidgetStillAttached(existingQuickLookWidget)) {
            Widget quicklookWidget = createQuicklookWidget(slotWidget, slot);
            slotIdxToQuickLookWidget.put(slot.getIndex(), quicklookWidget);
        } else {
            existingQuickLookWidget.setHidden(false);
        }
    }

    private Widget createQuicklookWidget(Widget slotWidget, SlotInfo slot) {
        Widget quickLookWidget = slotWidget.createChild(-1, WidgetType.GRAPHIC);
        quickLookWidget.setFontId(FontID.PLAIN_11);
        quickLookWidget.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
        quickLookWidget.setOriginalX(90);
        quickLookWidget.setOriginalY(52);
        quickLookWidget.setSpriteId(SpriteID.BANK_SEARCH);
        quickLookWidget.setWidthMode(WidgetSizeMode.ABSOLUTE);
        quickLookWidget.setOriginalHeight(22);
        quickLookWidget.setOriginalWidth(22);
        quickLookWidget.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
        quickLookWidget.setXTextAlignment(WidgetTextAlignment.LEFT);
        quickLookWidget.setTextShadowed(true);
        quickLookWidget.setHasListener(true);

        quickLookWidget.setOnMouseOverListener((JavaScriptCallback) ev -> {
            SwingUtilities.invokeLater(() -> {
                if (this.wikiRequest == null) {
                    quickLookPanel.updateDetails(null, null);
                    return;
                }
                Optional<SlotInfo> slotInfo = slotInfos.get(slot.getIndex());
                if (!slotInfo.isPresent()) {
                    quickLookPanel.updateDetails(null, null);
                    return;
                }

                int itemId = slotInfo.get().getItemId();
                WikiItemMargins margins = this.wikiRequest.getData().get(itemId);
                PointerInfo a = MouseInfo.getPointerInfo();
                Point p = a.getLocation();
                quickLookPanel.updateDetails(slotInfo.get(), margins);
                popup.pack();
                popup.setLocation(p.x + 25, p.y);
                popup.setVisible(true);
            });
        });

        quickLookWidget.setOnMouseLeaveListener((JavaScriptCallback) ev -> {
            SwingUtilities.invokeLater(() -> {
                popup.setVisible(false);
            });
        });

        quickLookWidget.revalidate();
        return quickLookWidget;
    }

    /**
     * Determines if the widget is still actually on the screen. This is useful for deciding
     * whether we should reuse the old widget, or create a new one.
     */
    private boolean isWidgetStillAttached(Widget widget) {
        Widget parent = widget.getParent();
        Widget[] siblings = parent.getDynamicChildren();
        return widget.getIndex() < siblings.length && siblings[widget.getIndex()] != null;
    }

    /**
     * Builds a representation of the slots to draw
     */
    private List<Optional<SlotInfo>> createSlotRepresentation() {
        List<Optional<SlotInfo>> slots = new ArrayList<>();
        GrandExchangeOffer[] currentOffers = plugin.getClient().getGrandExchangeOffers();

        for (int i = 0; i < currentOffers.length; i++) {
            GrandExchangeOffer localSlot = currentOffers[i];

            if (localSlot.getState() == GrandExchangeOfferState.EMPTY) {
                slots.add(Optional.empty());
                continue;
            }

            slots.add(clientGeOfferToSlotInfo(i, localSlot));
        }
        return slots;
    }

    private Optional<SlotInfo> clientGeOfferToSlotInfo(int index, GrandExchangeOffer offer) {
        if (wikiRequest == null) {
            return Optional.empty();
        }

        int itemId = offer.getItemId();
        WikiItemMargins margins = this.wikiRequest.getData().get(itemId);
        if (margins == null) {
            return Optional.empty();
        }

        int listedPrice = offer.getPrice();
        boolean isBuy = offer.getState() == GrandExchangeOfferState.BUYING;
        SlotPredictedState predictedState = SlotPredictedState.getPredictedState(isBuy, listedPrice, margins.getLow(), margins.getHigh());

        return Optional.of(new SlotInfo(index, predictedState, itemId, listedPrice, isBuy));
    }
}