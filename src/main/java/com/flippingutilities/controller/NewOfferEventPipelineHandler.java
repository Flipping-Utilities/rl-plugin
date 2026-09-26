package com.flippingutilities.controller;

import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.ui.widgets.SlotActivityTimer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.WorldType;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemStats;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.util.Objects;

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
        EnumSet<WorldType> excludedWorldTypes = EnumSet.of(WorldType.SEASONAL);
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

        // Screening replaces lastOffers; retain the known predecessor for history updates.
        OfferEvent previousOffer = plugin.getDataHandler().getAccountData(currentlyLoggedInAccount)
            .getLastOffers().get(newOfferEvent.getSlot());
        Optional<OfferEvent> screenedOfferEvent = screenOfferEvent(newOfferEvent);

        if (!screenedOfferEvent.isPresent()) {
            return;
        }

        OfferEvent finalizedOfferEvent = screenedOfferEvent.get();
        
        List<FlippingItem> currentlyLoggedInAccountsTrades = plugin.getDataHandler().getAccountData(currentlyLoggedInAccount).getTrades();

        Optional<FlippingItem> flippingItem = currentlyLoggedInAccountsTrades.stream().filter(item -> item.getItemId() == finalizedOfferEvent.getItemId()).findFirst();

        List<String> replacedUuids = updateTradesList(currentlyLoggedInAccountsTrades, flippingItem, finalizedOfferEvent.clone(), previousOffer);

        // Persist exactly the history replacement made above, including late cancellation
        // corrections. Slot state, replacement, and the new snapshot commit together.
        OfferEvent snapshot = finalizedOfferEvent.clone();
        plugin.submitStorageTask(storage -> storage.recordOfferUpdate(currentlyLoggedInAccount, snapshot, replacedUuids));

        // Keep the persisted GE limit state in sync (only buys change it)
        persistGeLimitState(currentlyLoggedInAccount, finalizedOfferEvent);

        plugin.setUpdateSinceLastItemAccountWideBuild(true);

        rebuildDisplayAfterOfferEvent(finalizedOfferEvent);
    }

    /**
     * There is no point rebuilding either the stats panel or flipping panel when the user is looking at the trades list of
     * one of their accounts that isn't logged in as that trades list won't be being updated anyway.
     *
     * @param offerEvent   offer event just received
     */
    private void rebuildDisplayAfterOfferEvent(OfferEvent offerEvent) {

        if (!plugin.isAccountInCurrentView(plugin.getCurrentlyLoggedInAccount())) {
            return;
        }

        plugin.getFlippingPanel().onNewOfferEventRebuild(offerEvent);
        plugin.getStatPanel().rebuildItemsDisplay(plugin.viewItemsForCurrentView());
    }

    /**
     * Every single OfferEvent passes through this method for screening before being sent to the wider system because
     * offer updates have strange quirks such as duplicates, empty updates, etc.
     *
     * For example, every empty/buy/sell/cancelled buy/cancelled sell
     * spawns two identical events. And when you fully buy/sell item, it spawns two events (a
     * buying/selling event and a bought/sold event). This method screens out the unwanted events/duplicate
     * events and sets the ticksSinceFirstOffer field correctly on new OfferEvents. For detailed documentation see
     * the "Documenting RL events" section in the README.
     *
     * If some component needs access to OfferEvents prior to screening or at some point in the screening prior to
     * completion or in some way needs to benefit from the internal logic of this method, then we can pass the OfferEvent
     * to that component in this method itself. We currently do this with the slotsPanel and slotActivityTimer.
     *
     * @param newOfferEvent event that just occurred
     * @return an optional containing an OfferEvent.
     */
    public Optional<OfferEvent> screenOfferEvent(OfferEvent newOfferEvent) {
        plugin.getSlotsPanel().update(newOfferEvent);

        Map<Integer, OfferEvent> lastOfferEventForEachSlot = plugin.getDataHandler().getAccountData(plugin.getCurrentlyLoggedInAccount()).getLastOffers();
        List<SlotActivityTimer> slotActivityTimers = plugin.getDataHandler().getAccountData(plugin.getCurrentlyLoggedInAccount()).getSlotTimers();
        OfferEvent lastOfferEvent = lastOfferEventForEachSlot.get(newOfferEvent.getSlot());

        //completely useless updates
        if (newOfferEvent.isCausedByEmptySlot() && newOfferEvent.isBeforeLogin()) {
            return Optional.empty();
        }

        //is null when an offer was cleared or perhaps the slot in game has an offer but that offer was made
        //outside the plugin, so the lastOfferEvent for that slot is still null.
        if (lastOfferEvent == null) {
            //don't think newOfferEvent can be caused by an empty slot at this point but i'm leaving this old code
            //here in case i'm overlooking something that past self caught...
            if (!newOfferEvent.isCausedByEmptySlot()) {
                lastOfferEventForEachSlot.put(newOfferEvent.getSlot(), newOfferEvent);
                slotActivityTimers.get(newOfferEvent.getSlot()).setCurrentOffer(newOfferEvent);
                //set the start time BEFORE persisting so the active_slots row carries it and
                //the slot timer can be restored after a restart
                if (newOfferEvent.isStartOfOffer()) {
                    newOfferEvent.setTradeStartedAt(Instant.now());
                }
                persistSlotState(plugin.getCurrentlyLoggedInAccount(), newOfferEvent.getSlot(), newOfferEvent, false);
            }

            return Optional.empty();
        }

        //we get essentially every offer event twice..
        if (lastOfferEvent.isDuplicate(newOfferEvent)) {
            return Optional.empty();
        }

        //because we took care of the empty slot updates on login in a previous clause, this
        //will only trigger on empty slot updates when an offer is collected
        if (newOfferEvent.isCausedByEmptySlot()) {
            OfferEvent retained = plugin.getDataHandler().getAccountData(plugin.getCurrentlyLoggedInAccount()).getTrades().stream()
                .filter(item -> item.getItemId() == lastOfferEvent.getItemId())
                .flatMap(item -> item.getHistory().getCompressedOfferEvents().stream())
                .filter(offer -> Objects.equals(offer.getUuid(), lastOfferEvent.getUuid()))
                .findFirst().orElse(null);
            // A collected slot is terminal, including a partial fill delivered just after
            // cancellation. Finalize it so the next trade in this slot cannot replace it.
            if (retained != null && !retained.isComplete()) {
                retained.setState(retained.isBuy() ? GrandExchangeOfferState.CANCELLED_BUY
                    : GrandExchangeOfferState.CANCELLED_SELL);
            }
            OfferEvent archived = retained == null ? null : retained.clone();
            lastOfferEventForEachSlot.remove(newOfferEvent.getSlot());
            slotActivityTimers.get(newOfferEvent.getSlot()).reset();
            String account = plugin.getCurrentlyLoggedInAccount();
            plugin.submitStorageTask(storage -> storage.archiveOfferAndClearSlot(account, newOfferEvent.getSlot(), archived));
            return Optional.empty();
        }

        if (newOfferEvent.isRedundantEventBeforeOfferCompletion()) {
            return Optional.empty();
        }

        newOfferEvent.setTicksSinceFirstOffer(lastOfferEvent);
        newOfferEvent.setTradeStartedAt(lastOfferEvent.getTradeStartedAt());
        lastOfferEventForEachSlot.put(newOfferEvent.getSlot(), newOfferEvent);
        slotActivityTimers.get(newOfferEvent.getSlot()).setCurrentOffer(newOfferEvent);
        if (newOfferEvent.getCurrentQuantityInTrade() == 0) {
            persistSlotState(plugin.getCurrentlyLoggedInAccount(), newOfferEvent.getSlot(), newOfferEvent, false);
        }
        return newOfferEvent.getCurrentQuantityInTrade() ==0? Optional.empty() : Optional.of(newOfferEvent);
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
    private List<String> updateTradesList(List<FlippingItem> trades, Optional<FlippingItem> flippingItem,
                                          OfferEvent newOffer, OfferEvent previousOffer) {
        if (flippingItem.isPresent()) {
            FlippingItem item = flippingItem.get();

            //if a user buys/sells an item they previously deleted from the flipping panel, show the panel again.
            if (!item.getValidFlippingPanelItem()) {
                item.setValidFlippingPanelItem(true);
            }

            List<String> removedUuids = item.updateHistory(newOffer, previousOffer);
            item.updateLatestProperties(newOffer);
            return removedUuids;
        } else {
            addToTradesList(trades, newOffer);
            return Collections.emptyList();
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

        ItemStats itemStats = plugin.getItemManager().getItemStats(tradeItemId);
        int geLimit = itemStats != null ? itemStats.getGeLimit() : 0;

        FlippingItem flippingItem = new FlippingItem(tradeItemId, itemName, geLimit, plugin.getCurrentlyLoggedInAccount());
        flippingItem.setValidFlippingPanelItem(true);
        flippingItem.updateHistory(newOffer);
        flippingItem.updateLatestProperties(newOffer);

        tradesList.add(0, flippingItem);
    }

    /**
     * Persists the in-progress offer state of a slot (or clears it when the slot empties) to
     * SQLite when enabled, so active offers survive restarts. The write is queued on the
     * storage executor to keep DB I/O off the client thread.
     */
    private void persistSlotState(String account, int slotIndex, OfferEvent offer, boolean historyVisible) {
        if (account == null || plugin.getSqliteStorage() == null) {
            return;
        }
        OfferEvent snapshot = offer == null ? null : offer.clone();
        plugin.submitStorageTask(storage -> storage.upsertSlot(account, slotIndex, snapshot, historyVisible));
    }

    /**
     * Persists the GE limit state for the offer's item to SQLite when enabled. Only buys change
     * GE limit state, so sells are skipped. The state is read from memory on the client thread
     * and only the DB write is queued on the executor. Best-effort.
     */
    private void persistGeLimitState(String account, OfferEvent offer) {
        try {
            if (account == null || plugin.getSqliteStorage() == null || !offer.isBuy()) {
                return;
            }
            Instant resetTime = null;
            int itemsBought = 0;
            int itemsBoughtThroughComplete = 0;
            int itemId = -1;
            Optional<FlippingItem> item = plugin.getDataHandler().getAccountData(account).getTrades().stream()
                .filter(tradeItem -> tradeItem.getItemId() == offer.getItemId())
                .findFirst();
            if (item.isPresent()) {
                resetTime = item.get().getGeLimitResetTime();
                itemsBought = item.get().getItemsBoughtThisLimitWindow();
                itemsBoughtThroughComplete = item.get().getHistory().getItemsBoughtThroughCompleteOffers();
                itemId = item.get().getItemId();
            }
            if (resetTime == null || itemId < 0) {
                return;
            }

            final String accountName = account;
            final int finalItemId = itemId;
            final Instant finalResetTime = resetTime;
            final int finalItemsBought = itemsBought;
            final int finalItemsBoughtThroughComplete = itemsBoughtThroughComplete;
            plugin.submitStorageTask(storage -> storage.upsertGeLimitState(
                accountName, finalItemId, finalResetTime, finalItemsBought, finalItemsBoughtThroughComplete));
        } catch (Exception e) {
            log.debug("Failed to queue GE limit state persistence (best-effort): {}", e.getMessage());
        }
    }
}
