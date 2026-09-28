package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.AccountWideData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.RecipeFlipGroup;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class JsonStorageTest {
    private static final String ALICE = "Alice";
    private static final String BOB = "Bob";
    private static final Instant TIME = Instant.parse("2026-09-28T12:00:00Z");
    private final Gson gson = new Gson();
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void migratesLegacyFilesOnceAndPreservesIndependentSlotAndRecipeSnapshots() throws Exception {
        File directory = temporaryFolder.newFolder();
        AccountData legacy = account(ALICE, 100);
        OfferEvent active = history(legacy).clone();
        active.setCurrentQuantityInTrade(7);
        active.setState(GrandExchangeOfferState.BUYING);
        active.setTradeStartedAt(TIME.minusSeconds(30));
        legacy.getLastOffers().put(2, active);
        OfferEvent recipeOffer = history(legacy).clone();
        recipeOffer.setPrice(40L);
        Map<Integer, Map<String, PartialOffer>> inputs = new LinkedHashMap<>();
        inputs.put(4151, Collections.singletonMap(recipeOffer.getUuid(), new PartialOffer(recipeOffer, 3)));
        RecipeFlipGroup group = new RecipeFlipGroup("recipe/key");
        group.getRecipeFlips().add(new RecipeFlip(TIME, Collections.emptyMap(), inputs, 5));
        legacy.getRecipeFlipGroups().add(group);
        AccountWideData wide = new AccountWideData();
        wide.setJwt("test-token");
        TradePersister persister = new TradePersister(gson, directory);
        LegacyJsonFixtures.write(persister, ALICE, legacy);
        LegacyJsonFixtures.write(persister, "accountwide", wide);
        Path legacyFile = directory.toPath().resolve(ALICE + ".json");
        Path wideFile = directory.toPath().resolve("accountwide.json");
        byte[] original = Files.readAllBytes(legacyFile);
        byte[] originalWide = Files.readAllBytes(wideFile);

        JsonStorage storage = new JsonStorage(gson, directory);
        JsonStorage.LoadedData loaded = storage.load();
        AccountData actual = loaded.getAccounts().get(ALICE);
        assertEquals(100, history(actual).getPreTaxPrice());
        assertEquals(7, actual.getLastOffers().get(2).getCurrentQuantityInTrade());
        assertEquals(GrandExchangeOfferState.BUYING, actual.getLastOffers().get(2).getState());
        assertEquals(active.getTradeStartedAt(), actual.getLastOffers().get(2).getTradeStartedAt());
        assertEquals(40, actual.getRecipeFlipGroups().get(0).getRecipeFlips().get(0)
            .getInputs().get(4151).get(recipeOffer.getUuid()).getOffer().getPreTaxPrice());
        assertEquals("test-token", loaded.getAccountWideData().getJwt());
        assertArrayEquals(original, Files.readAllBytes(legacyFile));
        assertArrayEquals(originalWide, Files.readAllBytes(wideFile));
        assertTrue(Files.exists(storage.getStorageDirectory().resolve("checkpoint.json")));

        // Once imported, an old client rewriting legacy JSON cannot replace journal data.
        LegacyJsonFixtures.write(persister, ALICE, account(ALICE, 900));
        assertEquals(100, history(new JsonStorage(gson, directory).load().getAccounts().get(ALICE)).getPreTaxPrice());
    }

    @Test
    public void commitsAccountsAndSettingsInOneTransaction() throws Exception {
        File directory = temporaryFolder.newFolder();
        JsonStorage storage = new JsonStorage(gson, directory);
        storage.load();
        AccountWideData settings = new AccountWideData();
        settings.setEnhancedSlots(false);
        Map<String, AccountData> dirty = new LinkedHashMap<>();
        dirty.put(ALICE, account(ALICE, 111));
        dirty.put(BOB, account(BOB, 222));

        storage.commit(storage.capture(dirty, settings, Collections.emptySet()));

        assertEquals(1, Files.readAllLines(storage.getStorageDirectory().resolve("journal.jsonl")).size());
        JsonStorage.LoadedData loaded = new JsonStorage(gson, directory).load();
        assertEquals(2, loaded.getAccounts().size());
        assertEquals(111, history(loaded.getAccounts().get(ALICE)).getPreTaxPrice());
        assertEquals(222, history(loaded.getAccounts().get(BOB)).getPreTaxPrice());
        assertFalse(loaded.getAccountWideData().isEnhancedSlots());
    }

    @Test
    public void captureDoesNotRetainMutableModelReferences() throws Exception {
        File directory = temporaryFolder.newFolder();
        JsonStorage storage = new JsonStorage(gson, directory);
        storage.load();
        AccountData account = account(ALICE, 100);
        AccountWideData wide = new AccountWideData();
        wide.setJwt("captured");
        JsonStorage.CapturedSave captured = storage.capture(Collections.singletonMap(ALICE, account),
            wide, Collections.emptySet());

        history(account).setPrice(999L);
        account.getTrades().get(0).setFavorite(true);
        wide.setJwt("later");
        storage.commit(captured);

        JsonStorage.LoadedData disk = new JsonStorage(gson, directory).load();
        assertEquals(100, history(disk.getAccounts().get(ALICE)).getPreTaxPrice());
        assertFalse(disk.getAccounts().get(ALICE).getTrades().get(0).isFavorite());
        assertEquals("captured", disk.getAccountWideData().getJwt());
        storage.commit(storage.capture(Collections.singletonMap(ALICE, account), wide, Collections.emptySet()));
        assertEquals(999, history(new JsonStorage(gson, directory).load().getAccounts().get(ALICE)).getPreTaxPrice());
    }

    @Test
    public void savingAnotherAccountDoesNotAdoptUnseenRemoteChanges() throws Exception {
        File directory = seededDirectory();
        JsonStorage first = new JsonStorage(gson, directory);
        JsonStorage second = new JsonStorage(gson, directory);
        JsonStorage.LoadedData firstView = first.load();
        JsonStorage.LoadedData secondView = second.load();
        AccountData remoteAlice = secondView.getAccounts().get(ALICE);
        history(remoteAlice).setPrice(200L);
        second.commit(second.capture(Collections.singletonMap(ALICE, remoteAlice), null, Collections.emptySet()));

        AccountData localBob = firstView.getAccounts().get(BOB);
        history(localBob).setPrice(300L);
        first.commit(first.capture(Collections.singletonMap(BOB, localBob), null, Collections.emptySet()));
        assertTrue(first.accountNames().contains(ALICE));
        first.loadAccount(BOB);
        first.loadAccountWideData();

        AccountData staleAlice = firstView.getAccounts().get(ALICE);
        history(staleAlice).setPrice(400L);
        expectConflict(first, staleAlice);
        JsonStorage.LoadedData disk = new JsonStorage(gson, directory).load();
        assertEquals(200, history(disk.getAccounts().get(ALICE)).getPreTaxPrice());
        assertEquals(300, history(disk.getAccounts().get(BOB)).getPreTaxPrice());
    }

    @Test
    public void explicitlyReloadingAnAccountAdoptsOnlyItsLatestRevision() throws Exception {
        File directory = seededDirectory();
        JsonStorage first = new JsonStorage(gson, directory);
        JsonStorage second = new JsonStorage(gson, directory);
        first.load();
        AccountData remote = second.load().getAccounts().get(ALICE);
        history(remote).setPrice(200L);
        second.commit(second.capture(Collections.singletonMap(ALICE, remote), null, Collections.emptySet()));

        AccountData refreshed = first.loadAccount(ALICE);
        assertEquals(200, history(refreshed).getPreTaxPrice());
        history(refreshed).setPrice(300L);
        first.commit(first.capture(Collections.singletonMap(ALICE, refreshed), null, Collections.emptySet()));
        assertEquals(300, history(new JsonStorage(gson, directory).load().getAccounts().get(ALICE)).getPreTaxPrice());
    }

    @Test
    public void observingUpdatesDoesNotAdoptDirtyAccountsAndAcceptanceUsesTheObservedSnapshot() throws Exception {
        File directory = seededDirectory();
        JsonStorage first = new JsonStorage(gson, directory);
        JsonStorage second = new JsonStorage(gson, directory);
        AccountData stale = first.load().getAccounts().get(ALICE);
        AccountData remote = second.load().getAccounts().get(ALICE);
        history(remote).setPrice(200L);
        second.commit(second.capture(Collections.singletonMap(ALICE, remote), null, Collections.emptySet()));

        JsonStorage.Updates updates = first.readUpdates();
        assertEquals(Collections.singleton(ALICE), updates.getAccounts().keySet());
        assertNull(updates.getAccountWideData());
        assertTrue(updates.getDeletedAccounts().isEmpty());
        history(stale).setPrice(300L);
        expectConflict(first, stale);

        history(remote).setPrice(400L);
        second.commit(second.capture(Collections.singletonMap(ALICE, remote), null, Collections.emptySet()));
        first.adopt(updates, Collections.singleton(ALICE), false);
        AccountData acceptedOlderSnapshot = updates.getAccounts().get(ALICE);
        history(acceptedOlderSnapshot).setPrice(500L);
        expectConflict(first, acceptedOlderSnapshot);
        JsonStorage.Updates latest = first.readUpdates();
        assertEquals(400, history(latest.getAccounts().get(ALICE)).getPreTaxPrice());
        first.adopt(latest, Collections.singleton(ALICE), false);
        assertTrue(first.readUpdates().getAccounts().isEmpty());
    }

    @Test
    public void acceptsRemoteDeletionAndAccountWideSettingsIndependently() throws Exception {
        File directory = seededDirectory();
        JsonStorage first = new JsonStorage(gson, directory);
        JsonStorage second = new JsonStorage(gson, directory);
        first.load();
        AccountWideData wide = second.load().getAccountWideData();
        wide.setEnhancedSlots(false);
        second.commit(second.capture(Collections.emptyMap(), wide, Collections.singleton(ALICE)));

        JsonStorage.Updates observed = first.readUpdates();
        assertEquals(Collections.singleton(ALICE), observed.getDeletedAccounts());
        assertFalse(observed.getAccountWideData().isEnhancedSlots());
        first.adopt(observed, Collections.singleton(ALICE), false);
        assertTrue(first.readUpdates().getDeletedAccounts().isEmpty());
        assertNotNull(first.readUpdates().getAccountWideData());
        first.adopt(observed, Collections.emptySet(), true);
        assertNull(first.readUpdates().getAccountWideData());
    }

    @Test
    public void sessionOnlyCapturePreservesHistoryAndFullCaptureTakesPrecedence() throws Exception {
        File directory = seededDirectory();
        JsonStorage storage = new JsonStorage(gson, directory);
        AccountData live = storage.load().getAccounts().get(ALICE);
        live.setAccumulatedSessionTimeMillis(1234);
        // Session-only encoding must not visit unrelated live history.
        history(live).setPrice(900L);
        storage.commit(storage.capture(Collections.emptyMap(), Collections.singletonMap(ALICE, live),
            null, Collections.emptySet()));
        AccountData sessionOnly = new JsonStorage(gson, directory).load().getAccounts().get(ALICE);
        assertEquals(1234, sessionOnly.getAccumulatedSessionTimeMillis());
        assertEquals(100, history(sessionOnly).getPreTaxPrice());

        live.setAccumulatedSessionTimeMillis(2345);
        storage.commit(storage.capture(Collections.singletonMap(ALICE, live), Collections.singletonMap(ALICE, live),
            null, Collections.emptySet()));
        AccountData full = new JsonStorage(gson, directory).load().getAccounts().get(ALICE);
        assertEquals(2345, full.getAccumulatedSessionTimeMillis());
        assertEquals(900, history(full).getPreTaxPrice());
    }

    @Test
    public void deletesAnAccountWithSettingsAndDoesNotResurrectItFromLegacy() throws Exception {
        File directory = seededDirectory();
        JsonStorage storage = new JsonStorage(gson, directory);
        AccountWideData wide = storage.load().getAccountWideData();
        wide.setEnhancedSlots(false);
        storage.commit(storage.capture(Collections.emptyMap(), wide, Collections.singleton(ALICE)));

        JsonStorage reloaded = new JsonStorage(gson, directory);
        assertEquals(Collections.singleton(BOB), reloaded.load().getAccounts().keySet());
        assertNull(reloaded.loadAccount(ALICE));
        assertFalse(reloaded.loadAccountWideData().isEnhancedSlots());
        assertTrue(Files.exists(directory.toPath().resolve(ALICE + ".json")));
    }

    @Test
    public void unreadableLegacyInputDoesNotPublishAnEmptyStore() throws Exception {
        File directory = temporaryFolder.newFolder();
        Path legacy = directory.toPath().resolve(ALICE + ".json");
        byte[] corrupt = "{\"trades\":[".getBytes(StandardCharsets.UTF_8);
        Files.write(legacy, corrupt);
        JsonStorage storage = new JsonStorage(gson, directory);
        expectLoadFailure(storage);
        assertArrayEquals(corrupt, Files.readAllBytes(legacy));
        assertFalse(Files.exists(storage.getStorageDirectory().resolve("checkpoint.json")));
    }

    @Test
    public void rejectsFutureLegacyAndStoredAccountVersionsWithoutReplacingThem() throws Exception {
        File directory = temporaryFolder.newFolder();
        AccountData future = account(ALICE, 100);
        future.setVersion(AccountData.CURRENT_VERSION + 1);
        LegacyJsonFixtures.write(new TradePersister(gson, directory), ALICE, future);
        Path legacy = directory.toPath().resolve(ALICE + ".json");
        byte[] before = Files.readAllBytes(legacy);
        JsonStorage storage = new JsonStorage(gson, directory);
        expectLoadFailure(storage);
        assertArrayEquals(before, Files.readAllBytes(legacy));
        assertFalse(Files.exists(storage.getStorageDirectory().resolve("checkpoint.json")));

        File otherDirectory = temporaryFolder.newFolder();
        JsonStorage other = new JsonStorage(gson, otherDirectory);
        Map<String, JsonElement> records = new JsonStorageCodec(gson).encodeAccount(ALICE, account(ALICE, 100));
        records.get(JsonStorageCodec.accountPrefix(ALICE) + "account").getAsJsonObject().addProperty("formatVersion", 999);
        new JsonJournalStore(other.getStorageDirectory(), gson).initialize(records);
        Path checkpoint = other.getStorageDirectory().resolve("checkpoint.json");
        byte[] originalCheckpoint = Files.readAllBytes(checkpoint);
        expectLoadFailure(other);
        assertArrayEquals(originalCheckpoint, Files.readAllBytes(checkpoint));
    }

    private File seededDirectory() throws Exception {
        File directory = temporaryFolder.newFolder();
        TradePersister legacy = new TradePersister(gson, directory);
        LegacyJsonFixtures.write(legacy, ALICE, account(ALICE, 100));
        LegacyJsonFixtures.write(legacy, BOB, account(BOB, 100));
        return directory;
    }

    private static void expectLoadFailure(JsonStorage storage) throws Exception {
        try {
            storage.load();
            fail("Invalid data must not become an empty store");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static void expectConflict(JsonStorage storage, AccountData stale) throws Exception {
        try {
            storage.commit(storage.capture(Collections.singletonMap(ALICE, stale), null, Collections.emptySet()));
            fail("Unseen changes to the same account must cause a conflict");
        } catch (JsonJournalStore.ConflictException expected) {
            assertTrue(expected.getMessage().contains("preserved"));
        }
    }

    private static AccountData account(String name, int price) {
        AccountData account = new AccountData();
        account.setSessionStartTime(TIME);
        account.setLastModifiedAt(TIME);
        FlippingItem item = new FlippingItem(4151, "Abyssal whip", 70, name);
        item.setValidFlippingPanelItem(true);
        item.getHistory().getCompressedOfferEvents().add(StorageTestOffers.complete(name, 4151,
            "offer-" + name, TIME.toEpochMilli(), 10, price, true));
        account.getTrades().add(item);
        return account;
    }

    private static OfferEvent history(AccountData account) {
        return account.getTrades().get(0).getHistory().getCompressedOfferEvents().get(0);
    }
}
