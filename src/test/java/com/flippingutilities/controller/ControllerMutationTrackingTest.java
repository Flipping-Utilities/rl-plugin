package com.flippingutilities.controller;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.AccountWideData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.utilities.User;
import com.google.gson.Gson;
import net.runelite.api.GrandExchangeOfferState;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;

public class ControllerMutationTrackingTest {
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    public void existingFavoriteAndTruncationAreMarkedAfterTheMutation() {
        StubPlugin plugin = new StubPlugin();
        FlippingItem item = item("First");
        plugin.data.accounts.put("First", account(item));
        new FavoriteHandler(plugin).addFavoritedItem(item);
        assertTrue(plugin.data.favoritesAtMark.get("First"));

        item.setValidFlippingPanelItem(false);
        new TradeHistoryHandler(plugin).truncateTradeList();
        assertEquals(Integer.valueOf(0), plugin.data.itemCountsAtMark.get("First"));
    }

    @Test
    public void accountWideIntervalDeletionMarksEachRealAccountAfterHistoryChanges() {
        StubPlugin plugin = new StubPlugin();
        for (String name : new String[]{"First", "Second"}) {
            FlippingItem item = item(name);
            item.getHistory().getCompressedOfferEvents().add(offer(name + "-before", TIME.minusSeconds(1)));
            item.getHistory().getCompressedOfferEvents().add(offer(name + "-after", TIME.plusSeconds(1)));
            plugin.data.accounts.put(name, account(item));
        }
        try {
            new TradeHistoryHandler(plugin).deleteOffers(TIME);
            assertEquals(plugin.data.accounts.keySet(), plugin.data.historyAtMark.keySet());
            assertEquals(Collections.singletonList("First-before"), plugin.data.historyAtMark.get("First"));
            assertEquals(Collections.singletonList("Second-before"), plugin.data.historyAtMark.get("Second"));
        } finally {
            plugin.httpClient.dispatcher().executorService().shutdownNow();
            plugin.httpClient.connectionPool().evictAll();
        }
    }

    @Test
    public void successfulAuthenticationMarksTheNewTokenRatherThanTheOldOne() {
        StubPlugin plugin = new StubPlugin();
        ApiAuthHandler auth = new ApiAuthHandler(plugin);
        auth.loginWithToken("test-token").join();
        assertEquals("new-jwt", plugin.data.jwtAtMark);
    }

    private FlippingItem item(String name) {
        FlippingItem item = new FlippingItem(4151, "Whip", 70, name);
        item.setValidFlippingPanelItem(true);
        return item;
    }

    private AccountData account(FlippingItem item) {
        AccountData data = new AccountData();
        data.getTrades().add(item);
        return data;
    }

    private OfferEvent offer(String uuid, Instant time) {
        return new OfferEvent(uuid, true, 4151, 1, 100L, time, -1, GrandExchangeOfferState.BOUGHT,
            0, 0, 1, time, false, null, null, 0L, 0L);
    }

    private static final class StubPlugin extends FlippingPlugin {
        private final TrackingDataHandler data = new TrackingDataHandler(this);
        private final OkHttpClient httpClient = new OkHttpClient.Builder().addInterceptor(chain ->
            new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(MediaType.get("application/json"), "[]")).build()).build();
        private final ApiRequestHandler requests = new ApiRequestHandler(this) {
            @Override public CompletableFuture<String> loginWithToken(String token) {
                return CompletableFuture.completedFuture("new-jwt");
            }
            @Override public CompletableFuture<User> getUser() { return CompletableFuture.completedFuture(new User()); }
        };
        private RecipeHandler recipes;
        @Override public DataHandler getDataHandler() { return data; }
        @Override public OkHttpClient getHttpClient() { return httpClient; }
        @Override public ApiRequestHandler getApiRequestHandler() { return requests; }
        @Override public List<String> getAccountNamesForCurrentView() { return new ArrayList<>(data.accounts.keySet()); }
        @Override public String getAccountCurrentlyViewed() { return ACCOUNT_WIDE; }
        @Override public String getCurrentlyLoggedInAccount() { return null; }
        @Override public FlippingItemHandler getFlippingItemHandler() { return new FlippingItemHandler(this); }
        @Override public void markAccountTradesAsHavingChanged(String account) { data.markDataAsHavingChanged(account); }
        @Override Collection<AccountData> getAccountsForCurrentView() {
            data.accounts.keySet().forEach(data::markDataAsHavingChanged);
            return data.accounts.values();
        }
        @Override public RecipeHandler getRecipeHandler() {
            if (recipes == null) recipes = new RecipeHandler(new Gson(), httpClient, Collections.emptyList());
            return recipes;
        }
    }

    private static final class TrackingDataHandler extends DataHandler {
        private final Map<String, AccountData> accounts = new LinkedHashMap<>();
        private final Map<String, Boolean> favoritesAtMark = new LinkedHashMap<>();
        private final Map<String, Integer> itemCountsAtMark = new LinkedHashMap<>();
        private final Map<String, List<String>> historyAtMark = new LinkedHashMap<>();
        private final AccountWideData wide = new AccountWideData();
        private String jwtAtMark;
        TrackingDataHandler(FlippingPlugin plugin) { super(plugin); }
        @Override public AccountData viewAccountData(String account) { return accounts.get(account); }
        @Override public AccountData getAccountData(String account) {
            markDataAsHavingChanged(account);
            return accounts.get(account);
        }
        @Override public AccountWideData viewAccountWideData() { return wide; }
        @Override public AccountWideData getAccountWideData() {
            markDataAsHavingChanged(FlippingPlugin.ACCOUNT_WIDE);
            return wide;
        }
        @Override public void markDataAsHavingChanged(String name) {
            if (FlippingPlugin.ACCOUNT_WIDE.equals(name)) {
                jwtAtMark = wide.getJwt();
                return;
            }
            AccountData account = accounts.get(name);
            itemCountsAtMark.put(name, account.getTrades().size());
            favoritesAtMark.put(name, account.getTrades().stream().anyMatch(FlippingItem::isFavorite));
            List<String> ids = new ArrayList<>();
            account.getTrades().forEach(item -> item.getHistory().getCompressedOfferEvents().forEach(offer -> ids.add(offer.getUuid())));
            historyAtMark.put(name, ids);
        }
    }
}
