package com.flippingutilities.controller;

import com.flippingutilities.db.TradePersister;
import com.flippingutilities.model.*;
import com.flippingutilities.ui.widgets.SlotActivityTimer;
import com.google.gson.Gson;
import net.runelite.client.util.Filepath;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.*;

import static org.junit.Assert.*;

public class AccountIdentityTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private DataHandler handler;
    private TestPersister persister;
    private BackupCheckpoints checkpoints;

    @Before public void setup() throws Exception {
        FlippingPlugin plugin = new FlippingPlugin();
        persister = new TestPersister(Filepath.Unchecked.getRooted(temporary.getRoot().toPath()));
        plugin.tradePersister = persister;
        handler = new DataHandler(plugin);
        checkpoints = new BackupCheckpoints();
        setField("backupCheckpoints", checkpoints);
    }

    @Test public void loginRenamesSameIdentityAndKeepsPendingHistory() throws Exception {
        AccountData old = account("123", "Old name");
        FlippingItem item = new FlippingItem(1, "Item", 100, "Old name");
        OfferEvent offer = offer("trade-uuid", "Old name");
        item.getHistory().getCompressedOfferEvents().add(offer);
        old.getTrades().add(item);
        seed("Old name", old);
        handler.getAccountData("Old name");
        checkpoints.getAccountToBackupTime().put("Old name", Instant.EPOCH);

        assertEquals("New name", handler.bindLoggedInAccount("123", "New name"));
        assertEquals(Collections.singleton("New name"), handler.getCurrentAccounts());
        assertSame(old, handler.viewAccountData("New name"));
        assertEquals("New name", item.getFlippedBy());
        assertEquals("New name", offer.getMadeBy());
        assertEquals("trade-uuid", offer.getUuid());
        assertEquals(Instant.EPOCH, checkpoints.getAccountToBackupTime().get("id:123"));
        handler.storeData();
        assertEquals(Collections.singletonList("New name"), persister.savedAccounts);
    }

    @Test public void reusedNameKeepsBothCharactersAndDifferentOfferOwners() throws Exception {
        AccountData previous = account("123", "Bob");
        previous.getTrades().add(new FlippingItem(1, "Item", 100, "Bob"));
        seed("Bob", previous);

        assertEquals("Bob [456]", handler.bindLoggedInAccount("456", "Bob"));
        assertEquals(new HashSet<>(Arrays.asList("Bob [123]", "Bob [456]")), handler.getCurrentAccounts());
        assertSame(previous, handler.viewAccountData("Bob [123]"));
        assertEquals("Bob [123]", previous.getTrades().get(0).getFlippedBy());
        assertEquals("Bob", previous.getDisplayName());
        assertEquals("456", handler.viewAccountData("Bob [456]").getAccountId());
        assertEquals(handler.getCurrentAccounts(), persister.index.keySet());
    }

    @Test public void loginLoadsExistingIdHistoryBeforeItsPollingEventArrives() throws Exception {
        AccountData disk = account("123", "Old name");
        disk.setStorageFileName("123_Old name.json");
        disk.getTrades().add(new FlippingItem(1, "Existing history", 100, "Old name"));
        persister.files.put("123_Old name.json", disk);
        assertEquals("New name", handler.bindLoggedInAccount("123", "New name"));
        assertSame(disk, handler.viewAccountData("New name"));
        assertEquals("Existing history", handler.viewAccountData("New name").getTrades().get(0).getItemName());
    }

    @Test public void playerNamedAccountwideCannotBecomeTheCombinedView() throws Exception {
        assertEquals("Accountwide [123]", handler.bindLoggedInAccount("123", "Accountwide"));
        assertEquals("Accountwide", handler.viewAccountData("Accountwide [123]").getDisplayName());
        assertFalse(handler.getCurrentAccounts().contains(FlippingPlugin.ACCOUNT_WIDE));
    }

    @Test public void numericLegacyNameCannotStealAnotherCharactersBackupCheckpoint() throws Exception {
        AccountData legacy = account(null, "123");
        seed("123", legacy);
        checkpoints.getAccountToBackupTime().put("id:123", Instant.EPOCH);
        checkpoints.getAccountToBackupTime().put("123", Instant.EPOCH.plusSeconds(1));
        handler.bindLoggedInAccount("456", "123");
        assertEquals(Instant.EPOCH, checkpoints.getAccountToBackupTime().get("id:123"));
        assertEquals(Instant.EPOCH.plusSeconds(1), checkpoints.getAccountToBackupTime().get("id:456"));
    }

    @Test public void firstLoginClaimsMatchingLegacyHistory() throws Exception {
        AccountData legacy = account(null, "Con");
        legacy.getTrades().add(new FlippingItem(1, "Item", 100, "Con"));
        seed("Con", legacy);
        assertEquals("Con", handler.bindLoggedInAccount("18446744073709551614", "Con"));
        assertSame(legacy, handler.viewAccountData("Con"));
        assertEquals("18446744073709551614", legacy.getAccountId());
        assertEquals(1, legacy.getTrades().size());
    }

    @Test public void changedFileRenamesDirtyCacheWithoutDiscardingEdits() throws Exception {
        AccountData old = account("123", "Old");
        old.getTrades().add(new FlippingItem(1, "Pending item", 100, "Old"));
        seed("Old", old);
        handler.getAccountData("Old");
        AccountData incoming = account("123", "New");
        incoming.setStorageFileName("123_New.json");
        persister.files.put("123_New.json", incoming);

        assertEquals("New", handler.loadAccountFile("123_New.json"));
        assertEquals(Collections.singleton("New"), handler.getCurrentAccounts());
        assertSame(old, handler.viewAccountData("New"));
        assertEquals("Pending item", old.getTrades().get(0).getItemName());
        assertEquals("123_New.json", old.getStorageFileName());
        handler.storeData();
        assertEquals(Collections.singletonList("New"), persister.savedAccounts);
    }

    @Test public void anotherClientMigratesLegacyCacheWithoutDuplicatingAccount() throws Exception {
        AccountData legacy = account(null, "Bob");
        legacy.setStorageFileName("Bob.json");
        seed("Bob", legacy);
        handler.getAccountData("Bob");
        persister.files.put("123_Bob.json", account("123", "Bob"));
        persister.legacyFileExists = false;
        assertEquals("Bob", handler.loadAccountFile("123_Bob.json"));
        assertEquals(Collections.singleton("Bob"), handler.getCurrentAccounts());
        assertSame(legacy, handler.viewAccountData("Bob"));
        assertEquals("123", legacy.getAccountId());
    }

    @Test public void delayedMissingFileEventDoesNotCreateEmptyAccount() throws Exception {
        AccountData current = account("123", "New");
        seed("New", current);
        assertNull(handler.loadAccountFile("123_Old.json"));
        assertEquals(Collections.singleton("New"), handler.getCurrentAccounts());
        assertSame(current, handler.viewAccountData("New"));
    }

    @Test public void reloadDisambiguatesAnUnrelatedCharacterWithSameName() throws Exception {
        AccountData current = account("123", "Bob");
        seed("Bob", current);
        persister.files.put("456_Bob.json", account("456", "Bob"));
        assertEquals("Bob [456]", handler.loadAccountFile("456_Bob.json"));
        assertEquals("Bob [123]", handler.getAccountKey(current));
        assertEquals(2, handler.getCurrentAccounts().size());
    }

    @Test public void failedSaveKeepsChangesForRetry() throws Exception {
        AccountData current = account("123", "Bob");
        seed("Bob", current);
        handler.getAccountData("Bob");
        persister.failWrite = true;
        handler.storeData();
        persister.failWrite = false;
        handler.storeData();
        assertEquals(Collections.singletonList("Bob"), persister.savedAccounts);
    }

    @Test public void renameUpdatesEveryHydratedOwnerWithoutChangingIdentityOrUuids() {
        AccountData account = account("123", "Actual RSN");
        OfferEvent history = offer("history", "Old");
        FlippingItem item = new FlippingItem(1, "Item", 100, "Old");
        item.getHistory().getCompressedOfferEvents().add(history);
        account.getTrades().add(item);
        OfferEvent last = offer("last", "Old");
        account.getLastOffers().put(0, last);
        OfferEvent recipeOffer = offer("recipe", "Old");
        PartialOffer partial = new PartialOffer(recipeOffer, 1);
        RecipeFlipGroup group = new RecipeFlipGroup("recipe");
        group.getRecipeFlips().add(new RecipeFlip(Instant.EPOCH, Collections.emptyMap(),
            Collections.singletonMap(1, Collections.singletonMap("recipe", partial)), 0));
        account.getRecipeFlipGroups().add(group);
        SlotActivityTimer timer = new SlotActivityTimer(null, null, 0);
        timer.currentOffer = offer("timer", "Old");
        account.setSlotTimers(Collections.singletonList(timer));

        account.renameAccount("Actual RSN [123]");

        assertEquals("Actual RSN", account.getDisplayName());
        assertEquals("123", account.getAccountId());
        for (OfferEvent event : Arrays.asList(history, last, recipeOffer, timer.currentOffer)) {
            assertEquals("Actual RSN [123]", event.getMadeBy());
        }
        assertEquals("history", history.getUuid());
        assertEquals("recipe", partial.getOfferUuid());
        assertEquals("Actual RSN [123]", item.getFlippedBy());
    }

    private void seed(String key, AccountData account) throws Exception {
        Map<String, AccountData> accounts = new HashMap<>();
        accounts.put(key, account);
        setField("accountSpecificData", accounts);
        persister.setAccountIndex(accounts);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = DataHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(handler, value);
    }

    private static AccountData account(String id, String name) {
        AccountData account = new AccountData() {
            @Override public void prepareForUse(FlippingPlugin plugin) { }
        };
        account.setAccountId(id);
        account.setDisplayName(name);
        return account;
    }

    private static OfferEvent offer(String uuid, String owner) {
        OfferEvent event = new OfferEvent();
        event.setUuid(uuid);
        event.setMadeBy(owner);
        return event;
    }

    private static class TestPersister extends TradePersister {
        private final Map<String, AccountData> files = new HashMap<>();
        private Map<String, AccountData> index = new HashMap<>();
        private final List<String> savedAccounts = new ArrayList<>();
        private boolean failWrite;
        private boolean legacyFileExists = true;

        private TestPersister(Filepath directory) { super(new Gson(), directory); }
        @Override public synchronized void setAccountIndex(Map<String, AccountData> accounts) { index = new HashMap<>(accounts); }
        @Override public synchronized void bindAccount(String id, String name, AccountData data) {
            data.setAccountId(id);
            data.setDisplayName(name);
            data.setStorageFileName(id + "_" + name + ".json");
        }
        @Override public synchronized AccountData loadAccountFile(String filename) { return files.get(filename); }
        @Override public synchronized AccountData loadAccountById(String id) {
            return files.values().stream().filter(data -> id.equals(data.getAccountId())).findFirst().orElse(null);
        }
        @Override public synchronized boolean accountFileExists(AccountData data) { return legacyFileExists; }
        @Override public void writeToFile(String key, Object data) throws IOException {
            if (failWrite) throw new IOException("simulated write failure");
            if (data instanceof AccountData) savedAccounts.add(key);
        }
    }
}
