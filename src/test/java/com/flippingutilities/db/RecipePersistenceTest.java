package com.flippingutilities.db;

import com.flippingutilities.model.*;
import com.flippingutilities.controller.FlippingPlugin;
import com.google.gson.Gson;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

import static com.flippingutilities.db.StorageTestOffers.complete;
import static org.junit.Assert.*;

public class RecipePersistenceTest {
    private static final String ACCOUNT = "Recipe account";
    private static final long TIME = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
    private final Gson gson = new Gson();

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void migrationPreservesLegacyRecipeOffersMissingFromHistory() throws Exception {
        File accounts = temporaryFolder.newFolder("accounts");
        AccountData source = legacyAccount();
        Files.writeString(new File(accounts, ACCOUNT + ".json").toPath(), gson.toJson(source));
        TradePersister persister = new TradePersister(gson, accounts);
        SqliteStorage storage = new SqliteStorage(temporaryFolder.newFile("recipes.db"));
        try {
            assertEquals(1, new MigrationService(storage, persister).migrate());
            storage.close();
            assertRecipePreserved(storage.loadAccount(ACCOUNT));
        } finally {
            storage.close();
        }
    }

    private AccountData legacyAccount() {
        AccountData account = new AccountData();
        OfferEvent normal = complete(ACCOUNT, 4151, "normal", TIME, 10, 100, true);
        OfferEvent detachedInput = complete(ACCOUNT, 4587, "detached-input", TIME, 20, 250, true);
        OfferEvent detachedOutput = complete(ACCOUNT, 11802, "detached-output", TIME, 30, 1000, false);
        FlippingItem item = new FlippingItem(4151, "Abyssal whip", 70, ACCOUNT);
        item.updateHistory(normal);
        account.getTrades().add(item);
        Map<Integer, Map<String, PartialOffer>> inputs = new LinkedHashMap<>();
        inputs.put(4151, Collections.singletonMap("normal", new PartialOffer(normal, 2)));
        inputs.put(4587, Collections.singletonMap("detached-input", new PartialOffer(detachedInput, 1)));
        Map<Integer, Map<String, PartialOffer>> outputs = Collections.singletonMap(11802,
            Collections.singletonMap("detached-output", new PartialOffer(detachedOutput, 1)));
        RecipeFlip flip = new RecipeFlip(Instant.ofEpochMilli(TIME), outputs, inputs, 0);
        // Legacy records have the UUID inside the embedded offer, not on PartialOffer.
        flip.getPartialOffers().forEach(po -> po.setOfferUuid(null));
        RecipeFlipGroup group = new RecipeFlipGroup("4151:2,4587:1|11802:1");
        group.getRecipeFlips().add(flip);
        account.getRecipeFlipGroups().add(group);
        return account;
    }

    @Test
    public void jsonRewritePreservesRecipeOffersBeforeSqliteMigration() throws Exception {
        File accounts = temporaryFolder.newFolder("json-rewrite");
        TradePersister persister = new TradePersister(gson, accounts);
        AccountData source = legacyAccount();
        hydrateReferences(source);
        source.markMigrated();
        persister.writeToFile(ACCOUNT, source);

        AccountData reloaded = persister.loadAccount(ACCOUNT);
        hydrateReferences(reloaded);
        assertRecipePreserved(reloaded);
        SqliteStorage storage = new SqliteStorage(temporaryFolder.newFile("rewritten.db"));
        try {
            assertEquals(1, new MigrationService(storage, persister).migrate());
            assertRecipePreserved(storage.loadAccount(ACCOUNT));
        } finally {
            storage.close();
        }
    }

    private void hydrateReferences(AccountData account) {
        Map<String, OfferEvent> history = new HashMap<>();
        account.getTrades().forEach(item -> item.getHistory().getCompressedOfferEvents()
            .forEach(offer -> history.put(offer.getUuid(), offer)));
        account.getRecipeFlipGroups().forEach(group -> group.getPartialOffers()
            .forEach(component -> component.hydrateOffer(history)));
    }

    /**
     * A dangling reference (offer exists nowhere: not embedded, not in history) must NOT
     * block the account's migration. Refusing the account drops ALL of its trades and flips
     * from SQLite, degrades every total that includes it, and retries (and fails) on every
     * startup — far more destructive than the dead reference itself. The reference migrates
     * as a zero-price stub instead, matching how the JSON backend has always rendered it.
     */
    @Test
    public void danglingRecipeReferenceMigratesAsZeroPriceStubInsteadOfBlockingAccount() throws Exception {
        AccountData source = legacyAccount();
        source.getTrades().clear();
        PartialOffer missing = source.getRecipeFlipGroups().get(0).getPartialOffers().get(0);
        missing.setOfferUuid(missing.getOffer().getUuid());
        missing.setOffer(null);
        source.getRecipeFlipGroups().get(0).synthesizeRecipe(null);
        source.prepareForUse(new FlippingPlugin());
        File accounts = temporaryFolder.newFolder("unresolved");
        TradePersister persister = new TradePersister(gson, accounts);
        persister.writeToFile(ACCOUNT, source);
        SqliteStorage storage = new SqliteStorage(temporaryFolder.newFile("unresolved.db"));
        try {
            assertEquals("The account must migrate despite the dangling reference",
                1, new MigrationService(storage, persister).migrate());
            assertNotNull("migrated_ flag must be set so startup retries stop",
                storage.getSetting("migrated_" + ACCOUNT));
            assertEquals("Migration completes when no account fails",
                "true", storage.getSetting("migration_completed"));

            // The nulled component ("normal") renders as a zero-price stub; components that
            // kept their embedded snapshots load with their real prices untouched.
            AccountData loaded = storage.loadAccount(ACCOUNT);
            assertEquals(1, loaded.getRecipeFlipGroups().size());
            RecipeFlip flip = loaded.getRecipeFlipGroups().get(0).getRecipeFlips().get(0);
            PartialOffer stubbed = flip.getInputs().get(4151).get("normal");
            assertNotNull("Dangling component must still render", stubbed.getOffer());
            assertEquals("Stub price must be zero", 0, stubbed.getOffer().getPrice());
            assertEquals("Embedded snapshot price preserved (input)", 250,
                flip.getInputs().get(4587).get("detached-input").getOffer().getPreTaxPrice());
            assertEquals("Embedded snapshot price preserved (output)", 1000,
                flip.getOutputs().get(11802).get("detached-output").getOffer().getPreTaxPrice());
        } finally {
            storage.close();
        }
    }

    @Test
    public void liveRecipeWritePreservesDetachedSnapshotsAndRollsBackMissingOffers() throws Exception {
        AccountData source = legacyAccount();
        hydrateReferences(source);
        RecipeFlip flip = source.getRecipeFlipGroups().get(0).getRecipeFlips().get(0);
        SqliteStorage storage = new SqliteStorage(temporaryFolder.newFile("live.db"));
        try {
            storage.initializeSchema();
            storage.recordTrade(ACCOUNT, source.getTrades().get(0).getHistory().getCompressedOfferEvents().get(0));
            storage.insertRecipeFlip(ACCOUNT, "recipe", flip.clone());
            storage.close();
            assertRecipePreserved(storage.loadAccount(ACCOUNT));

            RecipeFlip unresolved = flip.clone();
            unresolved.setTimeOfCreation(flip.getTimeOfCreation().plusSeconds(1));
            unresolved.getOutputs().get(11802).get("detached-output").setOffer(null);
            try {
                storage.insertRecipeFlip(ACCOUNT, "recipe", unresolved.clone());
                fail("Missing offer data must reject the entire recipe write");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getCause().getMessage().contains("detached-output"));
            }
            AccountData restored = storage.loadAccount(ACCOUNT);
            assertEquals(1, restored.getRecipeFlipGroups().get(0).getRecipeFlips().size());
            assertRecipePreserved(restored);
        } finally {
            storage.close();
        }
    }

    @Test
    public void migrationStillResolvesUuidOnlyRecipesFromHistory() throws Exception {
        AccountData source = legacyAccount();
        for (PartialOffer component : source.getRecipeFlipGroups().get(0).getPartialOffers()) {
            OfferEvent offer = component.getOffer();
            if (offer.getItemId() != 4151) {
                FlippingItem item = new FlippingItem(offer.getItemId(), "Ingredient", 70, ACCOUNT);
                item.updateHistory(offer);
                source.getTrades().add(item);
            }
            component.setOfferUuid(offer.getUuid());
            component.setOffer(null);
        }
        SqliteStorage storage = new SqliteStorage(temporaryFolder.newFile("uuid-only.db"));
        try {
            assertEquals(1, new MigrationService(storage, new TradePersister(gson))
                .migrate(Collections.singletonMap(ACCOUNT, source)));
            RecipeFlip restored = storage.loadAccount(ACCOUNT).getRecipeFlipGroups().get(0).getRecipeFlips().get(0);
            assertEquals(530, restored.getProfit());
            assertOffer(restored.getOutputs().get(11802).get("detached-output"), 30, 1000, 1);
        } finally {
            storage.close();
        }
    }

    @Test
    public void migrationRejectsRecipeOffersWithoutTime() throws Exception {
        AccountData source = legacyAccount();
        source.getRecipeFlipGroups().get(0).getPartialOffers().get(0).getOffer().setTime(null);
        SqliteStorage storage = new SqliteStorage(temporaryFolder.newFile("missing-time.db"));
        try {
            assertEquals("An offer without a timestamp cannot be used to calculate recipe prices", 0,
                new MigrationService(storage, new TradePersister(gson)).migrate(Collections.singletonMap(ACCOUNT, source)));
            assertNull(storage.getSetting("migration_completed"));
            assertTrue(storage.listAccounts().isEmpty());
        } finally {
            storage.close();
        }
    }

    private void assertRecipePreserved(AccountData account) {
        assertNotNull(account);
        assertEquals("Recipe-only trades must not reappear in ordinary history", 1, account.getTrades().size());
        assertEquals(1, account.getTrades().get(0).getHistory().getCompressedOfferEvents().size());
        RecipeFlip flip = account.getRecipeFlipGroups().get(0).getRecipeFlips().get(0);
        assertEquals(450, flip.getExpense());
        assertEquals(980, flip.getRevenue());
        assertEquals(530, flip.getProfit());
        assertEquals(20, flip.getTaxPaid());
        assertOffer(flip.getInputs().get(4151).get("normal"), 10, 100, 2);
        assertOffer(flip.getInputs().get(4587).get("detached-input"), 20, 250, 1);
        assertOffer(flip.getOutputs().get(11802).get("detached-output"), 30, 1000, 1);
    }

    private void assertOffer(PartialOffer component, int quantity, int price, int consumed) {
        assertNotNull(component.getOffer());
        assertEquals(quantity, component.getOffer().getCurrentQuantityInTrade());
        assertEquals(price, component.getOffer().getPreTaxPrice());
        assertEquals(consumed, component.getAmountConsumed());
        assertEquals(Instant.ofEpochMilli(TIME), component.getOffer().getTime());
    }
}
