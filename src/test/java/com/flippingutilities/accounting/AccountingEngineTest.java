package com.flippingutilities.accounting;

import com.flippingutilities.accounting.AccountingPlan.Mode;
import com.flippingutilities.accounting.AccountingRecipe.Component;
import com.flippingutilities.accounting.AccountingResult.Kind;
import com.flippingutilities.accounting.AccountingResult.Realization;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class AccountingEngineTest
{
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private final AccountingEngine engine = new AccountingEngine();

    @Test
    public void recognizesSaleUsingEarlierPeriodCostWithoutRoundingAverage()
    {
        AccountingSource buy = source("buy", true, 2, 201L, 0L, 0);
        AccountingSource sell = source("sell", false, 2, 300L, 3L, 86400);
        AccountingResult result = calculate(buy, sell);
        assertEquals(1, result.getRealizations().size());
        Realization row = result.getRealizations().get(0);
        assertEquals(START.plusSeconds(86400), row.getRecognizedAt());
        assertEquals(Long.valueOf(96), row.getProfitGp());
        assertEquals(Long.valueOf(201), row.getCostGp());
    }

    @Test
    public void primitiveRemaindersConserveMoneyAcrossSeveralSales()
    {
        AccountingResult result = calculate(source("buy", true, 3, 301L, 0L, 0),
            source("sale1", false, 1, 150L, 1L, 10), source("sale2", false, 2, 305L, 3L, 20));
        assertEquals(301, result.getRealizations().stream().mapToLong(Realization::getCostGp).sum());
        assertEquals(150, result.getRealizations().stream().mapToLong(Realization::getProfitGp).sum());
        assertEquals(Long.valueOf(100), result.getRealizations().get(0).getCostGp());
        assertEquals(Long.valueOf(201), result.getRealizations().get(1).getCostGp());
        for (Realization row : result.getRealizations())
        {
            assertEquals(row.getGrossGp() - row.getTaxGp() - row.getCostGp() - row.getAdjustmentGp(),
                row.getProfitGp().longValue());
        }
    }

    @Test
    public void neverUsesFuturePurchasesAndDoesNotLoseUnknownProceeds()
    {
        AccountingResult result = calculate(source("sale", false, 1, 120L, 2L, 0),
            source("buy", true, 1, 100L, 0L, 60));
        assertNull(result.getRealizations().get(0).getProfitGp());
        assertEquals(Long.valueOf(118), result.getRealizations().get(0).getNetGp());
        assertEquals(1, result.getOpenLots().get(0).getQuantity());
    }

    @Test
    public void knownZeroCostIsNotMissingCost()
    {
        AccountingResult known = calculate(source("buy", true, 1, 0L, 0L, 0),
            source("sale", false, 1, 10L, 0L, 1));
        assertEquals(Long.valueOf(10), known.getRealizations().get(0).getProfitGp());
        AccountingResult unknown = calculate(source("buy", true, 1, null, 0L, 0),
            source("sale", false, 1, 10L, 0L, 1));
        assertNull(unknown.getRealizations().get(0).getProfitGp());
    }

    @Test
    public void marginPairsTakePriorityButOrdinaryRemainderStillUsesFifo()
    {
        AccountingSource old = source("old", true, 1, 100L, 0L, 0);
        AccountingSource check = margin("check", true, 200L, 100);
        AccountingSource checkSale = margin("check-sale", false, 220L, 101);
        AccountingSource sale = source("sale", false, 1, 150L, 0L, 200);
        AccountingResult result = calculate(old, check, checkSale, sale);
        assertEquals(Kind.MARGIN, result.getRealizations().get(0).getKind());
        assertEquals(Long.valueOf(20), result.getRealizations().get(0).getProfitGp());
        assertEquals(Long.valueOf(50), result.getRealizations().get(1).getProfitGp());
    }

    @Test
    public void equalTimestampOrderUsesPersistedSequence()
    {
        AccountingSource buy = new AccountingSource("z", "z", 1, 10, true, 1, 100L, 0L,
            START, 2, false, false, false);
        AccountingSource sell = new AccountingSource("a", "a", 1, 10, false, 1, 120L, 0L,
            START, 1, false, false, false);
        assertNull(calculate(buy, sell).getRealizations().get(0).getProfitGp());
    }

    @Test
    public void freshOpeningReconcilesEarlierSalesBeforeFilteringPurchases()
    {
        AccountingPlan plan = dated(Mode.FRESH_START, 100, 50L, Map.of("buy", 2L));
        AccountingResult result = engine.calculate(plan, Arrays.asList(
            source("buy", true, 10, 1001L, 0L, 60),
            source("past-sale", false, 6, 900L, 0L, 80),
            source("sale", false, 3, 450L, 0L, 110)), Collections.emptyList());
        assertEquals(4, result.getOpeningCandidates().get(0).getQuantity());
        assertEquals(6, result.getOpeningCandidates().get(0).getOffset());
        assertEquals(Long.valueOf(401), result.getOpeningCandidates().get(0).getCostGp());
        assertEquals(2, result.getRealizations().size());
        assertEquals(Long.valueOf(100), result.getRealizations().get(0).getProfitGp());
        assertNull(result.getRealizations().get(1).getProfitGp());
    }

    @Test
    public void hybridExcludesAnyItemWithEarlierSalesEvenIfFifoShowsRemainder()
    {
        AccountingResult result = engine.calculate(dated(Mode.HYBRID, 100, 0L, Map.of("buy2", 2L)),
            Arrays.asList(source("buy1", true, 2, 200L, 0L, 10),
                source("buy2", true, 2, 220L, 0L, 20),
                source("old-sale", false, 2, 300L, 0L, 30),
                source("sale", false, 2, 300L, 0L, 110)), Collections.emptyList());
        assertFalse(result.getOpeningCandidates().get(0).isEligible());
        assertNull(result.getRealizations().get(0).getProfitGp());
        assertFalse(result.getWarnings().isEmpty());
    }

    @Test
    public void cutoffDoesNotDiscardSalesThatConsumeOlderLots()
    {
        AccountingResult result = engine.calculate(dated(Mode.FRESH_START, 100, 50L, Map.of()),
            Arrays.asList(source("old", true, 2, 200L, 0L, 10),
                source("new", true, 2, 220L, 0L, 60),
                source("sale", false, 3, 450L, 0L, 80)), Collections.emptyList());
        assertEquals(1, result.getOpeningCandidates().size());
        assertEquals("new", result.getOpeningCandidates().get(0).getSourceId());
        assertEquals(1, result.getOpeningCandidates().get(0).getQuantity());
        assertEquals(Long.valueOf(110), result.getOpeningCandidates().get(0).getCostGp());
        assertTrue(result.getOpenLots().isEmpty()); // No implicit confirmation of the remaining unit.
    }

    @Test
    public void missingTimeRemainsUndatedAndCannotFundDatedSale()
    {
        AccountingSource buy = new AccountingSource("buy", "buy", 1, 10, true, 1, 100L, 0L,
            null, 0, false, false, true);
        AccountingSource sale = new AccountingSource("sale", "sale", 1, 10, false, 1, 120L, 0L,
            null, 1, false, false, true);
        AccountingResult result = calculate(buy, sale);
        assertNull(result.getRealizations().get(0).getRecognizedAt());
        assertNull(result.getRealizations().get(0).getProfitGp());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsCrossAccountSources()
    {
        AccountingSource source = new AccountingSource("other", "other", 2, 10, true, 1,
            100L, 0L, START, 0, false, false, false);
        calculate(source);
    }

    @Test
    public void recipeReservesInputsAndRecognizesAtEachOutputSale()
    {
        AccountingRecipe recipe = new AccountingRecipe("r", 1, START.plusSeconds(1000),
            List.of(new Component("input", 2)),
            List.of(new Component("output1", 1), new Component("output2", 1)), 3L);
        AccountingResult result = engine.calculate(recalculate(), Arrays.asList(
            source("input", true, 2, 101L, 0L, 0),
            source("output1", false, 1, 100L, 1L, 10),
            source("output2", false, 1, 200L, 2L, 20)), List.of(recipe));
        assertEquals(2, result.getRealizations().size());
        assertEquals(Long.valueOf(33), result.getRealizations().get(0).getCostGp());
        assertEquals(Long.valueOf(68), result.getRealizations().get(1).getCostGp());
        assertEquals(Long.valueOf(1), result.getRealizations().get(0).getAdjustmentGp());
        assertEquals(Long.valueOf(2), result.getRealizations().get(1).getAdjustmentGp());
        assertEquals(193, result.getRealizations().stream().mapToLong(Realization::getProfitGp).sum());
        assertEquals(START.plusSeconds(10), result.getRealizations().get(0).getRecognizedAt());
        assertTrue(result.getOpenLots().isEmpty());
    }

    @Test
    public void recipeZeroValueOutputsAssignCostAndSignedAdjustmentToLastSale()
    {
        AccountingRecipe recipe = new AccountingRecipe("r", 1, START.plusSeconds(100),
            List.of(new Component("input", 1)),
            List.of(new Component("one", 1), new Component("two", 1)), -3L);
        AccountingResult result = engine.calculate(recalculate(), Arrays.asList(
            source("input", true, 1, 10L, 0L, 0),
            source("one", false, 1, 0L, 0L, 10), source("two", false, 1, 0L, 0L, 20)), List.of(recipe));
        assertEquals(Long.valueOf(0), result.getRealizations().get(0).getProfitGp());
        assertEquals(Long.valueOf(-7), result.getRealizations().get(1).getProfitGp());
    }

    @Test
    public void recipeSpanningCutoverIsQuarantinedWithoutDuplicatingSales()
    {
        AccountingRecipe recipe = new AccountingRecipe("r", 1, START.plusSeconds(200),
            List.of(new Component("input", 2)),
            List.of(new Component("old-output", 1), new Component("new-output", 1)), 0L);
        AccountingResult result = engine.calculate(dated(Mode.HYBRID, 100, 0L, Map.of("input", 2L)),
            Arrays.asList(source("input", true, 2, 100L, 0L, 0),
                source("old-output", false, 1, 100L, 0L, 80),
                source("new-output", false, 1, 100L, 0L, 110)), List.of(recipe));
        assertEquals(1, result.getRealizations().size());
        assertEquals(Kind.RECIPE, result.getRealizations().get(0).getKind());
        assertNull(result.getRealizations().get(0).getProfitGp());
        assertEquals(1, result.getRealizations().get(0).getQuantity());
        assertTrue(result.getOpenLots().isEmpty());
    }

    @Test
    public void recipeInputsNeedExplicitSelectedOpeningStock()
    {
        AccountingRecipe recipe = new AccountingRecipe("r", 1, START.plusSeconds(200),
            List.of(new Component("input", 1)), List.of(new Component("output", 1)), 0L);
        List<AccountingSource> sources = Arrays.asList(source("input", true, 1, 100L, 0L, 80),
            source("output", false, 1, 150L, 0L, 110));
        AccountingResult excluded = engine.calculate(dated(Mode.FRESH_START, 100, 0L, Map.of()),
            sources, List.of(recipe));
        assertNull(excluded.getRealizations().get(0).getProfitGp());
        AccountingResult carried = engine.calculate(dated(Mode.FRESH_START, 100, 0L, Map.of("input", 1L)),
            sources, List.of(recipe));
        assertEquals(Long.valueOf(50), carried.getRealizations().get(0).getProfitGp());
    }

    @Test
    public void detachedRecipeEvidenceNeverBecomesOrdinaryInventory()
    {
        AccountingSource detached = new AccountingSource("detached", "detached", 1, 10, true,
            10, 100L, 0L, START, 0, false, true, true);
        AccountingResult result = calculate(detached, source("sale", false, 1, 30L, 0L, 10));
        assertNull(result.getRealizations().get(0).getProfitGp());
        assertTrue(result.getOpenLots().isEmpty());
    }

    @Test
    public void overclaimedRecipeInputMakesEveryConflictingRecipeIncomplete()
    {
        AccountingRecipe first = new AccountingRecipe("r1", 1, START.plusSeconds(200),
            List.of(new Component("input", 1)), List.of(new Component("one", 1)), 0L);
        AccountingRecipe second = new AccountingRecipe("r2", 1, START.plusSeconds(201),
            List.of(new Component("input", 1)), List.of(new Component("two", 1)), 0L);
        AccountingResult result = engine.calculate(recalculate(), Arrays.asList(
            source("input", true, 1, 100L, 0L, 0),
            source("one", false, 1, 150L, 0L, 10), source("two", false, 1, 150L, 0L, 20)),
            Arrays.asList(first, second));
        assertEquals(2, result.getRealizations().size());
        assertNull(result.getRealizations().get(0).getProfitGp());
        assertNull(result.getRealizations().get(1).getProfitGp());
        assertEquals(1, result.getAllocations().stream().filter(AccountingResult.Allocation::isBuy)
            .mapToLong(AccountingResult.Allocation::getQuantity).sum());
    }

    @Test
    public void recipeCannotAssignFuturePurchaseCostToEarlierOutput()
    {
        AccountingRecipe recipe = new AccountingRecipe("r", 1, START.plusSeconds(200),
            List.of(new Component("input", 1)), List.of(new Component("output", 1)), 0L);
        AccountingResult result = engine.calculate(recalculate(), Arrays.asList(
            source("output", false, 1, 150L, 0L, 0), source("input", true, 1, 100L, 0L, 10)), List.of(recipe));
        assertNull(result.getRealizations().get(0).getProfitGp());
    }

    @Test
    public void recipeAndOrdinaryReservationsConserveSharedSourceQuantityAndCost()
    {
        AccountingRecipe recipe = new AccountingRecipe("r", 1, START.plusSeconds(200),
            List.of(new Component("buy", 1)), List.of(new Component("sale", 1)), 0L);
        AccountingResult result = engine.calculate(recalculate(), Arrays.asList(
            source("buy", true, 3, 301L, 0L, 0),
            source("sale", false, 3, 451L, 4L, 10)), List.of(recipe));
        assertEquals(3, result.getRealizations().stream().mapToLong(Realization::getQuantity).sum());
        assertEquals(301, result.getRealizations().stream().mapToLong(Realization::getCostGp).sum());
        assertEquals(451, result.getRealizations().stream().mapToLong(Realization::getGrossGp).sum());
        assertEquals(4, result.getRealizations().stream().mapToLong(Realization::getTaxGp).sum());
        assertEquals(146, result.getRealizations().stream().mapToLong(Realization::getProfitGp).sum());
        assertEquals(3, result.getAllocations().stream().filter(AccountingResult.Allocation::isBuy)
            .mapToLong(AccountingResult.Allocation::getQuantity).sum());
    }

    @Test
    public void openingInventoryIsAvailableExactlyAtCutoverWithoutMovingItsAcquisitionDate()
    {
        AccountingSource buy = source("buy", true, 1, 100L, 0L, 10);
        AccountingSource sale = source("sale", false, 1, 120L, 0L, 100);
        AccountingResult result = engine.calculate(dated(Mode.FRESH_START, 100, 0L, Map.of("buy", 1L)),
            Arrays.asList(buy, sale), List.of());
        assertEquals(Long.valueOf(20), result.getRealizations().get(0).getProfitGp());
        assertEquals(buy.getTime(), result.getOpeningCandidates().get(0).getAcquiredAt());
        assertEquals(sale.getTime(), result.getRealizations().get(0).getRecognizedAt());
    }

    @Test
    public void splittingUsesWideIntermediatesAndConservesNegativeAdjustments()
    {
        assertEquals(Long.valueOf(Long.MAX_VALUE), AccountingEngine.portion(Long.MAX_VALUE,
            Long.MAX_VALUE, 0, Long.MAX_VALUE));
        assertEquals(Long.valueOf(-1), AccountingEngine.portion(-2L, 3, 0, 1));
        assertEquals(Long.valueOf(-1), AccountingEngine.portion(-2L, 3, 1, 1));
        assertEquals(Long.valueOf(0), AccountingEngine.portion(-2L, 3, 2, 1));
    }

    @Test
    public void replayIsStableAndLegacyDoesNotGenerateAlternativeFacts()
    {
        AccountingSource buy = source("buy", true, 1, 100L, 0L, 0);
        AccountingSource sale = source("sale", false, 1, 120L, 0L, 10);
        assertEquals(calculate(buy, sale).getRealizations(), calculate(buy, sale).getRealizations());
        AccountingResult legacy = engine.calculate(new AccountingPlan("p", 1, Mode.LEGACY,
            null, null, Map.of()), Arrays.asList(buy, sale), Collections.emptyList());
        assertTrue(legacy.getRealizations().isEmpty());
    }

    @Test
    public void cleanAppendPreservesSourceOffsetsAndMatchesFullReplay()
    {
        AccountingSource buy = source("buy", true, 3, 301L, 0L, 0);
        AccountingSource oldSale = source("old", false, 1, 120L, 0L, 10);
        AccountingSource newSale = source("new", false, 2, 300L, 3L, 20);
        AccountingResult previous = calculate(buy, oldSale);
        AccountingResult append = engine.calculateAppend(recalculate(), List.of(newSale), List.of(buy),
            previous.getOpenLots(), oldSale.getTime(), oldSale.getSequence());
        AccountingResult replay = calculate(buy, oldSale, newSale);
        assertEquals(replay.getRealizations().subList(1, 2), append.getRealizations());
        assertEquals(replay.getOpenLots(), append.getOpenLots());
    }

    @Test(expected = IllegalArgumentException.class)
    public void appendRejectsNewMarginPairThatCouldReclassifyOldHistory()
    {
        engine.calculateAppend(recalculate(), List.of(margin("sale", false, 120L, 20)),
            List.of(), List.of(), START, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void appendRejectsLatePurchaseThatCouldChangeEarlierAllocations()
    {
        engine.calculateAppend(recalculate(), List.of(source("buy", true, 1, 100L, 0L, 10)),
            List.of(), List.of(), START.plusSeconds(20), 20);
    }

    @Test
    public void variedIncrementalLedgersEqualReplayAndConserveEverySource()
    {
        for (int seed = 0; seed < 25; seed++)
        {
            Random random = new Random(seed);
            List<AccountingSource> sources = new ArrayList<>();
            Map<String, AccountingSource> byId = new HashMap<>();
            List<Realization> accumulated = new ArrayList<>();
            List<AccountingResult.Allocation> allocations = new ArrayList<>();
            AccountingResult previous = null;
            for (int sequence = 0; sequence < 40; sequence++)
            {
                boolean buy = sequence == 0 || random.nextBoolean();
                long quantity = 1 + random.nextInt(7);
                Long amount = buy && random.nextInt(12) == 0 ? null : (long) random.nextInt(10000);
                Long tax = buy ? 0L : (long) random.nextInt((int) (amount / 10 + 1));
                AccountingSource source = new AccountingSource("s" + sequence, "o" + sequence, 1,
                    10 + random.nextInt(3), buy, quantity, amount, tax, START.plusSeconds(sequence), sequence,
                    false, false, false);
                sources.add(source);
                byId.put(source.getId(), source);
                if (previous == null)
                {
                    previous = engine.calculate(recalculate(), sources, List.of());
                }
                else
                {
                    List<AccountingSource> lotSources = new ArrayList<>();
                    previous.getOpenLots().forEach(lot -> lotSources.add(byId.get(lot.getSourceId())));
                    previous = engine.calculateAppend(recalculate(), List.of(source), lotSources,
                        previous.getOpenLots(), START.plusSeconds(sequence - 1), sequence - 1);
                }
                accumulated.addAll(previous.getRealizations());
                allocations.addAll(previous.getAllocations());
                AccountingResult replay = engine.calculate(recalculate(), sources, List.of());
                assertEquals("Realizations, seed " + seed + " sequence " + sequence,
                    replay.getRealizations(), accumulated);
                assertEquals(replay.getOpenLots(), previous.getOpenLots());
                for (AccountingSource recorded : sources)
                {
                    long consumed = allocations.stream().filter(allocation -> allocation.getSourceId().equals(recorded.getId()))
                        .mapToLong(AccountingResult.Allocation::getQuantity).sum();
                    long remaining = previous.getOpenLots().stream().filter(lot -> lot.getSourceId().equals(recorded.getId()))
                        .mapToLong(AccountingResult.OpenLot::getQuantity).sum();
                    assertEquals(recorded.getQuantity(), consumed + remaining);
                    if (recorded.getAmountGp() != null)
                    {
                        long allocatedMoney = allocations.stream().filter(allocation -> allocation.getSourceId().equals(recorded.getId()))
                            .mapToLong(AccountingResult.Allocation::getAmountGp).sum();
                        long openMoney = previous.getOpenLots().stream().filter(lot -> lot.getSourceId().equals(recorded.getId()))
                            .mapToLong(AccountingResult.OpenLot::getCostGp).sum();
                        assertEquals(recorded.getAmountGp().longValue(), allocatedMoney + openMoney);
                    }
                }
            }
        }
    }

    private AccountingResult calculate(AccountingSource... sources)
    {
        return engine.calculate(recalculate(), Arrays.asList(sources), Collections.emptyList());
    }

    private AccountingPlan recalculate()
    {
        return new AccountingPlan("p", 1, Mode.RECALCULATE, null, null, Map.of());
    }

    private AccountingPlan dated(Mode mode, long cutover, Long cutoff, Map<String, Long> opening)
    {
        return new AccountingPlan("p", 1, mode, START.plusSeconds(cutover),
            cutoff == null ? null : START.plusSeconds(cutoff), opening);
    }

    private AccountingSource source(String id, boolean buy, long quantity, Long amount, Long tax, long seconds)
    {
        return new AccountingSource(id, id, 1, 10, buy, quantity, amount, tax,
            START.plusSeconds(seconds), seconds, false, false, false);
    }

    private AccountingSource margin(String id, boolean buy, long amount, long seconds)
    {
        return new AccountingSource(id, id, 1, 10, buy, 1, amount, 0L,
            START.plusSeconds(seconds), seconds, true, false, false);
    }
}
