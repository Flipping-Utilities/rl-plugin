package com.flippingutilities.controller;

import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.ui.widgets.SlotActivityTimer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.WorldType;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.http.api.item.ItemStats;

import java.time.Instant;
import java.util.*;

@Slf4j
public class NewOfferEventPipelineHandler {
    FlippingPlugin plugin;

    NewOfferEventPipelineHandler(FlippingPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * This method is invoked every time the plugin receives a GrandExchangeOfferChanged event which is
     * when the user set an offer, cancelled an offer, or when an offer was updated (items bought/sold partially
     * or completely).
     *
     * @param offerChangedEvent the offer event that represents when an offer is updated
     *                          (buying, selling, bought, sold, cancelled sell, or cancelled buy)
     */
    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged offerChangedEvent) {
        EnumSet<WorldType> currentWorldType = this.plugin.getClient().getWorldType();
        EnumSet<WorldType> excludedWorldTypes = EnumSet.of(WorldType.SEASONAL, WorldType.DEADMAN);
        if (!Collections.disjoint(currentWorldType, excludedWorldTypes)) {
            return;
        }

        if (plugin.getCurrentlyLoggedInAccount() == null) {
            OfferEvent newOfferEvent = createOfferEvent(offerChangedEvent);
            //event came in before account was fully logged in. This means that the offer actually came through
            //sometime when the account was logged out, at an undetermined time. We need to mark the offer as such to
            //avoid adjusting ge limits and slot timers incorrectly (cause we don't know exactly when the offer came in)
            newOfferEvent.setBeforeLogin(true);
            plugin.getEventsReceivedBeforeFullLogin().add(newOfferEvent);
            return;
        }
        OfferEvent newOfferEvent = createOfferEvent(offerChangedEvent);
        if (newOfferEvent.getTickArrivedAt() == plugin.getLoginTickCount()) {
            newOfferEvent.setBeforeLogin(true);
        }
        onNewOfferEvent(newOfferEvent);
    }

    public void onNewOfferEvent(OfferEvent newOfferEvent) {
        String currentlyLoggedInAccount = plugin.getCurrentlyLoggedInAccount();
        if (currentlyLoggedInAccount != null) {
            newOfferEvent.setMadeBy(currentlyLoggedInAccount);
        }

        Optional<OfferEvent> screenedOfferEvent = screenOfferEvent(newOfferEvent);

        if (!screenedOfferEvent.isPresent()) {
            return;
        }

        OfferEvent finalizedOfferEvent = screenedOfferEvent.get();
        
        List<FlippingItem> currentlyLoggedInAccountsTrades = plugin.getDataHandler().getAccountData(currentlyLoggedInAccount).getTrades();

        Optional<FlippingItem> flippingItem = currentlyLoggedInAccountsTrades.stream().filter(item -> item.getItemId() == finalizedOfferEvent.getItemId()).findFirst();

        updateTradesList(currentlyLoggedInAccountsTrades, flippingItem, finalizedOfferEvent.clone());

        plugin.setUpdateSinceLastAccountWideBuild(true);

        rebuildDisplayAfterOfferEvent(finalizedOfferEvent);
    }

    /**
     * There is no point rebuilding either the stats panel or flipping panel when the user is looking at the trades list of
     * one of their accounts that isn't logged in as that trades list won't be being updated anyway.
     *
     * @param offerEvent   offer event just received
     */
    private void rebuildDisplayAfterOfferEvent(OfferEvent offerEvent) {

        if (!(plugin.getAccountCurrentlyViewed().equals(plugin.getCurrentlyLoggedInAccount()) ||
                plugin.getAccountCurrentlyViewed().equals(FlippingPlugin.ACCOUNT_WIDE))) {
            return;
        }

        plugin.getFlippingPanel().onNewOfferEventRebuild(offerEvent);
        plugin.getStatPanel().rebuildItemsDisplay(plugin.viewItemsForCurrentView());
    }

    /**
     * Runelite has some wonky events. For example, every empty/buy/sell/cancelled buy/cancelled sell
     * spawns two identical events. And when you fully buy/sell item, it spawns two events (a
     * buying/selling event and a bought/sold event). This method screens out the unwanted events/duplicate
     * events and sets the ticksSinceFirstOffer field correctly on new OfferEvents. This method is also responsible
     * for broadcasting the event to any components that need it, such as the slot panel, the slot timer widgets, etc.
     *
     * @param newOfferEvent event that just occurred
     * @return an optional containing an OfferEvent.
     */
    public Optional<OfferEvent>  screenOfferEvent(OfferEvent newOfferEvent) {
        //TODO this method can probably handle the different states in a more elegant manner...
        plugin.getSlotsPanel().update(newOfferEvent);
        Map<Integer, OfferEvent> lastOfferEventForEachSlot = plugin.getDataHandler().getAccountData(plugin.getCurrentlyLoggedInAccount()).getLastOffers();
        List<SlotActivityTimer> slotActivityTimers = plugin.getDataHandler().getAccountData(plugin.getCurrentlyLoggedInAccount()).getSlotTimers();
        OfferEvent lastOfferEvent = lastOfferEventForEachSlot.get(newOfferEvent.getSlot());

        if (newOfferEvent.isCausedByEmptySlot() && newOfferEvent.isBeforeLogin()) {
            return Optional.empty();
        }

        //is null when an offer was removed or perhaps the slot has an offer but that offer was made
        //outside the plugin, so the lastOfferEvent for that slot is still null.
        if (lastOfferEvent == null) {
            if (!newOfferEvent.isCausedByEmptySlot()) {
                lastOfferEventForEachSlot.put(newOfferEvent.getSlot(), newOfferEvent);
                slotActivityTimers.get(newOfferEvent.getSlot()).setCurrentOffer(newOfferEvent);
            }
            if (newOfferEvent.isStartOfOffer()) {
                newOfferEvent.setTradeStartedAt(Instant.now());
            }

            return Optional.empty();
        }

        //we get essentially every offer event twice..
        if (lastOfferEvent.isDuplicate(newOfferEvent)) {
            return Optional.empty();
        }

        //get empty slot offer events on login, reject them. Just make sure the last offer isn't complete as we don't
        //want to re-add a non complete offer that comes in the second batch of login events as that will mess up its
        //"ticksSinceFirstOffer" (making it 0).
        if (newOfferEvent.isCausedByEmptySlot() && !lastOfferEvent.isComplete()) {
            return Optional.empty();
        }

        //empty offer event after collecting an offer. Mark the slot as being empty by removing the offer event for it.
        //this will technically also trigger on login when we get those empty slot events if the last event
        //had a complete state, but in that case we will just re-add that offer event on the next tick when we get an
        //event for it and since it's a complete offer already, its ticksSinceLastOffer
        if (newOfferEvent.isCausedByEmptySlot() && lastOfferEvent.isComplete()) {
            lastOfferEventForEachSlot.remove(newOfferEvent.getSlot());
            return Optional.empty();
        }

        if (newOfferEvent.isRedundantEventBeforeOfferCompletion()) {
            return Optional.empty();
        }

        newOfferEvent.setTicksSinceFirstOffer(lastOfferEvent);
        newOfferEvent.setTradeStartedAt(lastOfferEvent.getTradeStartedAt());
        lastOfferEventForEachSlot.put(newOfferEvent.getSlot(), newOfferEvent);
        slotActivityTimers.get(newOfferEvent.getSlot()).setCurrentOffer(newOfferEvent);
        return newOfferEvent.getCurrentQuantityInTrade() ==0? Optional.empty() : Optional.of(newOfferEvent);
    }

    /**
     * We get offer events that mark the start of an offer on login, even though we already received them prior to logging
     * out. This method is used to identify them.
     *
     * @param offerEvent
     * @return whether or not this trade event is a duplicate "start of trade" event
     */
    private boolean isDuplicateStartOfOfferEvent(OfferEvent offerEvent) {
        Map<Integer, OfferEvent> loggedInAccsLastOffers = plugin.getDataHandler().viewAccountData(plugin.getCurrentlyLoggedInAccount()).getLastOffers();
        return loggedInAccsLastOffers.containsKey(offerEvent.getSlot()) &&
                loggedInAccsLastOffers.get(offerEvent.getSlot()).getCurrentQuantityInTrade() == 0 &&
                loggedInAccsLastOffers.get(offerEvent.getSlot()).getState() == offerEvent.getState();
    }

    /**
     * Creates an OfferEvent object out of a GrandExchangeOfferChanged event and adds additional attributes such as
     * tickArrivedAt to help identify margin check offers.
     *
     * @param newOfferEvent event that we subscribe to.
     * @return an OfferEvent object with the relevant information from the event.
     */
    private OfferEvent createOfferEvent(GrandExchangeOfferChanged newOfferEvent) {
        OfferEvent offer = OfferEvent.fromGrandExchangeEvent(newOfferEvent);
        offer.setTickArrivedAt(plugin.getClient().getTickCount());
        offer.setMadeBy(plugin.getCurrentlyLoggedInAccount());
        return offer;
    }

    /**
     * This method updates the given trade list in response to an OfferEvent
     *
     * @param trades       the trades list to update
     * @param flippingItem the flipping item to be updated in the tradeslist, if it even exists
     * @param newOffer     new offer that just came in
     */
    private void updateTradesList(List<FlippingItem> trades, Optional<FlippingItem> flippingItem, OfferEvent newOffer) {
        if (flippingItem.isPresent()) {
            FlippingItem item = flippingItem.get();

            //if a user buys/sells an item they previously deleted from the flipping panel, show the panel again.
            if (!item.getValidFlippingPanelItem()) {
                item.setValidFlippingPanelItem(true);
            }

            item.updateHistory(newOffer);
            item.updateLatestProperties(newOffer);
        } else {
            addToTradesList(trades, newOffer);
        }
    }

    /**
     * Constructs a FlippingItem, the data structure that represents an item the user is currently flipping, and
     * adds it to the given trades list. This method is invoked when we receive an offer event for an item that isn't
     * currently present in the trades list.
     *
     * @param tradesList the trades list to be updated
     * @param newOffer   the offer to update the trade list with
     */
    private void addToTradesList(List<FlippingItem> tradesList, OfferEvent newOffer) {
        int tradeItemId = newOffer.getItemId();
        String itemName = plugin.getItemManager().getItemComposition(tradeItemId).getName();

        ItemStats itemStats = plugin.getItemManager().getItemStats(tradeItemId, false);
        int geLimit = itemStats != null ? itemStats.getGeLimit() : 0;

        FlippingItem flippingItem = new FlippingItem(tradeItemId, itemName, geLimit, plugin.getCurrentlyLoggedInAccount());
        flippingItem.setValidFlippingPanelItem(true);
        flippingItem.updateHistory(newOffer);
        flippingItem.updateLatestProperties(newOffer);

        tradesList.add(0, flippingItem);
    }
}
