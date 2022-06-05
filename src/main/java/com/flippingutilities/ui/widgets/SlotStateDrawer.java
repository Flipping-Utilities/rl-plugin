package com.flippingutilities.ui.widgets;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.ui.uiutilities.CustomColors;
import com.flippingutilities.ui.uiutilities.GeSpriteLoader;
import com.flippingutilities.ui.uiutilities.TimeFormatters;
import com.flippingutilities.utilities.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.FontID;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.SpriteID;
import net.runelite.api.widgets.*;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.QuantityFormatter;
import org.apache.commons.lang3.tuple.Pair;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
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
    SlotDetailsPanel detailsPanel = new SlotDetailsPanel();
    List<Optional<RemoteSlot>> unifiedSlotStates = new ArrayList<>();

    public SlotStateDrawer(FlippingPlugin plugin) {
        this.plugin = plugin;
        popup.add(detailsPanel);
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
        this.unifiedSlotStates = getUnifiedSlotStates();
        plugin.getClientThread().invokeLater(() -> draw(unifiedSlotStates));
    }

    private void draw(List<Optional<RemoteSlot>> remoteSlots) {
        for (int i = 0; i < remoteSlots.size(); i++) {
            Optional<RemoteSlot> maybeRemoteSlot = remoteSlots.get(i);
            Widget slotWidget = slotWidgets[i + 1];
            if (!maybeRemoteSlot.isPresent()) {
                resetSlot(i, slotWidget);
            } else {
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

        long secondsSinceLastUpdate =
            slot.getPredictedState() == SlotPredictedState.IN_RANGE
                ? Instant.now().getEpochSecond() - slot.getLatestPredictedFilledTimestamp()
                : 0;
        long latestTimeInRange = slot.getTimeInRange() + secondsSinceLastUpdate;

        if (latestTimeInRange > 0) {
            addTimeInRangeWidget(slotWidget, slot.getIndex(), latestTimeInRange);
        }
        if (slot.getPredictedState() != SlotPredictedState.UNKNOWN) {
            addQuicklookWidget(slotWidget, slot);
        }
    }

    private void addQuicklookWidget(Widget slotWidget, RemoteSlot slot) {
        log.info("adding quick look widget on slot {}", slot.getIndex());
        Widget existingWidget = slotIdxToDescWidget.get(slot.getIndex());
        if (existingWidget == null || !isWidgetStillAttached(existingWidget)) {
            Widget quicklookWidget = createQuicklookWidget(slotWidget, slot);
            slotIdxToDescWidget.put(slot.getIndex(), quicklookWidget);
        } else {
            existingWidget.setHidden(false);
        }
    }

    private void addTimeInRangeWidget(Widget slotWidget, int slotIdx, long latestTimeInRange) {
        Widget existingWidget = slotIdxToTimeInRangeWidget.get(slotIdx);
        if (existingWidget == null || !isWidgetStillAttached(existingWidget)) {
            Widget timeInRangeWidget = createTimeInRangeWidget(slotWidget);
            timeInRangeWidget.setText(TimeFormatters.formatDuration(Duration.ofSeconds(latestTimeInRange)));
            slotIdxToTimeInRangeWidget.put(slotIdx, timeInRangeWidget);
        } else {
            existingWidget.setHidden(false);
            existingWidget.setText(TimeFormatters.formatDuration(Duration.ofSeconds(latestTimeInRange)));
        }
    }

    private Widget createQuicklookWidget(Widget slotWidget, RemoteSlot slot) {
        Widget detailsWidget = slotWidget.createChild(-1, WidgetType.GRAPHIC);
        detailsWidget.setFontId(FontID.PLAIN_11);
        detailsWidget.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
        detailsWidget.setOriginalX(90);
        detailsWidget.setOriginalY(52);
        detailsWidget.setSpriteId(SpriteID.BANK_SEARCH);
        detailsWidget.setWidthMode(WidgetSizeMode.ABSOLUTE);
        detailsWidget.setOriginalHeight(22);
        detailsWidget.setOriginalWidth(22);
        detailsWidget.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
        detailsWidget.setXTextAlignment(WidgetTextAlignment.LEFT);
        detailsWidget.setTextShadowed(true);
        detailsWidget.setHasListener(true);

        detailsWidget.setOnMouseOverListener((JavaScriptCallback) ev -> {
            SwingUtilities.invokeLater(() -> {
                if (this.wikiRequest == null) {
                    detailsPanel.updateDetails(null, null);
                    return;
                }
                Optional<RemoteSlot> maybeRemoteSlot = unifiedSlotStates.get(slot.getIndex());
                if (!maybeRemoteSlot.isPresent()) {
                    detailsPanel.updateDetails(null, null);
                    return;
                }

                int itemId = maybeRemoteSlot.get().getItemId();
                WikiItemMargins margins = this.wikiRequest.getData().get(itemId);
                PointerInfo a = MouseInfo.getPointerInfo();
                Point p = a.getLocation();
                detailsPanel.updateDetails(maybeRemoteSlot.get(), margins);
                popup.pack();
                popup.setLocation(p.x, p.y);
                popup.setVisible(true);
            });
        });

        detailsWidget.setOnMouseLeaveListener((JavaScriptCallback) ev -> {
            SwingUtilities.invokeLater(() -> {
                popup.setVisible(false);
            });
        });

        detailsWidget.revalidate();
        return detailsWidget;
    }

    private Color getDescriptionWidgetColor(SlotPredictedState state) {
        if (state == SlotPredictedState.OUT_OF_RANGE) {
            return CustomColors.TOMATO;
        } else if (state == SlotPredictedState.IN_RANGE) {
            return CustomColors.IN_RANGE;
        } else {
            return ColorScheme.GRAND_EXCHANGE_PRICE;
        }
    }

    private String getDescriptionWidgetText(SlotPredictedState state) {
        if (state == SlotPredictedState.OUT_OF_RANGE) {
            return "Undercut";
        } else if (state == SlotPredictedState.IN_RANGE) {
            return "In range";
        } else {
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

    private List<Optional<RemoteSlot>> getUnifiedSlotStates() {
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
        boolean isBetterThanWiki = buy ? listedPrice > Math.max(instaBuy, instaSell) : listedPrice < Math.min(instaSell, instaBuy);
        boolean isInRange = buy ? listedPrice >= Math.min(instaSell, instaBuy) : listedPrice <= Math.max(instaBuy, instaSell);

        if (isBetterThanWiki) {
            return SlotPredictedState.BETTER_THAN_WIKI;
        } else if (isInRange) {
            return SlotPredictedState.IN_RANGE;
        } else {
            return SlotPredictedState.OUT_OF_RANGE;
        }
    }
}

class SlotDetailsPanel extends JPanel {
    JLabel wikiInstaBuy = new JLabel();
    JLabel wikiInstaSell = new JLabel();
    JLabel wikiInstaBuyAge = new JLabel();
    JLabel wikiInstaSellAge = new JLabel();
    JLabel summary = new JLabel("", JLabel.CENTER);

    SlotDetailsPanel() {
        super();
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(10, 15, 10, 15));

        wikiInstaBuy.setForeground(Color.WHITE);
        wikiInstaSell.setForeground(Color.WHITE);

        JLabel title = new JLabel("Quick Look", JLabel.CENTER);
        title.setFont(new Font("Whitney", Font.BOLD + Font.ITALIC, 12));

        JLabel wikiInstaBuyDesc = new JLabel("Wiki Insta Buy");
        JLabel wikiInstaSellDesc = new JLabel("Wiki Insta Sell");
        JLabel wikiInstaBuyAgeDesc = new JLabel("Wiki Insta Buy Age");
        JLabel wikiInstaSellAgeDesc = new JLabel("Wiki Insta Sell Age");

        //being lazy...just want to separate the rows that hold the wiki price vals from the rows that hold the wiki time
        //vals
        wikiInstaBuyAgeDesc.setBorder(new EmptyBorder(5,0,0,0));
        wikiInstaBuyAge.setBorder(new EmptyBorder(5,0,0,0));

        Arrays.asList(wikiInstaBuyDesc, wikiInstaSellDesc, wikiInstaBuyAgeDesc, wikiInstaSellAgeDesc, wikiInstaBuy,
            wikiInstaSell, wikiInstaBuyAge, wikiInstaSellAge).forEach(l -> l.setFont(FontManager.getRunescapeSmallFont()));

        JPanel wikiPanel = new JPanel(new DynamicGridLayout(4, 2, 10, 2));
        wikiPanel.setBorder(new EmptyBorder(10,0,0,0));
        wikiPanel.add(wikiInstaBuyDesc);
        wikiPanel.add(wikiInstaBuy);
        wikiPanel.add(wikiInstaSellDesc);
        wikiPanel.add(wikiInstaSell);

        wikiPanel.add(wikiInstaBuyAgeDesc);
        wikiPanel.add(wikiInstaBuyAge);
        wikiPanel.add(wikiInstaSellAgeDesc);
        wikiPanel.add(wikiInstaSellAge);

        JPanel summaryPanel = new JPanel(new BorderLayout());
        summaryPanel.add(summary);

        add(title, BorderLayout.NORTH);
        add(wikiPanel, BorderLayout.CENTER);
        add(summaryPanel, BorderLayout.SOUTH);
    }

    public void updateDetails(RemoteSlot slot, WikiItemMargins wikiItemInfo) {
        wikiInstaBuy.setForeground(Color.WHITE);
        wikiInstaSell.setForeground(Color.WHITE);
        wikiInstaBuyAge.setForeground(Color.WHITE);
        wikiInstaSellAge.setForeground(Color.WHITE);
        Map<Integer, JLabel> m = new HashMap<>();
        m.put(wikiItemInfo.getHigh(), wikiInstaBuy);
        m.put(wikiItemInfo.getLow(), wikiInstaSell);
        if (wikiItemInfo == null || slot == null) {
            wikiInstaBuyAge.setText("No data");
            wikiInstaSellAge.setText("No data");
            wikiInstaBuy.setText("No data");
            wikiInstaSell.setText("No data");
            return;
        }
        if (wikiItemInfo.getHighTime() == 0) {
            wikiInstaBuyAge.setText("No data");
        } else {
            wikiInstaBuyAge.setText(TimeFormatters.formatDuration(Instant.ofEpochSecond(wikiItemInfo.getHighTime())));
        }
        if (wikiItemInfo.getLowTime() == 0) {
            wikiInstaSellAge.setText("No data");
        } else {
            wikiInstaSellAge.setText(TimeFormatters.formatDuration(Instant.ofEpochSecond(wikiItemInfo.getLowTime())));
        }

        wikiInstaBuy.setText(wikiItemInfo.getHigh() == 0 ? "No data" : QuantityFormatter.formatNumber(wikiItemInfo.getHigh()) + " gp");
        wikiInstaSell.setText(wikiItemInfo.getLow() == 0 ? "No data" : QuantityFormatter.formatNumber(wikiItemInfo.getLow()) + " gp");

        int max = Math.max(wikiItemInfo.getHigh(), wikiItemInfo.getLow());
        int min = Math.min(wikiItemInfo.getHigh(), wikiItemInfo.getLow());

        if (slot.isBuyOffer() && slot.getPredictedState() == SlotPredictedState.BETTER_THAN_WIKI) {
            m.get(max).setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
            summary.setText(
                String.format("buy offer is ultra competitive: %s is >= %s",
                    QuantityFormatter.formatNumber(slot.getOfferPrice()),
                    QuantityFormatter.formatNumber(max)
                    ));
        }
        else if (slot.isBuyOffer() && slot.getPredictedState() == SlotPredictedState.IN_RANGE) {
            m.get(min).setForeground(CustomColors.IN_RANGE);
            summary.setText(
                String.format("buy offer is competitive: %s >= %s < %s",
                    QuantityFormatter.formatNumber(slot.getOfferPrice()),
                    QuantityFormatter.formatNumber(min)
                ));
        }
        else if (slot.isBuyOffer() && slot.getPredictedState() == SlotPredictedState.OUT_OF_RANGE) {
            m.get(min).setForeground(CustomColors.TOMATO);
            summary.setText(
                String.format("buy offer is not competitive: %s < %s",
                    QuantityFormatter.formatNumber(slot.getOfferPrice()),
                    QuantityFormatter.formatNumber(min)
                ));
        }
        else if (!slot.isBuyOffer() && slot.getPredictedState() == SlotPredictedState.BETTER_THAN_WIKI) {
            m.get(min).setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
            summary.setText(
                String.format("sell offer is ultra competitive: %s < %s",
                    QuantityFormatter.formatNumber(slot.getOfferPrice()),
                    QuantityFormatter.formatNumber(min)
                ));
        }
        else if (!slot.isBuyOffer() && slot.getPredictedState() == SlotPredictedState.IN_RANGE) {
            m.get(max).setForeground(CustomColors.IN_RANGE);
            summary.setText(
                String.format("sell offer is competitive: %s < %s",
                    QuantityFormatter.formatNumber(slot.getOfferPrice()),
                    QuantityFormatter.formatNumber(max)
                ));
        }
        else if (!slot.isBuyOffer() && slot.getPredictedState() == SlotPredictedState.OUT_OF_RANGE) {
            m.get(max).setForeground(CustomColors.TOMATO);
            summary.setText(
                String.format("sell offer is not competitive: %s > %s",
                    QuantityFormatter.formatNumber(slot.getOfferPrice()),
                    QuantityFormatter.formatNumber(max)
                ));
        }
    }

    private String getSummaryText() {
        return "";
    }
}
