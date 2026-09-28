package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.RecipeFlipGroup;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

import static com.flippingutilities.db.StorageTestOffers.complete;
import static org.junit.Assert.*;

/** Synthetic history exercises full in-memory loading without depending on private account files. */
public class LargeAccountLoadTest {
    private static final String ACCOUNT = "Large account";
    private static final int TRADES = 50_000;
    private static final int ITEMS = 100;
    private static final int RECIPES = 200;
    private static final int GROUPS = 10;
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void largeAccountRestoresAllTradesAndRecipesThenAppendsOnlyItsNewTrade() throws Exception {
        AccountData source = fixture();
        Gson gson = new Gson();
        JsonStorageCodec codec = new JsonStorageCodec(gson);
        Path directory = folder.newFolder().toPath();
        JsonJournalStore store = new JsonJournalStore(directory, gson);
        Map<String, JsonElement> initial = codec.encodeAccount(ACCOUNT, source);
        store.initialize(initial);
        AccountData restored = codec.decodeAccount(ACCOUNT, new JsonJournalStore(directory, gson).load());

        assertEquals(ITEMS, restored.getTrades().size());
        assertEquals(TRADES, tradeCount(restored));
        assertEquals(GROUPS, restored.getRecipeFlipGroups().size());
        assertEquals(RECIPES, restored.getRecipeFlipGroups().stream().mapToInt(group -> group.getRecipeFlips().size()).sum());
        assertEquals(recipeProfit(source), recipeProfit(restored));
        for (int i = 0; i < ITEMS; i++) {
            assertEquals(source.getTrades().get(i).getHistory().getCompressedOfferEvents(),
                restored.getTrades().get(i).getHistory().getCompressedOfferEvents());
        }

        OfferEvent newTrade = complete(ACCOUNT, 10_000, "new-trade", TIME.plusSeconds(TRADES + 1).toEpochMilli(), 3, 1234, true);
        restored.getTrades().get(0).getHistory().getCompressedOfferEvents().add(newTrade);
        store.commit(initial, codec.encodeAccount(ACCOUNT, restored));
        assertTrue("A new trade must not rewrite a 50,000-trade history", Files.size(directory.resolve("journal.jsonl")) < 2048);
        AccountData reopened = codec.decodeAccount(ACCOUNT, new JsonJournalStore(directory, gson).load());
        assertEquals(TRADES + 1, tradeCount(reopened));
        assertEquals(newTrade, reopened.getTrades().get(0).getHistory().getCompressedOfferEvents().get(TRADES / ITEMS));
        assertEquals(recipeProfit(source), recipeProfit(reopened));
    }

    private AccountData fixture() {
        AccountData account = new AccountData();
        for (int i = 0; i < ITEMS; i++) {
            account.getTrades().add(new FlippingItem(10_000 + i, "Item " + i, 70, ACCOUNT));
        }
        for (int i = 0; i < TRADES; i++) {
            FlippingItem item = account.getTrades().get(i % ITEMS);
            item.getHistory().getCompressedOfferEvents().add(complete(ACCOUNT, item.getItemId(), "trade-" + i,
                TIME.plusSeconds(i).toEpochMilli(), 1 + i % 20, 10_000 + i % 1000, i % 2 == 0));
        }
        for (int i = 0; i < GROUPS; i++) account.getRecipeFlipGroups().add(new RecipeFlipGroup("group-" + i));
        for (int i = 0; i < RECIPES; i++) {
            OfferEvent input = account.getTrades().get(0).getHistory().getCompressedOfferEvents().get(i);
            OfferEvent output = account.getTrades().get(1).getHistory().getCompressedOfferEvents().get(i);
            RecipeFlip flip = new RecipeFlip(TIME.plusSeconds(i),
                Collections.singletonMap(output.getItemId(), Collections.singletonMap(output.getUuid(), new PartialOffer(output, 1))),
                Collections.singletonMap(input.getItemId(), Collections.singletonMap(input.getUuid(), new PartialOffer(input, 1))), 100);
            account.getRecipeFlipGroups().get(i % GROUPS).addRecipeFlip(flip);
        }
        return account;
    }

    private int tradeCount(AccountData account) {
        return account.getTrades().stream().mapToInt(item -> item.getHistory().getCompressedOfferEvents().size()).sum();
    }

    private long recipeProfit(AccountData account) {
        return account.getRecipeFlipGroups().stream().flatMap(group -> group.getRecipeFlips().stream())
            .mapToLong(RecipeFlip::getProfit).sum();
    }
}
