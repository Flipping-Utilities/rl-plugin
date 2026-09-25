package com.flippingutilities.accounting;

import com.flippingutilities.accounting.AccountingResult.Allocation;
import com.flippingutilities.accounting.AccountingResult.Kind;
import com.flippingutilities.accounting.AccountingResult.OpenLot;
import com.flippingutilities.accounting.AccountingResult.OpeningCandidate;
import com.flippingutilities.accounting.AccountingResult.Realization;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic account-local materialization. Reporting never invokes this calculator.
 * The caller publishes all returned rows atomically with its source revision and plan.
 */
public final class AccountingEngine
{
    public static final String VERSION = "margin_then_fifo_v1";

    private static final Comparator<AccountingSource> SOURCE_ORDER = Comparator
        .comparing(AccountingSource::getTime, Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparingLong(AccountingSource::getSequence);

    public AccountingResult calculate(AccountingPlan plan, List<AccountingSource> sources,
                                      List<AccountingRecipe> recipes)
    {
        Map<String, AccountingSource> sourceById = new LinkedHashMap<>();
        for (AccountingSource source : sources)
        {
            if (source.getAccountId() != plan.getAccountId())
            {
                throw new IllegalArgumentException("Cannot match sources from another account");
            }
            AccountingSource previous = sourceById.putIfAbsent(source.getId(), source);
            if (previous != null && !previous.equals(source))
            {
                throw new IllegalArgumentException("Conflicting source identity: " + source.getId());
            }
        }
        Set<String> recipeIds = new HashSet<>();
        for (AccountingRecipe recipe : recipes)
        {
            if (recipe.getAccountId() != plan.getAccountId() || !recipeIds.add(recipe.getId()))
            {
                throw new IllegalArgumentException("Recipe account or identity is invalid");
            }
        }
        List<AccountingSource> ordered = new ArrayList<>(sourceById.values());
        ordered.sort(SOURCE_ORDER); // Timed ties retain persisted ingestion order, never UUID order.
        List<AccountingRecipe> orderedRecipes = new ArrayList<>(recipes);
        orderedRecipes.sort(Comparator.comparing(AccountingRecipe::getRecordedAt,
            Comparator.nullsLast(Comparator.naturalOrder())));
        Work result = new Work();
        if (plan.getMode() == AccountingPlan.Mode.LEGACY)
        {
            return result.finish();
        }
        if (plan.getMode() == AccountingPlan.Mode.RECALCULATE)
        {
            Map<String, Balance> balances = balances(ordered);
            reserveRecipes(orderedRecipes, balances, result, false);
            match(balances, result);
            collectOpenLots(balances, result);
        }
        else
        {
            dated(plan, ordered, sourceById, orderedRecipes, result);
        }
        result.realizations.sort(Comparator
            .comparing(Realization::getRecognizedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingLong(row -> sourceById.get(row.getSaleSourceId()).getSequence()));
        return result.finish();
    }

    /**
     * Append path for a clean partition with no changed recipe reservations. The caller must
     * use full reconciliation for corrections, deletions, recipe edits and margin-check changes.
     * Returns only new realizations/allocations and the complete replacement open-lot balance.
     */
    public AccountingResult calculateAppend(AccountingPlan plan, List<AccountingSource> newSources,
                                            List<AccountingSource> lotSources, List<OpenLot> openLots,
                                            Instant previousLatestTime, long previousLatestSequence)
    {
        if (plan.getMode() == AccountingPlan.Mode.LEGACY || previousLatestTime == null)
        {
            throw new IllegalArgumentException("Append needs a new-method plan and a known source watermark");
        }
        Map<String, AccountingSource> sourceById = new HashMap<>();
        for (AccountingSource source : lotSources)
        {
            if (source.getAccountId() != plan.getAccountId() || !source.isBuy()
                || sourceById.putIfAbsent(source.getId(), source) != null)
            {
                throw new IllegalArgumentException("Invalid append inventory source");
            }
        }
        Map<String, Balance> balances = new LinkedHashMap<>();
        for (OpenLot lot : openLots)
        {
            AccountingSource source = sourceById.get(lot.getSourceId());
            if (source == null || source.isRestricted() || lot.getItemId() != source.getItemId()
                || lot.getQuantity() <= 0 || lot.getOffset() < 0 || lot.getOffset() > source.getQuantity()
                || lot.getQuantity() > source.getQuantity() - lot.getOffset()
                || (lot.getAvailableAt() != null && lot.getAvailableAt().isAfter(previousLatestTime)))
            {
                throw new IllegalArgumentException("Invalid persisted open lot");
            }
            if (balances.putIfAbsent(source.getId(), new Balance(source, lot.getOffset(),
                lot.getOffset() + lot.getQuantity(), lot.getAvailableAt(),
                source.getTime() != null && !source.getTime().equals(lot.getAvailableAt()))) != null)
            {
                throw new IllegalArgumentException("Duplicate open lot");
            }
        }
        for (AccountingSource source : newSources)
        {
            if (source.getAccountId() != plan.getAccountId() || source.getTime() == null
                || source.isMarginEligible() || source.isRestricted()
                || source.getTime().isBefore(previousLatestTime)
                || (source.getTime().equals(previousLatestTime) && source.getSequence() <= previousLatestSequence)
                || (plan.getCutover() != null && source.getTime().isBefore(plan.getCutover()))
                || sourceById.putIfAbsent(source.getId(), source) != null)
            {
                throw new IllegalArgumentException("Source requires full reconciliation instead of append");
            }
            balances.put(source.getId(), new Balance(source));
        }
        Work result = new Work();
        match(balances, result);
        collectOpenLots(balances, result);
        return result.finish();
    }

    private void dated(AccountingPlan plan, List<AccountingSource> sources,
                       Map<String, AccountingSource> sourceById, List<AccountingRecipe> recipes,
                       Work result)
    {
        Instant cutover = plan.getCutover();
        Map<String, Balance> before = new LinkedHashMap<>();
        Map<String, Balance> after = new LinkedHashMap<>();
        Set<Integer> previouslySoldItems = new HashSet<>();
        for (AccountingSource source : sources)
        {
            if (source.getTime() == null)
            {
                result.warnings.add("Undated source archived outside dated ledger: " + source.getId());
                if (source.isBuy())
                {
                    before.put(source.getId(), new Balance(source));
                }
            }
            else if (source.getTime().isBefore(cutover))
            {
                before.put(source.getId(), new Balance(source));
                if (!source.isBuy() && !source.isRestricted())
                {
                    previouslySoldItems.add(source.getItemId());
                }
            }
            else
            {
                after.put(source.getId(), new Balance(source));
            }
        }
        List<AccountingRecipe> oldRecipes = new ArrayList<>();
        List<AccountingRecipe> newRecipes = new ArrayList<>();
        List<AccountingRecipe> conflicts = new ArrayList<>();
        for (AccountingRecipe recipe : recipes)
        {
            Boundary boundary = boundary(recipe, sourceById, cutover);
            if (boundary == Boundary.BEFORE)
            {
                oldRecipes.add(recipe);
            }
            else if (boundary == Boundary.AFTER)
            {
                newRecipes.add(recipe);
            }
            else
            {
                conflicts.add(recipe);
                result.warnings.add("Recipe crosses or conflicts with the frozen boundary: " + recipe.getId());
            }
        }
        Work history = new Work();
        reserveRecipes(oldRecipes, before, history, false);
        reserveRecipes(conflicts, before, history, true);
        match(before, history); // Reconcile all earlier sales BEFORE applying the purchase cutoff.
        result.warnings.addAll(history.warnings);

        Set<String> visitedSelections = new HashSet<>();
        for (Balance balance : before.values())
        {
            AccountingSource source = balance.source;
            if (!source.isBuy())
            {
                continue;
            }
            String exclusion = null;
            if (source.isRestricted())
            {
                exclusion = "Recipe-only evidence is not ordinary inventory";
            }
            else if (source.getTime() == null)
            {
                exclusion = "Purchase date is unknown";
            }
            else if (source.getAmountGp() == null)
            {
                exclusion = "Purchase cost is unknown";
            }
            else if (plan.getPurchaseCutoff() == null || source.getTime().isBefore(plan.getPurchaseCutoff()))
            {
                exclusion = "Purchase predates the selected opening cutoff";
            }
            else if (plan.getMode() == AccountingPlan.Mode.HYBRID
                && previouslySoldItems.contains(source.getItemId()))
            {
                exclusion = "Frozen legacy sales make this item's remaining basis ambiguous";
            }
            if (balance.remaining() == 0)
            {
                exclusion = "No unconsumed purchase quantity remains";
            }
            if (balance.remaining() > 0 || plan.getOpeningQuantities().containsKey(source.getId()))
            {
                result.openingCandidates.add(new OpeningCandidate(source.getId(), source.getItemId(),
                    balance.offset, balance.remaining(), portion(source.getAmountGp(), source.getQuantity(),
                    balance.offset, balance.remaining()), source.getTime(), exclusion == null, exclusion,
                    source.isEstimated()));
            }
            Long selected = plan.getOpeningQuantities().get(source.getId());
            if (selected != null)
            {
                visitedSelections.add(source.getId());
                if (exclusion != null || selected > balance.remaining())
                {
                    // A later source correction can invalidate a formerly accepted selection.
                    // Excluding it leaves new sales incomplete rather than retaining incorrect profit.
                    result.warnings.add("Opening selection is no longer eligible: " + source.getId());
                    continue;
                }
                after.put(source.getId(), new Balance(source, balance.offset,
                    Math.addExact(balance.offset, selected), cutover, true));
            }
        }
        for (String selected : plan.getOpeningQuantities().keySet())
        {
            if (!visitedSelections.contains(selected))
            {
                result.warnings.add("Opening selection source is missing: " + selected);
            }
        }
        reserveRecipes(conflicts, after, result, true);
        reserveRecipes(newRecipes, after, result, false);
        match(after, result);
        collectOpenLots(after, result);
    }

    private enum Boundary { BEFORE, AFTER, CONFLICT }

    private Boundary boundary(AccountingRecipe recipe, Map<String, AccountingSource> sources, Instant cutover)
    {
        boolean before = false;
        boolean after = false;
        if (recipe.getOutputs().isEmpty())
        {
            return Boundary.CONFLICT;
        }
        for (AccountingRecipe.Component component : recipe.getOutputs())
        {
            AccountingSource source = sources.get(component.getSourceId());
            if (source == null || source.getTime() == null)
            {
                return Boundary.CONFLICT;
            }
            before |= source.getTime().isBefore(cutover);
            after |= !source.getTime().isBefore(cutover);
        }
        if (before && after)
        {
            return Boundary.CONFLICT;
        }
        if (before)
        {
            if (recipe.getRecordedAt() == null || !recipe.getRecordedAt().isBefore(cutover))
            {
                return Boundary.CONFLICT;
            }
            for (AccountingRecipe.Component component : recipe.getInputs())
            {
                AccountingSource source = sources.get(component.getSourceId());
                if (source == null || source.getTime() == null || !source.getTime().isBefore(cutover))
                {
                    return Boundary.CONFLICT;
                }
            }
            return Boundary.BEFORE;
        }
        return Boundary.AFTER;
    }

    private Map<String, Balance> balances(List<AccountingSource> sources)
    {
        Map<String, Balance> balances = new LinkedHashMap<>();
        sources.forEach(source -> balances.put(source.getId(), new Balance(source)));
        return balances;
    }

    private void reserveRecipes(List<AccountingRecipe> recipes, Map<String, Balance> balances,
                                Work work, boolean forceIncomplete)
    {
        Map<String, BigInteger> requested = new HashMap<>();
        for (AccountingRecipe recipe : recipes)
        {
            for (AccountingRecipe.Component component : recipe.getInputs())
            {
                requested.merge(component.getSourceId(), BigInteger.valueOf(component.getQuantity()), BigInteger::add);
            }
            for (AccountingRecipe.Component component : recipe.getOutputs())
            {
                requested.merge(component.getSourceId(), BigInteger.valueOf(component.getQuantity()), BigInteger::add);
            }
        }
        Set<String> overclaimed = new HashSet<>();
        requested.forEach((id, quantity) ->
        {
            Balance balance = balances.get(id);
            if (balance != null && quantity.compareTo(BigInteger.valueOf(balance.remaining())) > 0)
            {
                overclaimed.add(id);
            }
        });
        for (AccountingRecipe recipe : recipes)
        {
            boolean[] incomplete = {forceIncomplete
                || recipe.getInputs().stream().anyMatch(component -> overclaimed.contains(component.getSourceId()))
                || recipe.getOutputs().stream().anyMatch(component -> overclaimed.contains(component.getSourceId()))};
            List<Segment> inputs = takeComponents(recipe.getInputs(), true, balances, incomplete);
            List<Segment> outputs = takeComponents(recipe.getOutputs(), false, balances, incomplete);
            if (recipe.getInputs().isEmpty() || outputs.isEmpty())
            {
                incomplete[0] = true;
            }
            outputs.sort(Comparator.comparing(segment -> segment.source, SOURCE_ORDER));
            if (!outputs.isEmpty() && outputs.get(0).source.getTime() != null)
            {
                for (Segment input : inputs)
                {
                    if (input.source.getTime() != null && SOURCE_ORDER.compare(input.source, outputs.get(0).source) > 0)
                    {
                        incomplete[0] = true;
                    }
                }
            }
            Long inputCost = incomplete[0] ? null : 0L;
            boolean estimated = false;
            for (Segment input : inputs)
            {
                inputCost = add(inputCost, input.amount());
                estimated |= input.source.isEstimated();
            }
            BigInteger totalGross = BigInteger.ZERO;
            boolean knownWeights = true;
            for (Segment output : outputs)
            {
                Long gross = output.amount();
                knownWeights &= gross != null;
                if (gross != null)
                {
                    totalGross = totalGross.add(BigInteger.valueOf(gross));
                }
                estimated |= output.source.isEstimated();
            }
            if (incomplete[0] || inputCost == null || !knownWeights || recipe.getCoinCostGp() == null)
            {
                work.warnings.add("Recipe has unresolved components or boundary reservations: " + recipe.getId());
            }
            BigInteger offset = BigInteger.ZERO;
            String firstRealization = null;
            for (int i = 0; i < outputs.size(); i++)
            {
                Segment output = outputs.get(i);
                Long gross = output.amount();
                BigInteger next = gross == null ? offset : offset.add(BigInteger.valueOf(gross));
                Long cost = weighted(inputCost, offset, next, totalGross, knownWeights, i == outputs.size() - 1);
                Long adjustment = weighted(recipe.getCoinCostGp(), offset, next, totalGross,
                    knownWeights, i == outputs.size() - 1);
                String id = "recipe:" + recipe.getId() + ":" + output.source.getId() + ":" + output.offset;
                Realization row = row(id, "recipe:" + recipe.getId(), Kind.RECIPE, recipe.getId(), output,
                    cost == null ? 0 : output.quantity, cost, adjustment, estimated);
                work.realizations.add(row);
                work.allocations.add(output.allocation(id));
                if (firstRealization == null)
                {
                    firstRealization = id;
                }
                offset = next;
            }
            // Input lineage is reserved once, attached to the recipe's first realization.
            // Its money is apportioned over every output above, never summed from allocations.
            if (firstRealization != null)
            {
                for (Segment input : inputs)
                {
                    work.allocations.add(input.allocation(firstRealization));
                }
            }
        }
    }

    private List<Segment> takeComponents(List<AccountingRecipe.Component> components, boolean buy,
                                         Map<String, Balance> balances, boolean[] incomplete)
    {
        List<Segment> taken = new ArrayList<>();
        for (AccountingRecipe.Component component : components)
        {
            Balance balance = balances.get(component.getSourceId());
            if (balance == null || balance.source.isBuy() != buy)
            {
                incomplete[0] = true;
                continue;
            }
            if (balance.remaining() < component.getQuantity())
            {
                incomplete[0] = true;
            }
            long quantity = Math.min(balance.remaining(), component.getQuantity());
            if (quantity > 0)
            {
                taken.add(balance.take(quantity));
            }
        }
        return taken;
    }

    private void match(Map<String, Balance> balances, Work work)
    {
        List<Balance> ordered = new ArrayList<>(balances.values());
        ordered.sort(Comparator.comparing((Balance balance) -> balance.availableAt,
            Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(balance -> !balance.opening)
            .thenComparingLong(balance -> balance.source.getSequence()));

        // Whole one-unit margin checks keep priority over older ordinary inventory.
        Map<Integer, Deque<Balance>> marginBuys = new HashMap<>();
        for (Balance balance : ordered)
        {
            if (balance.source.isRestricted() || !balance.source.isMarginEligible()
                || balance.source.getQuantity() != 1 || balance.remaining() != 1 || balance.availableAt == null)
            {
                continue;
            }
            Deque<Balance> buys = marginBuys.computeIfAbsent(balance.source.getItemId(), ignored -> new ArrayDeque<>());
            if (balance.source.isBuy())
            {
                buys.addLast(balance);
            }
            else
            {
                while (!buys.isEmpty() && !buys.peekFirst().source.getTime()
                    .plusSeconds(60).isAfter(balance.source.getTime()))
                {
                    buys.removeFirst();
                }
                if (!buys.isEmpty())
                {
                    realize(buys.removeFirst().take(1), balance.take(1), Kind.MARGIN, work);
                }
            }
        }
        Map<Integer, Deque<Balance>> available = new HashMap<>();
        for (Balance balance : ordered)
        {
            if (balance.source.isRestricted() || balance.remaining() == 0)
            {
                continue;
            }
            Deque<Balance> buys = available.computeIfAbsent(balance.source.getItemId(), ignored -> new ArrayDeque<>());
            if (balance.source.isBuy())
            {
                if (balance.availableAt != null)
                {
                    buys.addLast(balance);
                }
                continue;
            }
            // An undated sale cannot be assigned an invented causal purchase ordering.
            while (balance.availableAt != null && balance.remaining() > 0 && !buys.isEmpty())
            {
                Balance buy = buys.peekFirst();
                long quantity = Math.min(buy.remaining(), balance.remaining());
                realize(buy.take(quantity), balance.take(quantity), Kind.ORDINARY, work);
                if (buy.remaining() == 0)
                {
                    buys.removeFirst();
                }
            }
            if (balance.remaining() > 0)
            {
                realize(null, balance.take(balance.remaining()), Kind.ORDINARY, work);
            }
        }
    }

    private void realize(Segment buy, Segment sale, Kind kind, Work work)
    {
        String id = "sale:" + sale.source.getId() + ":" + sale.offset;
        String flipId = (kind == Kind.MARGIN ? "margin:" : "order:") + sale.source.getOrderId();
        Long cost = buy == null ? null : buy.amount();
        work.realizations.add(row(id, flipId, kind, null, sale, cost == null ? 0 : sale.quantity,
            cost, 0L, sale.source.isEstimated() || (buy != null && buy.source.isEstimated())));
        work.allocations.add(sale.allocation(id));
        if (buy != null)
        {
            work.allocations.add(buy.allocation(id));
        }
    }

    private Realization row(String id, String flipId, Kind kind, String recipeId, Segment sale,
                            long matched, Long cost, Long adjustment, boolean estimated)
    {
        Long gross = sale.amount();
        Long tax = portion(sale.source.getTaxGp(), sale.source.getQuantity(), sale.offset, sale.quantity);
        Long net = subtract(gross, tax);
        Long profit = subtract(subtract(net, cost), adjustment);
        return new Realization(id, flipId, kind, recipeId, sale.source.getAccountId(), sale.source.getItemId(),
            sale.source.getId(), sale.source.getTime(), sale.quantity, matched, cost, gross, tax, adjustment,
            net, profit, estimated);
    }

    private void collectOpenLots(Map<String, Balance> balances, Work result)
    {
        for (Balance balance : balances.values())
        {
            if (balance.source.isBuy() && !balance.source.isRestricted() && balance.remaining() > 0)
            {
                result.openLots.add(new OpenLot(balance.source.getId(), balance.source.getItemId(),
                    balance.offset, balance.remaining(), portion(balance.source.getAmountGp(),
                    balance.source.getQuantity(), balance.offset, balance.remaining()), balance.source.getTime(),
                    balance.availableAt, balance.source.isEstimated()));
            }
        }
    }

    /** Allocates primitives first; subtracting these primitives gives conserving net and profit. */
    public static Long portion(Long total, long quantity, long offset, long length)
    {
        if (quantity <= 0 || offset < 0 || length < 0 || offset > quantity || length > quantity - offset)
        {
            throw new IllegalArgumentException("Invalid source quantity segment");
        }
        if (total == null)
        {
            return null;
        }
        return split(total, BigInteger.valueOf(offset), BigInteger.valueOf(offset + length),
            BigInteger.valueOf(quantity));
    }

    private static Long weighted(Long total, BigInteger start, BigInteger end, BigInteger weight,
                                  boolean known, boolean last)
    {
        if (total == null || !known)
        {
            return null;
        }
        return weight.signum() == 0 ? (last ? total : 0L) : split(total, start, end, weight);
    }

    private static long split(long total, BigInteger start, BigInteger end, BigInteger denominator)
    {
        BigInteger amount = BigInteger.valueOf(total);
        return floor(amount.multiply(end), denominator).subtract(floor(amount.multiply(start), denominator))
            .longValueExact();
    }

    private static BigInteger floor(BigInteger numerator, BigInteger denominator)
    {
        BigInteger[] divided = numerator.divideAndRemainder(denominator);
        return numerator.signum() < 0 && divided[1].signum() != 0
            ? divided[0].subtract(BigInteger.ONE) : divided[0];
    }

    private static Long add(Long left, Long right)
    {
        return left == null || right == null ? null : Math.addExact(left, right);
    }

    private static Long subtract(Long left, Long right)
    {
        return left == null || right == null ? null : Math.subtractExact(left, right);
    }

    private static final class Balance
    {
        private final AccountingSource source;
        private final long end;
        private final Instant availableAt;
        private final boolean opening;
        private long offset;

        private Balance(AccountingSource source)
        {
            this(source, 0, source.getQuantity(), source.getTime(), false);
        }

        private Balance(AccountingSource source, long offset, long end, Instant availableAt, boolean opening)
        {
            this.source = source;
            this.offset = offset;
            this.end = end;
            this.availableAt = availableAt;
            this.opening = opening;
        }

        private long remaining()
        {
            return end - offset;
        }

        private Segment take(long quantity)
        {
            if (quantity <= 0 || quantity > remaining())
            {
                throw new IllegalArgumentException("Allocation exceeds the source remainder");
            }
            Segment segment = new Segment(source, offset, quantity);
            offset += quantity;
            return segment;
        }
    }

    private static final class Segment
    {
        private final AccountingSource source;
        private final long offset;
        private final long quantity;

        private Segment(AccountingSource source, long offset, long quantity)
        {
            this.source = source;
            this.offset = offset;
            this.quantity = quantity;
        }

        private Long amount()
        {
            return portion(source.getAmountGp(), source.getQuantity(), offset, quantity);
        }

        private Allocation allocation(String realization)
        {
            return new Allocation(realization, source.getId(), source.isBuy(), offset, quantity, amount());
        }
    }

    private static final class Work
    {
        private final List<Realization> realizations = new ArrayList<>();
        private final List<Allocation> allocations = new ArrayList<>();
        private final List<OpenLot> openLots = new ArrayList<>();
        private final List<OpeningCandidate> openingCandidates = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        private AccountingResult finish()
        {
            return new AccountingResult(realizations, allocations, openLots, openingCandidates, warnings);
        }
    }
}
