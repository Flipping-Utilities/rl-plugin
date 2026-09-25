package com.flippingutilities.db;

import com.flippingutilities.accounting.AccountingPlan;
import com.flippingutilities.accounting.AccountingResult;
import com.flippingutilities.accounting.AccountingSource;
import com.flippingutilities.db.accounting.SqliteAccountingStore;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlip;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.flippingutilities.db.StorageTestOffers.complete;
import static org.junit.Assert.*;

public class AccountingPersistenceTest {
    private static final Instant T = Instant.parse("2020-01-10T00:00:00Z");
    private Path file;
    private SqliteStorage storage;
    private SqliteAccountingStore accounting;

    @Before public void setup() throws Exception {
        file = Files.createTempFile("accounting", ".db");
        storage = new SqliteStorage(file.toFile()); storage.initializeSchema();
        accounting = storage.getAccountingStore();
    }

    @After public void cleanup() throws Exception {
        storage.close(); Files.deleteIfExists(file);
        Files.deleteIfExists(Path.of(file + "-wal")); Files.deleteIfExists(Path.of(file + "-shm"));
    }

    @Test public void persistedProfitUsesEarlierCostAndSurvivesReopen() {
        trade("buy", true, 10, 100, T.minusSeconds(86400));
        trade("sell", false, 5, 120, T);
        activate("full", AccountingPlan.Mode.RECALCULATE, null, null, Collections.emptyMap());
        assertEquals(Long.valueOf(100), summary(T, T.plusSeconds(1)).getProfitGp());
        storage.close(); storage.initializeSchema();
        assertEquals(Long.valueOf(100), summary(T, T.plusSeconds(1)).getProfitGp());
        assertEquals(5, accounting.pageRealizations(Collections.singletonList(account()), T, T.plusSeconds(1), 20, 0).get(0).getQuantity());
    }

    @Test public void stalePreviewCannotReplaceCurrentActivity() {
        trade("buy", true, 1, 100, T.minusSeconds(2));
        SqliteAccountingStore.Preview preview = accounting.preview(plan("candidate", AccountingPlan.Mode.RECALCULATE, null, null, Collections.emptyMap()));
        trade("sell", false, 1, 120, T);
        try { accounting.activate(preview); fail("Expected stale preview rejection"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("changed")); }
        assertNull(accounting.getActivePlan(account()));
    }

    @Test public void publicationIsIdempotentAndSelectsOnlyOnePlan() {
        trade("buy", true, 1, 100, T.minusSeconds(2)); trade("sell", false, 1, 120, T);
        SqliteAccountingStore.Preview full = accounting.preview(plan("full", AccountingPlan.Mode.RECALCULATE, null, null, Collections.emptyMap()));
        accounting.activate(full); accounting.activate(full);
        assertEquals(1, summary(null, null).getRealizationCount());
        activate("fresh", AccountingPlan.Mode.FRESH_START, T, null, Collections.emptyMap());
        assertNull(summary(null, null).getProfitGp());
        assertEquals(Long.valueOf(120), summary(null, null).getGrossGp());
        assertEquals(2, accounting.listPlans(account()).size());
        assertEquals(1, summary(null, null).getRealizationCount());
    }

    @Test public void retainedObservationsProduceDeltasAndDeletionDoesNotResurrectBaseline() {
        OfferEvent first = offer("partial-5", true, 5, 100, T); first.setState(GrandExchangeOfferState.BUYING);
        first.setOrderId("live-order"); first.setCumulativeAmount(501L);
        storage.recordOfferUpdate("A", first, Collections.emptyList());
        storage.deleteTradesByUuid("A", Collections.singletonList(first.getUuid()));
        OfferEvent second = offer("partial-8", true, 8, 100, T.plusSeconds(10)); second.setState(GrandExchangeOfferState.BUYING);
        second.setOrderId("live-order"); second.setPredecessorUuid(first.getUuid()); second.setCumulativeAmount(803L);
        storage.recordOfferUpdate("A", second, Collections.singletonList(first.getUuid()));
        List<AccountingSource> sources = accounting.loadSources(account());
        assertEquals(1, sources.size()); assertEquals(3, sources.get(0).getQuantity());
        assertEquals(Long.valueOf(302), sources.get(0).getAmountGp());
        assertEquals(2, count("accounting_observations"));
        storage.recordOfferUpdate("A", second, Collections.singletonList(first.getUuid()));
        assertEquals(2, count("accounting_observations"));
    }

    @Test public void correctionKeepsSaleTimeAndReportingReadsDoNotReconcile() {
        trade("buy", true, 2, 100, T.minusSeconds(10));
        OfferEvent sale = offer("sale", false, 2, 120, T);
        storage.recordTrade("A", sale);
        activate("full", AccountingPlan.Mode.RECALCULATE, null, null, Collections.emptyMap());
        OfferEvent correction = offer("correction", false, 2, 130, T.plusSeconds(50)); correction.setPredecessorUuid("sale");
        storage.recordOfferUpdate("A", correction, Collections.singletonList("sale"));
        assertTrue(summary(null, null).isStale());
        assertEquals(Long.valueOf(40), summary(null, null).getProfitGp());
        accounting.reconcileAll();
        assertEquals(Long.valueOf(60), summary(T, T.plusSeconds(1)).getProfitGp());
        assertEquals(0, summary(T.plusSeconds(1), null).getRealizationCount());
    }

    @Test public void cleanAppendPreservesEarlierRowsAndConservesCostRemainders() throws Exception {
        OfferEvent purchase = offer("buy", true, 3, 100, T.minusSeconds(5)); purchase.setCumulativeAmount(301L);
        storage.recordTrade("A", purchase);
        trade("sale1", false, 1, 120, T);
        activate("full", AccountingPlan.Mode.RECALCULATE, null, null, Collections.emptyMap());
        long rowid;
        try (Statement statement = storage.getConnection().createStatement(); ResultSet row = statement.executeQuery("SELECT rowid FROM accounting_realizations")) { row.next(); rowid = row.getLong(1); }
        trade("sale2", false, 2, 120, T.plusSeconds(5));
        accounting.reconcileAll();
        assertEquals(Long.valueOf(59), summary(null, null).getProfitGp());
        try (PreparedStatement statement = storage.getConnection().prepareStatement("SELECT 1 FROM accounting_realizations WHERE rowid=? AND source_id='sale1'")) {
            statement.setLong(1, rowid); try (ResultSet row = statement.executeQuery()) { assertTrue(row.next()); }
        }
    }

    @Test public void fixedPurchaseCutoffAndSelectedQuantityLimitOpeningStock() {
        trade("old", true, 10, 100, T.minusSeconds(10000));
        trade("recent", true, 5, 110, T.minusSeconds(10));
        trade("sale", false, 3, 130, T.plusSeconds(1));
        activate("fresh", AccountingPlan.Mode.FRESH_START, T, T.minusSeconds(100), Collections.singletonMap("recent", 2L));
        SqliteAccountingStore.Summary summary = summary(T, null);
        assertEquals(3, summary.getSoldQuantity()); assertEquals(2, summary.getMatchedQuantity());
        assertNull(summary.getProfitGp()); assertEquals(40, summary.getKnownProfitGp());
    }

    @Test public void accountUpsertAndOtherAccountsNeverSpendThisAccountsInventory() {
        trade("buy", true, 2, 100, T.minusSeconds(1));
        activate("full", AccountingPlan.Mode.RECALCULATE, null, null, Collections.emptyMap());
        storage.upsertAccount("A", "known-player");
        assertNotNull(accounting.getActivePlan(account()));
        storage.recordTrade("B", complete("B", 4151, "other-sale", T.toEpochMilli(), 2, 130, false));
        long second = accounting.findAccountId("B");
        accounting.activate(accounting.preview(new AccountingPlan("second", second, AccountingPlan.Mode.RECALCULATE, null, null, Collections.emptyMap())));
        assertNull(accounting.getSummary(Arrays.asList(account(), second), null, null).getProfitGp());
    }

    @Test public void hybridFrozenSourceViewSurvivesFutureCorrection() {
        trade("buy", true, 2, 100, T.minusSeconds(10));
        trade("sale", false, 1, 120, T.minusSeconds(5));
        activate("hybrid", AccountingPlan.Mode.HYBRID, T, null, Collections.emptyMap());
        OfferEvent correction = offer("corrected", false, 1, 150, T.plusSeconds(5)); correction.setPredecessorUuid("sale");
        storage.recordOfferUpdate("A", correction, Collections.singletonList("sale")); accounting.reconcileAll();
        assertEquals(120, accounting.loadFrozenLegacyAccount("hybrid").getTrades().get(0).getHistory().getCompressedOfferEvents().get(1).getPreTaxPrice());
    }

    @Test public void additiveLayoutUpgradePreservesOldVersionOneSources() throws Exception {
        trade("buy", true, 1, 100, T);
        try (Statement statement = storage.getConnection().createStatement()) {
            statement.execute("DELETE FROM settings WHERE key='accounting_layout'");
            statement.execute("DROP TABLE accounting_items");
        }
        storage.initializeSchema(); storage.initializeSchema();
        assertEquals(1, count("trades")); assertEquals(1, count("accounting_observations"));
        assertNotNull(storage.getSetting("accounting_layout"));
    }

    @Test public void failedPublicationKeepsPreviousPlanAndCompleteFacts() throws Exception {
        trade("buy", true, 1, 100, T.minusSeconds(2)); trade("sell", false, 1, 120, T);
        activate("full", AccountingPlan.Mode.RECALCULATE, null, null, Collections.emptyMap());
        SqliteAccountingStore.Preview candidate = accounting.preview(plan("fresh", AccountingPlan.Mode.FRESH_START, T, null, Collections.emptyMap()));
        try (Statement statement = storage.getConnection().createStatement()) {
            statement.execute("CREATE TRIGGER fail_publication BEFORE INSERT ON accounting_realizations WHEN NEW.plan_id='fresh' BEGIN SELECT RAISE(ABORT,'simulated disk failure'); END");
        }
        try { accounting.activate(candidate); fail("Expected failed publication"); }
        catch (IllegalStateException expected) { assertNotNull(expected.getCause()); }
        assertEquals("full", accounting.getActivePlan(account()).getId());
        assertEquals(Long.valueOf(20), summary(null, null).getProfitGp());
        assertEquals(1, summary(null, null).getRealizationCount());
    }

    @Test public void recipeInstanceIdentitySurvivesReloadDeletionAndReusedSqliteRowId() {
        OfferEvent buy = offer("input", true, 10, 100, T.minusSeconds(10));
        OfferEvent sell = offer("output", false, 10, 130, T);
        storage.recordTrade("A", buy); storage.recordTrade("A", sell);
        RecipeFlip first = recipe(buy, sell), second = recipe(buy, sell);
        storage.insertRecipeFlip("A", "recipe", first); storage.insertRecipeFlip("A", "recipe", second);
        storage.insertRecipeFlip("A", "recipe", first.clone());
        assertEquals(2, count("recipe_flips"));
        assertEquals(first.getId(), storage.loadAccount("A").getRecipeFlipGroups().get(0).getRecipeFlips().get(0).getId());
        storage.deleteRecipeFlip("A", "recipe", second);
        assertEquals(1, count("recipe_flips"));
        RecipeFlip third = recipe(buy, sell); storage.insertRecipeFlip("A", "recipe", third);
        assertEquals(3, count("accounting_recipes"));
        assertEquals(2, accounting.loadRecipes(account()).size());
    }

    @Test public void retainedHistoryGuardDistinguishesLegacyCopiesFromRicherEvidence() {
        trade("old", true, 1, 100, T);
        assertFalse(accounting.hasRetainedHistory());
        OfferEvent live = offer("new", true, 1, 101, T.plusSeconds(1)); live.setCumulativeAmount(101L);
        storage.recordTrade("A", live);
        assertTrue(accounting.hasRetainedHistory());
        storage.close(); storage.initializeSchema();
        assertTrue(storage.getAccountingStore().hasRetainedHistory());
    }

    @Test public void correctionToSelectedOpeningBasisDoesNotSilentlyRestateProfit() {
        trade("carried", true, 2, 100, T.minusSeconds(10)); trade("sale", false, 1, 120, T.plusSeconds(1));
        activate("fresh", AccountingPlan.Mode.FRESH_START, T, T.minusSeconds(100), Collections.singletonMap("carried", 2L));
        assertEquals(Long.valueOf(20), summary(null, null).getProfitGp());
        OfferEvent correction = offer("correction", true, 2, 110, T.plusSeconds(20)); correction.setPredecessorUuid("carried");
        storage.recordOfferUpdate("A", correction, Collections.singletonList("carried")); accounting.reconcileAll();
        assertNull(summary(null, null).getProfitGp());
    }

    @Test public void lookupUsesPlanAndTimeIndex() throws Exception {
        boolean indexed = false;
        try (Statement statement = storage.getConnection().createStatement(); ResultSet row = statement.executeQuery(
            "EXPLAIN QUERY PLAN SELECT profit_gp FROM accounting_realizations WHERE plan_id='p' AND recognized_at>=1 AND recognized_at<2")) {
            while (row.next()) indexed |= row.getString("detail").contains("accounting_realizations_time");
        }
        assertTrue(indexed);
    }

    @Test public void newLedgerRejectsRecipeOverAllocationAtomically() {
        OfferEvent buy = offer("input", true, 1, 100, T.minusSeconds(10));
        OfferEvent sell = offer("output", false, 1, 130, T);
        storage.recordTrade("A", buy); storage.recordTrade("A", sell);
        activate("full", AccountingPlan.Mode.RECALCULATE, null, null, Collections.emptyMap());
        storage.insertRecipeFlip("A", "recipe", recipe(buy, sell)); accounting.reconcileAll();
        long revision = accounting.getSourceRevision(account());
        try { storage.insertRecipeFlip("A", "recipe", recipe(buy, sell)); fail("Expected over-allocation rejection"); }
        catch (com.flippingutilities.db.accounting.AccountingValidationException expected) { assertTrue(expected.getMessage().contains("quantity")); }
        assertEquals(1, count("recipe_flips")); assertEquals(1, count("accounting_recipes"));
        assertEquals(revision, accounting.getSourceRevision(account()));
    }

    @Test public void lateHistoricalSaleCannotShiftReviewedOpeningSegments() {
        trade("stock", true, 10, 100, T.minusSeconds(10)); trade("sale", false, 1, 120, T.plusSeconds(1));
        activate("fresh", AccountingPlan.Mode.FRESH_START, T, T.minusSeconds(100), Collections.singletonMap("stock", 2L));
        assertEquals(Long.valueOf(20), summary(null, null).getProfitGp());
        trade("late-history", false, 1, 120, T.minusSeconds(5)); accounting.reconcileAll();
        assertNull(summary(T, null).getProfitGp());
    }

    @Test public void freezingHybridViewPreservesOnlyUndeletedQuantity() {
        OfferEvent first = offer("partial5", true, 5, 100, T.minusSeconds(20)); first.setState(GrandExchangeOfferState.BUYING);
        storage.recordOfferUpdate("A", first, Collections.emptyList());
        storage.deleteTradesByUuid("A", Collections.singletonList(first.getUuid()));
        OfferEvent second = offer("partial8", true, 8, 100, T.minusSeconds(10)); second.setPredecessorUuid(first.getUuid());
        storage.recordOfferUpdate("A", second, Collections.singletonList(first.getUuid()));
        activate("hybrid", AccountingPlan.Mode.HYBRID, T, null, Collections.emptyMap());
        assertEquals(3, accounting.loadFrozenLegacyAccount("hybrid").getTrades().get(0).getHistory().getCompressedOfferEvents().get(0).getCurrentQuantityInTrade());
    }

    @Test public void partitionReplayPreservesWarningsFromOtherItems() {
        OfferEvent undatedA = offer("undated-a", true, 1, 100, T); undatedA.setTime(null);
        OfferEvent undatedB = offer("undated-b", true, 1, 100, T); undatedB.setTime(null); undatedB.setItemId(4587);
        storage.recordTrade("A", undatedA); storage.recordTrade("A", undatedB);
        activate("fresh", AccountingPlan.Mode.FRESH_START, T, null, Collections.emptyMap());
        assertEquals(2, accounting.getWarnings(account()).size());
        trade("sale", false, 1, 120, T.plusSeconds(1)); accounting.reconcileAll();
        assertTrue(accounting.getWarnings(account()).stream().anyMatch(warning -> warning.endsWith("undated-b")));
    }

    @Test public void exactObservationAfterEstimatedBaselineKeepsDeltaEstimated() {
        OfferEvent prior = offer("old-partial", true, 3, 100, T); prior.setState(GrandExchangeOfferState.BUYING);
        storage.recordOfferUpdate("A", prior, Collections.emptyList());
        OfferEvent update = offer("live-partial", true, 5, 100, T.plusSeconds(1));
        update.setPredecessorUuid(prior.getUuid()); update.setCumulativeAmount(502L);
        storage.recordOfferUpdate("A", update, Collections.singletonList(prior.getUuid()));
        AccountingSource delta = accounting.loadSources(account()).stream().filter(source -> source.getId().equals("live-partial")).findFirst().get();
        assertTrue(delta.isEstimated()); assertEquals(Long.valueOf(202), delta.getAmountGp());
    }

    private RecipeFlip recipe(OfferEvent buy, OfferEvent sell) {
        return new RecipeFlip(T, Collections.singletonMap(4151, Collections.singletonMap(sell.getUuid(), new PartialOffer(sell, 1))),
            Collections.singletonMap(4151, Collections.singletonMap(buy.getUuid(), new PartialOffer(buy, 1))), 0);
    }

    private void trade(String id, boolean buy, int quantity, int price, Instant when) { storage.recordTrade("A", offer(id, buy, quantity, price, when)); }
    private OfferEvent offer(String id, boolean buy, int quantity, int price, Instant when) { return complete("A", 4151, id, when.toEpochMilli(), quantity, price, buy); }
    private long account() { return accounting.findAccountId("A"); }
    private AccountingPlan plan(String id, AccountingPlan.Mode mode, Instant cutover, Instant cutoff, java.util.Map<String, Long> opening) {
        return new AccountingPlan(id, account(), mode, cutover, cutoff, opening);
    }
    private void activate(String id, AccountingPlan.Mode mode, Instant cutover, Instant cutoff, java.util.Map<String, Long> opening) {
        accounting.activate(accounting.preview(plan(id, mode, cutover, cutoff, opening)));
    }
    private SqliteAccountingStore.Summary summary(Instant from, Instant to) { return accounting.getSummary(Collections.singletonList(account()), from, to); }
    private long count(String table) {
        try (Statement statement = storage.getConnection().createStatement(); ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) { row.next(); return row.getLong(1); }
        catch (Exception e) { throw new AssertionError(e); }
    }
}
