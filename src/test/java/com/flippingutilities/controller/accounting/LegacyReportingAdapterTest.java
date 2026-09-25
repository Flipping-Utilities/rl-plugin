package com.flippingutilities.controller.accounting;

import com.flippingutilities.controller.accounting.LegacyReportingAdapter.LegacyReport;
import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.RecipeFlipGroup;
import com.flippingutilities.ui.accounting.AccountingUiService.ReportKind;
import com.flippingutilities.ui.accounting.AccountingUiService.ReportQuery;
import com.flippingutilities.ui.accounting.AccountingUiService.Sort;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyReportingAdapterTest
{
    private static final Instant START = Instant.parse("2020-01-01T00:00:00Z");
    private final LegacyReportingAdapter adapter = new LegacyReportingAdapter();

    @Test
    public void compatibilityFiltersPurchasesBeforeMatchingInsteadOfRecognizingSaleProfit()
    {
        AccountData snapshot = account("A", offer("buy", true, 1, 100, 10), offer("sell", false, 1, 120, 20));
        LegacyReport result = adapter.calculate("A", snapshot, query(ReportKind.ITEMS, 15L, 30L, null, ""), "Legacy");
        assertEquals(Long.valueOf(0), result.getSegment().amounts.profit.completeGp);
        assertEquals(Long.valueOf(120), result.getSegment().amounts.gross.completeGp);
        assertEquals(0, result.getSegment().flipCount);
        assertEquals(1, result.getSegment().soldQuantity);
        assertEquals(1, result.getRows().size());
    }

    @Test
    public void fullPeriodPreservesExistingMatchedProfitAndStrictLegacyStart()
    {
        AccountData snapshot = account("A", offer("buy", true, 1, 100, 10), offer("sell", false, 1, 120, 20));
        LegacyReport all = adapter.calculate("A", snapshot, query(ReportKind.ITEMS, null, null, null, ""), "Legacy");
        assertEquals(Long.valueOf(20), all.getSegment().amounts.profit.completeGp);
        LegacyReport exactStart = adapter.calculate("A", snapshot,
            query(ReportKind.ITEMS, 10L, 30L, null, ""), "Legacy");
        assertEquals(Long.valueOf(0), exactStart.getSegment().amounts.profit.completeGp);
        LegacyReport cutover = adapter.calculate("A", snapshot,
            query(ReportKind.ITEMS, null, 20L, null, ""), "Frozen legacy");
        assertEquals(Long.valueOf(0), cutover.getSegment().amounts.profit.completeGp);
        assertEquals(0, cutover.getSegment().soldQuantity);
    }

    @Test
    public void recipeReservationsRemainGlobalEvenWhenDeclarationIsOutsideReportPeriod()
    {
        OfferEvent buy = offer("buy", true, 2, 100, 10);
        OfferEvent sale = offer("sale", false, 2, 120, 20);
        AccountData snapshot = account("A", buy, sale);
        RecipeFlip recipe = new RecipeFlip(START.plusSeconds(100), components(sale, 1), components(buy, 1), 0);
        RecipeFlipGroup group = new RecipeFlipGroup("10:1|10:1");
        group.getRecipeFlips().add(recipe);
        snapshot.getRecipeFlipGroups().add(group);
        LegacyReport result = adapter.calculate("A", snapshot, query(ReportKind.ITEMS, 0L, 30L, null, ""), "Legacy");
        assertEquals(Long.valueOf(20), result.getSegment().amounts.profit.completeGp);
        assertEquals(1, result.getSegment().soldQuantity);
        assertEquals(1, result.getRows().get(0).quantity);
        assertEquals(2, buy.getCurrentQuantityInTrade()); // Calculator must not mutate frozen source evidence.
        assertEquals(1, recipe.getPartialOffers().get(0).amountConsumed);
    }

    @Test
    public void accountScopedDetailKeysDoNotPullAnotherAccountsMatchingItem()
    {
        AccountData snapshot = account("B", offer("buy", true, 1, 100, 10), offer("sale", false, 1, 120, 20));
        String accountAKey = LegacyReportingAdapter.itemKey("A", 10);
        LegacyReport result = adapter.calculate("B", snapshot,
            query(ReportKind.FLIPS, null, null, accountAKey, ""), "Legacy");
        assertTrue(result.getRows().isEmpty());
        assertEquals(0, result.getSegment().flipCount);
    }

    @Test
    public void detailRowsHaveTimesAndDoNotInventSourceTax()
    {
        AccountData snapshot = account("A", offer("buy", true, 1, 100, 10), offer("sale", false, 1, 120, 20));
        LegacyReport result = adapter.calculate("A", snapshot,
            query(ReportKind.FLIPS, null, null, LegacyReportingAdapter.itemKey("A", 10), "ITEM"), "Legacy");
        assertEquals(1, result.getRows().size());
        assertEquals(START.plusSeconds(20), result.getRows().get(0).occurredAt);
        assertEquals(Long.valueOf(20), result.getRows().get(0).amounts.profit.completeGp);
        assertNull(result.getRows().get(0).amounts.tax.completeGp);
        assertNull(result.getRows().get(0).detailKind);
    }

    @Test
    public void recipeReportsKeepCreationTimeAndMissingInputDoesNotInventProfit()
    {
        AccountData snapshot = new AccountData();
        OfferEvent output = offer("output", false, 1, 120, 10);
        PartialOffer missing = new PartialOffer("missing", 1);
        RecipeFlip flip = new RecipeFlip(START.plusSeconds(100), components(output, 1),
            Map.of(20, Map.of("missing", missing)), 3);
        RecipeFlipGroup group = new RecipeFlipGroup("20:1|10:1");
        group.getRecipeFlips().add(flip);
        snapshot.getRecipeFlipGroups().add(group);
        LegacyReport salePeriod = adapter.calculate("A", snapshot,
            query(ReportKind.RECIPES, 0L, 30L, null, ""), "Legacy");
        assertTrue(salePeriod.getRows().isEmpty());
        LegacyReport recordedPeriod = adapter.calculate("A", snapshot,
            query(ReportKind.RECIPE_FLIPS, 90L, 110L, null, ""), "Legacy");
        assertEquals(1, recordedPeriod.getRows().size());
        assertNull(recordedPeriod.getSegment().amounts.profit.completeGp);
        assertEquals(0, recordedPeriod.getSegment().amounts.profit.knownSubtotalGp);
        assertEquals(Long.valueOf(120), recordedPeriod.getSegment().amounts.net.completeGp);
        assertEquals(START.plusSeconds(100), recordedPeriod.getRows().get(0).occurredAt);
    }

    @Test
    public void excludesUndatedLegacyOffersWithWarningWithoutLosingKnownRecords()
    {
        OfferEvent undated = offer("undated", true, 1, 1, 0);
        undated.setTime(null);
        AccountData snapshot = account("A", undated, offer("buy", true, 1, 100, 10), offer("sale", false, 1, 120, 20));
        LegacyReport result = adapter.calculate("A", snapshot,
            query(ReportKind.ITEMS, null, null, null, ""), "Legacy");
        assertEquals(Long.valueOf(20), result.getSegment().amounts.profit.completeGp);
        assertTrue(result.getWarnings().stream().anyMatch(warning -> warning.contains("Undated")));
    }

    @Test
    public void returnsAllSearchMatchesForCoordinatorToPageOnlyOnce()
    {
        AccountData snapshot = account("A", offer("buy", true, 1, 100, 10), offer("sale", false, 1, 120, 20));
        FlippingItem second = new FlippingItem(20, "Other item", 100, "A");
        second.getHistory().getCompressedOfferEvents().addAll(Arrays.asList(
            offer("second-buy", true, 1, 50, 10), offer("second-sale", false, 1, 70, 20)));
        snapshot.getTrades().add(second);
        LegacyReport result = adapter.calculate("A", snapshot,
            query(ReportKind.ITEMS, null, null, null, "item"), "Legacy");
        assertEquals(2, result.getRows().size());
        assertEquals(Long.valueOf(40), result.getSegment().amounts.profit.completeGp);
    }

    private ReportQuery query(ReportKind kind, Long from, Long to, String groupKey, String search)
    {
        return new ReportQuery(Collections.emptyList(), from == null ? null : START.plusSeconds(from),
            to == null ? null : START.plusSeconds(to), "Test period", search, Sort.TIME, kind, groupKey,
            0, 1, "source", "projection");
    }

    private AccountData account(String name, OfferEvent... offers)
    {
        AccountData account = new AccountData();
        FlippingItem item = new FlippingItem(10, "Test item", 100, name);
        item.getHistory().getCompressedOfferEvents().addAll(Arrays.asList(offers));
        account.getTrades().add(item);
        return account;
    }

    private Map<Integer, Map<String, PartialOffer>> components(OfferEvent source, int quantity)
    {
        return Map.of(source.getItemId(), Map.of(source.getUuid(), new PartialOffer(source, quantity)));
    }

    private OfferEvent offer(String id, boolean buy, int quantity, int price, long seconds)
    {
        return new OfferEvent(id, buy, 10, quantity, price, START.plusSeconds(seconds), 0,
            buy ? GrandExchangeOfferState.BOUGHT : GrandExchangeOfferState.SOLD, 0, 10, quantity,
            null, false, "A", "Test item", price, price * quantity);
    }
}
