package com.flippingutilities.ui.widgets;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.ui.uiutilities.GeSpriteLoader;
import com.flippingutilities.ui.uiutilities.TimeFormatters;
import com.flippingutilities.utilities.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.FontID;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.SpriteID;
import net.runelite.api.widgets.*;
import org.apache.commons.lang3.tuple.Pair;

import javax.swing.*;
import java.awt.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.List;

@Slf4j
public class SlotStateDrawer {

    List<RemoteAccountSlots> remoteAccountSlots = new ArrayList<>();
    FlippingPlugin plugin;
    WikiRequest wikiRequest;
    Widget[] slotWidgets;
    Map<Integer, Widget> slotIdxToTimeInRangeWidget = new HashMap<>();
    Map<Integer, Widget> slotIdxToDescWidget = new HashMap<>();
    JPopupMenu popup = new JPopupMenu();
    JPanel detailsPanel = new JPanel();

    public SlotStateDrawer(FlippingPlugin plugin) {
        this.plugin = plugin;
    }

    public void setRemoteAccountSlots(List<RemoteAccountSlots> remoteAccountSlots) {
        this.remoteAccountSlots = remoteAccountSlots;
        drawWrapper();
    }

    public void setWikiRequest(WikiRequest wikiRequest) {
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

    private void drawWrapper() {
        if (
            slotWidgets == null ||
            plugin.getCurrentlyLoggedInAccount() == null
        ) {
            return;
        }
        plugin.getClientThread().invokeLater(this::draw);
    }

    private void draw() {
        List<Optional<RemoteSlot>> remoteSlots = getSlotStateToDraw();
        for (int i = 0; i < remoteSlots.size(); i++) {
            Optional<RemoteSlot> maybeRemoteSlot = remoteSlots.get(i);
            Widget slotWidget = slotWidgets[i + 1];
            if (!maybeRemoteSlot.isPresent()) {
                resetSlot(i, slotWidget);
            }
            else {
                drawOnSlot(maybeRemoteSlot.get(), slotWidget);
            }
        }
    }

    private void resetSlot(int slotIdx, Widget slotWidget) {
        Map<Integer, Integer> spriteIdMap = GeSpriteLoader.CHILDREN_IDX_TO_DEFAULT_SPRITE_ID;
        GeSpriteLoader.DYNAMIC_CHILDREN_IDXS.forEach(idx -> {
            Widget child = slotWidget.getChild(idx);
            int spriteId = spriteIdMap.get(idx);
            child.setSpriteId(spriteId);
        });
        Widget timeInRangeWidget = slotIdxToTimeInRangeWidget.get(slotIdx);
        if (timeInRangeWidget != null) {
            timeInRangeWidget.setHidden(true);
        }

        Widget descWidget = slotIdxToDescWidget.get(slotIdx);
        if (descWidget != null) {
            descWidget.setHidden(true);
        }
    }

    private void drawOnSlot(RemoteSlot slot, Widget slotWidget) {
        if (slotWidget.isHidden()) {
            return;
        }
        Map<Integer, Integer> spriteMap = GeSpriteLoader.CHILDREN_IDX_TO_RED_SPRITE_ID;
        if (slot.getPredictedState() == SlotPredictedState.IN_RANGE) {
            spriteMap = GeSpriteLoader.CHILDREN_IDX_TO_BLUE_SPRITE_ID;
        }
        else if (slot.getPredictedState() == SlotPredictedState.OUT_OF_RANGE) {
            spriteMap = GeSpriteLoader.CHILDREN_IDX_TO_RED_SPRITE_ID;
        }
        else if (slot.getPredictedState() == SlotPredictedState.BETTER_THAN_WIKI) {
            spriteMap = GeSpriteLoader.CHILDREN_IDX_TO_GREEN_SPRITE_ID;
        }

        Map<Integer, Integer> finalSpriteMap = spriteMap;
        GeSpriteLoader.DYNAMIC_CHILDREN_IDXS.forEach(idx -> {
            Widget child = slotWidget.getChild(idx);
            int spriteId = finalSpriteMap.get(idx);
            child.setSpriteId(spriteId);
        });

        long secondsSinceLastUpdate =
            slot.getPredictedState() == SlotPredictedState.IN_RANGE
                ? Instant.now().getEpochSecond() - slot.getLatestPredictedFilledTimestamp()
                : 0;
        long latestTimeInRange = slot.getTimeInRange() + secondsSinceLastUpdate;

        if (latestTimeInRange > 0) {
            addTimeInRangeWidget(slotWidget, slot.getIndex(), latestTimeInRange);
        }
        if (slot.getPredictedState() != SlotPredictedState.UNKNOWN) {
            addDescriptionWidget(slotWidget, slot.getIndex(), slot.getPredictedState());
        }
    }

    private void addDescriptionWidget(Widget slotWidget, int slotIdx, SlotPredictedState state) {
        Color c = getDescriptionWidgetColor(state);
        String text = getDescriptionWidgetText(state);
        Widget existingWidget = slotIdxToDescWidget.get(slotIdx);
        if (existingWidget == null || !isWidgetStillAttached(existingWidget)) {
            Widget descriptionWidget = createGraphicWidget(slotWidget, state);
            descriptionWidget.setText(text);
            descriptionWidget.setTextColor(c.getRGB());
            slotIdxToDescWidget.put(slotIdx, descriptionWidget);
        }
        else {
            existingWidget.setHidden(false);
            existingWidget.setText(text);
            existingWidget.setTextColor(c.getRGB());
        }
    }

    private void addTimeInRangeWidget(Widget slotWidget, int slotIdx, long latestTimeInRange) {
        Widget existingWidget = slotIdxToTimeInRangeWidget.get(slotIdx);
        if (existingWidget == null || !isWidgetStillAttached(existingWidget)) {
            Widget timeInRangeWidget = createTimeInRangeWidget(slotWidget);
            timeInRangeWidget.setText(TimeFormatters.formatDuration(Duration.ofSeconds(latestTimeInRange)));
            slotIdxToTimeInRangeWidget.put(slotIdx, timeInRangeWidget);
        }
        else {
            existingWidget.setHidden(false);
            existingWidget.setText(TimeFormatters.formatDuration(Duration.ofSeconds(latestTimeInRange)));
        }
        log.info("slot {} time in range is {}", slotIdx, latestTimeInRange);
    }

    private Widget createGraphicWidget(Widget slotWidget, SlotPredictedState state) {
        Widget descriptionWidget = slotWidget.createChild(-1, WidgetType.GRAPHIC);
        descriptionWidget.setFontId(FontID.PLAIN_11);
        descriptionWidget.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
        descriptionWidget.setOriginalX(88);
        descriptionWidget.setOriginalY(50);
        descriptionWidget.setSpriteId(SpriteID.BANK_SEARCH);
        descriptionWidget.setWidthMode(WidgetSizeMode.ABSOLUTE);
        descriptionWidget.setOriginalHeight(25);
        descriptionWidget.setOriginalWidth(25);
        descriptionWidget.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
        descriptionWidget.setXTextAlignment(WidgetTextAlignment.LEFT);
        descriptionWidget.setTextShadowed(true);
        descriptionWidget.setHasListener(true);


        SwingUtilities.invokeLater(() -> {


            JPanel panel = new JPanel();
            JLabel label = new JLabel("hello");
            panel.add(label);
            JPopupMenu popup = new JPopupMenu();
            popup.add(panel);
            descriptionWidget.setOnMouseOverListener((JavaScriptCallback) ev -> {
                log.info("x {}, y {}", ev.getMouseX(), ev.getMouseY());
                PointerInfo a = MouseInfo.getPointerInfo();
                Point p = a.getLocation();
                SwingUtilities.invokeLater(() -> {
                    popup.setLocation(p.x, p.y);
                    popup.setVisible(true);
                });
            });
            descriptionWidget.setOnMouseLeaveListener((JavaScriptCallback) ev -> {
                SwingUtilities.invokeLater(() -> {
                    popup.setVisible(false);
                });
            });
        });
        descriptionWidget.revalidate();
        return descriptionWidget;
    }

    private Color getDescriptionWidgetColor(SlotPredictedState state) {
        if (state == SlotPredictedState.OUT_OF_RANGE) {
            return new Color(255, 99, 71);
        }
        else if (state == SlotPredictedState.IN_RANGE) {
            return new Color(35, 139, 172);
        }
        else {
            return new Color(43, 208, 15);
        }
    }

    private String getDescriptionWidgetText(SlotPredictedState state) {
        if (state == SlotPredictedState.OUT_OF_RANGE) {
            return "Undercut";
        }
        else if (state == SlotPredictedState.IN_RANGE) {
            return "In range";
        }
        else {
            return "Best offer";
        }
    }

    private Widget createTimeInRangeWidget(Widget slotWidget) {
        Widget timeInRangeWidget = slotWidget.createChild(-1, WidgetType.TEXT);
        timeInRangeWidget.setTextColor(Color.WHITE.getRGB());
        timeInRangeWidget.setFontId(FontID.PLAIN_11);
        timeInRangeWidget.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
        timeInRangeWidget.setOriginalX(35);
        timeInRangeWidget.setOriginalY(77);
        timeInRangeWidget.setWidthMode(WidgetSizeMode.MINUS);
        timeInRangeWidget.setOriginalHeight(10);
        timeInRangeWidget.setXPositionMode(WidgetPositionMode.ABSOLUTE_CENTER);
        timeInRangeWidget.setXTextAlignment(WidgetTextAlignment.LEFT);
        timeInRangeWidget.setTextShadowed(true);
        timeInRangeWidget.revalidate();
        return timeInRangeWidget;
    }

    private boolean isWidgetStillAttached(Widget widget) {
        Widget parent = widget.getParent();
        Widget[] siblings = parent.getDynamicChildren();
        return widget.getIndex() < siblings.length && siblings[widget.getIndex()] != null;
    }

    private List<Optional<RemoteSlot>> getSlotStateToDraw() {
        List<Optional<RemoteSlot>> slots = new ArrayList<>();
        GrandExchangeOffer[] currentOffers = plugin.getClient().getGrandExchangeOffers();
        Map<Integer, OfferEvent> enrichedOffers = plugin.getDataHandler().getAccountData(plugin.getAccountCurrentlyViewed()).getLastOffers();

        String rsn = plugin.getCurrentlyLoggedInAccount();
        Optional<RemoteAccountSlots> maybeRemoteAccountSlots = remoteAccountSlots.stream().filter(r -> r.getRsn().equals(rsn)).findFirst();
        Map<Integer, RemoteSlot> idxToRemoteSlot = maybeRemoteAccountSlots
            .map(accountSlots -> ListUtils.toMap(accountSlots.getSlots(), r -> Pair.of(r.getIndex(), r)))
            .orElseGet(HashMap::new);

        for (int i = 0; i < currentOffers.length; i++) {
            GrandExchangeOffer localSlot = currentOffers[i];
            RemoteSlot remoteslot = idxToRemoteSlot.get(i);
            OfferEvent enrichedOffer = enrichedOffers.get(i);

            if (localSlot.getState() == GrandExchangeOfferState.EMPTY || OfferEvent.isComplete(localSlot.getState())) {
                slots.add(Optional.empty());
                continue;
            }
            if (remoteslot == null || !doesLocalSlotMatchWithRemote(localSlot, remoteslot)) {
                slots.add(clientGeOfferToRemoteSlot(i, localSlot, enrichedOffer));
                continue;
            }

            slots.add(Optional.of(remoteslot));
        }
        return slots;
    }

    private boolean doesLocalSlotMatchWithRemote(GrandExchangeOffer localSlot, RemoteSlot remoteSlot) {
        return localSlot.getItemId() == remoteSlot.getItemId() &&
            localSlot.getPrice() == remoteSlot.getOfferPrice() &&
            doesLocalSlotStateMatchWithRemote(localSlot, remoteSlot);
    }

    private boolean doesLocalSlotStateMatchWithRemote(GrandExchangeOffer localSlot, RemoteSlot remoteSlot) {
        switch (localSlot.getState()) {
            case SOLD:
                return !remoteSlot.isBuyOffer() && remoteSlot.getState().equals("FILLED");
            case BOUGHT:
                return remoteSlot.isBuyOffer() && remoteSlot.getState().equals("FILLED");
            case BUYING:
                return remoteSlot.isBuyOffer() && remoteSlot.getState().equals("ACTIVE");
            case SELLING:
                return !remoteSlot.isBuyOffer() && remoteSlot.getState().equals("ACTIVE");
            case CANCELLED_BUY:
                return remoteSlot.isBuyOffer() && remoteSlot.getState().equals("CANCELLED");
            case CANCELLED_SELL:
                return !remoteSlot.isBuyOffer() && remoteSlot.getState().equals("CANCELLED");
            default:
                return false;
        }
    }

    private Optional<RemoteSlot> clientGeOfferToRemoteSlot(int index, GrandExchangeOffer offer, OfferEvent enrichedOffer) {
        if (wikiRequest == null) {
            return Optional.empty();
        }

        int itemId = offer.getItemId();
        WikiItemMargins margins = this.wikiRequest.getData().get(itemId);
        if (margins == null) {
            return Optional.empty();
        }

        if (OfferEvent.isComplete(offer.getState())) {
            return Optional.empty();
        }

        int listedPrice = offer.getPrice();
        boolean isBuy = offer.getState() == GrandExchangeOfferState.BUYING;
        SlotPredictedState predictedState = getPredictedState(isBuy, listedPrice, margins.getLow(), margins.getHigh());

        return Optional.of(new RemoteSlot(index, predictedState, 0, itemId, listedPrice, isBuy, "ACTIVE", enrichedOffer.getTime().getEpochSecond()));
    }

    SlotPredictedState getPredictedState(boolean buy, int listedPrice, int instaSell, int instaBuy) {
        boolean isBetterThanWiki = buy? listedPrice > Math.max(instaBuy, instaSell) : listedPrice < Math.min(instaSell, instaBuy);
        boolean isInRange = buy? listedPrice >= Math.min(instaSell, instaBuy) : listedPrice <= Math.max(instaBuy, instaSell);

        if (isBetterThanWiki) {
            return SlotPredictedState.BETTER_THAN_WIKI;
        }
        else if (isInRange) {
            return SlotPredictedState.IN_RANGE;
        }
        else {
            return SlotPredictedState.OUT_OF_RANGE;
        }
    }
}
