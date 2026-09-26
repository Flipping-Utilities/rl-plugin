package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.google.gson.Gson;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static com.flippingutilities.db.StorageTestOffers.complete;
import static org.junit.Assert.*;

public class FavoriteMigrationTest {
    private static final String ACCOUNT = "Favorite account";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void unfavoritedCustomCodesSurviveMigrationAndRefavoriting() throws Exception {
        AccountData source = new AccountData();
        FlippingItem searchedItem = item(4151, "whip");
        FlippingItem tradedItem = item(4587, "scim");
        tradedItem.getHistory().getCompressedOfferEvents().add(complete(ACCOUNT, 4587, "trade",
            Instant.parse("2026-09-25T12:00:00Z").toEpochMilli(), 1, 100, true));
        searchedItem.setFavorite(true);
        tradedItem.setFavorite(true);
        // The favorite toggle keeps codes available for future use.
        searchedItem.setFavorite(false);
        tradedItem.setFavorite(false);
        source.getTrades().addAll(Arrays.asList(searchedItem, tradedItem, item(11802, "1"), item(11804, null)));

        SqliteStorage storage = new SqliteStorage(folder.newFile("favorites.db"));
        try {
            assertEquals(1, new MigrationService(storage, new TradePersister(new Gson()))
                .migrate(Collections.singletonMap(ACCOUNT, source)));
            storage.close();
            Map<Integer, Map<String, Object>> favoriteSettings = storage.loadAllFavorites(ACCOUNT);
            assertEquals("Unfavorited default/null codes must not create favorite rows", 2, favoriteSettings.size());
            AccountData loaded = storage.loadAccount(ACCOUNT);
            for (FlippingItem original : Arrays.asList(searchedItem, tradedItem)) {
                FlippingItem restored = findItem(loaded, original.getItemId());
                assertFalse("Restoring a code must not favorite the item", restored.isFavorite());
                assertEquals(original.getFavoriteCode(), restored.getFavoriteCode());
                restored.setFavorite(true);
                storage.upsertFavorite(ACCOUNT, restored.getItemId(), restored.isFavorite(), restored.getFavoriteCode());
            }

            storage.close();
            AccountData refavorited = storage.loadAccount(ACCOUNT);
            for (FlippingItem original : Arrays.asList(searchedItem, tradedItem)) {
                FlippingItem restored = findItem(refavorited, original.getItemId());
                assertTrue(restored.isFavorite());
                assertEquals("Refavoriting must reuse the previous search code",
                    original.getFavoriteCode(), restored.getFavoriteCode());
            }
        } finally {
            storage.close();
        }
    }

    private FlippingItem item(int id, String code) {
        FlippingItem item = new FlippingItem(id, "Item " + id, 70, ACCOUNT);
        item.setFavoriteCode(code);
        return item;
    }

    private FlippingItem findItem(AccountData account, int id) {
        return account.getTrades().stream().filter(item -> item.getItemId() == id).findFirst().get();
    }
}
