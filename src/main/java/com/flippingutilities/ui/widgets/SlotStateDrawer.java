package com.flippingutilities.ui.widgets;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.utilities.*;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.widgets.Widget;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


public class SlotStateDrawer {

    List<RemoteAccountSlots> remoteAccountSlots = new ArrayList<>();
    FlippingPlugin plugin;
    WikiRequest wikiRequest;

    public SlotStateDrawer(FlippingPlugin plugin) {
        this.plugin = plugin;
    }

    public void setRemoteAccountSlots(List<RemoteAccountSlots> remoteAccountSlots) {
        //need to draw here
    }

    public void setWikiRequest(WikiRequest wikiRequest) {
        this.wikiRequest = wikiRequest;
        //do i want to draw here?
    }

    public void setSlotWidgets(Widget[] slotWidgets) {
        if (slotWidgets == null) {
            return;
        }
        //need to draw here
    }

    private void draw() {

    }

    private void getSlotStateToDraw() {
        GrandExchangeOffer[] currentOffers = plugin.getClient().getGrandExchangeOffers();

        String rsn = plugin.getCurrentlyLoggedInAccount();
        Optional<RemoteAccountSlots> maybeRemoteAccountSlots = remoteAccountSlots.stream().filter(r -> r.getRsn().equals(rsn)).findFirst();


    }

    private Optional<RemoteSlot> clientGeOfferToRemoteSlot(int index, GrandExchangeOffer offer) {
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

        return Optional.of(new RemoteSlot(index, predictedState, 0));
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
//        GeSpriteLoader.DYNAMIC_CHILDREN_IDXS.forEach(idx -> {
//            Widget child = slotWidget.getChild(idx);
//            int spriteId = GeSpriteLoader.CHILDREN_IDX_TO_RED_SPRITE_ID.get(idx);
//            child.setSpriteId(spriteId);
//        });
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
////        "undercut"
////        "in range"
//
////        slotItemNameText.setText("<html>" + ColorUtil.wrapWithColorTag(itemName, c) + "<br>UNDERCUT </html>");
////        slotStateString = slotItemNameText.getText();
//        slotWidget.revalidate();
//    }
}
