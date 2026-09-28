package com.flippingutilities.controller;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class FlippingItemHandlerTest {
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");
    private final FlippingItemHandler handler = new FlippingItemHandler(null);

    @Test
    public void aggregatePreservesMetadataFavoriteAndStableTimestampTiesWithoutAliasingSources() {
        FlippingItem first = item("first", 1, true);
        FlippingItem second = item("second", 2, false);
        FlippingItem newest = item("newest", 3, false);
        FlippingItem tied = item("tied", 3, false);
        newest.setFavoriteCode("winner-code");
        newest.setValidFlippingPanelItem(false);
        newest.getHistory().setItemsBoughtThisLimitWindow(15);
        FlippingItem other = new FlippingItem(4587, "Other", 10, "other");
        other.updateLatestProperties(offer("other", "other", TIME.plusSeconds(4)));
        AccountData lastAccount = account(tied);
        lastAccount.getTrades().add(other);

        List<FlippingItem> result = handler.createAccountWideFlippingItemList(Arrays.asList(
            account(first), account(second), account(newest), lastAccount));

        assertEquals(4587, result.get(0).getItemId());
        FlippingItem aggregate = result.get(1);
        assertEquals("newest", aggregate.getFlippedBy());
        assertEquals("newest item", aggregate.getItemName());
        assertEquals("winner-code", aggregate.getFavoriteCode());
        assertTrue(aggregate.isFavorite());
        assertEquals(Boolean.FALSE, aggregate.getValidFlippingPanelItem());
        assertEquals(15, aggregate.getHistory().getItemsBoughtThisLimitWindow());
        assertEquals(Arrays.asList("newest-tie", "second-tie", "first-tie", "tied-tie",
            "first-latest", "second-latest", "newest-latest", "tied-latest"), ids(aggregate));
        assertEquals(Arrays.asList("first-tie", "first-latest"), ids(first));
        assertEquals(Arrays.asList("newest-tie", "newest-latest"), ids(newest));
        aggregate.getHistory().getCompressedOfferEvents().get(0).setPrice(999);
        aggregate.getHistory().getCompressedOfferEvents().remove(1);
        assertEquals(10L, newest.getHistory().getCompressedOfferEvents().get(0).getPreTaxPrice());
        assertEquals(2, second.getHistory().getCompressedOfferEvents().size());
    }

    @Test
    public void oneAccountRetainsItsHistoryOrderAndReturnsIndependentOffers() {
        FlippingItem source = item("source", 1, false);
        Collections.reverse(source.getHistory().getCompressedOfferEvents());
        FlippingItem aggregate = handler.createAccountWideFlippingItemList(
            Collections.singletonList(account(source))).get(0);
        assertEquals(ids(source), ids(aggregate));
        assertNotSame(source, aggregate);
        assertNotSame(source.getHistory().getCompressedOfferEvents().get(0),
            aggregate.getHistory().getCompressedOfferEvents().get(0));
        assertTrue(handler.createAccountWideFlippingItemList(Collections.emptyList()).isEmpty());
    }

    private FlippingItem item(String account, int latestOffset, boolean favorite) {
        FlippingItem item = new FlippingItem(4151, account + " item", 70, account);
        item.setFavorite(favorite);
        OfferEvent first = offer(account + "-tie", account, TIME);
        OfferEvent latest = offer(account + "-latest", account, TIME.plusSeconds(latestOffset));
        item.getHistory().getCompressedOfferEvents().addAll(Arrays.asList(first, latest));
        item.updateLatestProperties(latest);
        return item;
    }

    private OfferEvent offer(String id, String account, Instant time) {
        return new OfferEvent(id, true, 4151, 10, 10L, time, 0, GrandExchangeOfferState.BOUGHT,
            10, 10, 10, time, false, account, null, 0L, 0L);
    }

    private AccountData account(FlippingItem item) {
        AccountData account = new AccountData();
        account.getTrades().add(item);
        return account;
    }

    private List<String> ids(FlippingItem item) {
        return item.getHistory().getCompressedOfferEvents().stream().map(OfferEvent::getUuid).collect(Collectors.toList());
    }
}
