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
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetType;
import org.apache.commons.lang3.tuple.Pair;

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
    Map<Integer, Widget> slotIdxTimeInRangeWidget = new HashMap<>();

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
        List<RemoteSlot> remoteSlots = getSlotStateToDraw();
        for (int i = 0; i < remoteSlots.size(); i++) {
            RemoteSlot remoteSlot = remoteSlots.get(i);
            Widget slotWidget = slotWidgets[i + 1];
            if (remoteSlot == null) {
                resetSlot(i, slotWidget);
            }
            else {
                drawOnSlot(remoteSlot, slotWidget);
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
        Widget timeInRangeWidget = slotIdxTimeInRangeWidget.get(slotIdx);
        if (timeInRangeWidget != null) {
            timeInRangeWidget.setHidden(true);
            timeInRangeWidget.setOriginalHeight(0);
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
            log.info("slot {} time in range is {}", slot.getIndex(), latestTimeInRange);
            Widget timeInRangeWidget = slotWidget.createChild(26, WidgetType.TEXT);
            timeInRangeWidget.setText(TimeFormatters.formatDuration(Duration.ofSeconds(latestTimeInRange)));
            timeInRangeWidget.setTextColor(Color.WHITE.getRGB());
            timeInRangeWidget.setFontId(FontID.PLAIN_11);
            timeInRangeWidget.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
            timeInRangeWidget.setOriginalX(45);
            timeInRangeWidget.setOriginalY(60);
            timeInRangeWidget.setWidthMode(WidgetSizeMode.MINUS);
            timeInRangeWidget.setOriginalHeight(10);
//        w.setOriginalWidth(20);
            timeInRangeWidget.setXPositionMode(WidgetPositionMode.ABSOLUTE_CENTER);
            timeInRangeWidget.setXTextAlignment(0);
            timeInRangeWidget.setTextShadowed(true);
            timeInRangeWidget.revalidate();
            slotIdxTimeInRangeWidget.put(slot.getIndex(), timeInRangeWidget);
        }
    }

    private List<RemoteSlot> getSlotStateToDraw() {
        List<RemoteSlot> slots = new ArrayList<>();
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
                slots.add(null);
                continue;
            }
            if (remoteslot == null || !doesLocalSlotMatchWithRemote(localSlot, remoteslot)) {
                clientGeOfferToRemoteSlot(i, localSlot, enrichedOffer).ifPresent(slots::add);
                continue;
            }

            slots.add(remoteslot);
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







    private transient Widget slotWidget;
    private transient Widget slotItemNameText;

//    public void setWidget(Widget slotWidget)
//    {
//
//        Color c = new Color(229, 94, 94);
//        this.slotWidget = slotWidget;
//        Widget w = slotWidget.createChild(26, WidgetType.TEXT);
//

//
//        w.setText("UNDERCUT");
//        w.setTextColor(c.getRGB());
//        w.setFontId(FontID.PLAIN_11);
//        w.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
//        w.setOriginalX(45);
//        w.setOriginalY(60);
//        w.setWidthMode(WidgetSizeMode.MINUS);
//        w.setOriginalHeight(10);
////        w.setOriginalWidth(20);
//        w.setXPositionMode(WidgetPositionMode.ABSOLUTE_CENTER);
//        w.setXTextAlignment(0);
//        w.setTextShadowed(true);
//        w.revalidate();
////        slotItemNameText = slotWidget.getChild(19);
////        slotItemNameText.setText("Infinity hat<br><br>" + ColorUtil.wrapWithColorTag("UNDERCUT", c));

//
////        slotItemNameText.setText("<html>" + ColorUtil.wrapWithColorTag(itemName, c) + "<br>UNDERCUT </html>");
////        slotStateString = slotItemNameText.getText();
//        slotWidget.revalidate();
//    }
}
