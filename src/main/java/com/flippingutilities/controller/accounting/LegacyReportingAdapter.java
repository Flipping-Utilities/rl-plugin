package com.flippingutilities.controller.accounting;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.Flip;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.RecipeFlipGroup;
import com.flippingutilities.ui.accounting.AccountingUiService.Amounts;
import com.flippingutilities.ui.accounting.AccountingUiService.Money;
import com.flippingutilities.ui.accounting.AccountingUiService.ReportKind;
import com.flippingutilities.ui.accounting.AccountingUiService.ReportQuery;
import com.flippingutilities.ui.accounting.AccountingUiService.ReportRow;
import com.flippingutilities.ui.accounting.AccountingUiService.Segment;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.Getter;

/**
 * Compatibility calculator over a caller-owned SQLite snapshot, including a frozen hybrid snapshot.
 * This deliberately filters offers before invoking the historical matching algorithms.
 */
public final class LegacyReportingAdapter
{
    public static final String VERSION = "legacy_interval_v1";

    /** Returns all matching rows. The coordinator sorts and pages once across account/method segments. */
    public LegacyReport calculate(String account, AccountData snapshot, ReportQuery query, String methodLabel)
    {
        List<ReportRow> rows = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Totals totals = new Totals();
        if (!query.accounts.isEmpty() && !query.accounts.contains(account))
        {
            return new LegacyReport(new Segment(account, methodLabel, query.periodLabel, totals.amounts(),
                0, 0, 0), rows, warnings);
        }
        if (query.kind == ReportKind.ITEMS || query.kind == ReportKind.FLIPS)
        {
            ordinary(account, snapshot, query, methodLabel, rows, warnings, totals);
            warnings.add("Legacy reports filter offers before matching. Sale activity and matched profit use different bases; "
                + "rounded flip rows can differ from the legacy summary.");
        }
        else
        {
            recipes(account, snapshot, query, methodLabel, rows, warnings, totals);
            warnings.add("Legacy recipes recognize profit when recorded, rather than when their outputs were sold.");
        }
        return new LegacyReport(new Segment(account, methodLabel, query.periodLabel, totals.amounts(),
            totals.count, totals.quantity, totals.unknownQuantity), rows, warnings);
    }

    public static String itemKey(String account, int itemId)
    {
        return account + ":item:" + itemId;
    }

    public static String recipeKey(String account, String recipeKey)
    {
        return account + ":recipe:" + recipeKey;
    }

    private void ordinary(String account, AccountData snapshot, ReportQuery query, String method,
                          List<ReportRow> rows, List<String> warnings, Totals total)
    {
        for (FlippingItem item : snapshot.getTrades())
        {
            String key = itemKey(account, item.getItemId());
            String title = item.getItemName() == null ? "Item " + item.getItemId() : item.getItemName();
            if (!matches(title, query.search) || (query.groupKey != null && !query.groupKey.equals(key)))
            {
                continue;
            }
            Map<String, PartialOffer> reservations = reservations(snapshot, item.getItemId());
            List<OfferEvent> adjusted = new ArrayList<>();
            long missing = 0;
            long unknownQuantity = 0;
            Instant latest = null;
            for (OfferEvent offer : item.getHistory().getCompressedOfferEvents())
            {
                if (offer == null || offer.getTime() == null)
                {
                    warnings.add("Undated legacy offer excluded for " + account + ": " + title);
                    continue;
                }
                if (!inPeriod(offer.getTime(), query))
                {
                    continue;
                }
                latest = latest == null || offer.getTime().isAfter(latest) ? offer.getTime() : latest;
                PartialOffer reservation = reservations.get(offer.getUuid());
                OfferEvent remaining = reservation == null ? offer : reservation.toRemainingOfferEvent();
                if (remaining == null || remaining.getTime() == null)
                {
                    missing++;
                    unknownQuantity = Math.addExact(unknownQuantity, offer.getCurrentQuantityInTrade());
                    warnings.add("Legacy recipe reservation has missing offer details: " + offer.getUuid());
                }
                else
                {
                    adjusted.add(remaining);
                }
            }
            if (adjusted.isEmpty() && missing == 0)
            {
                continue;
            }
            List<Flip> flips = FlippingItem.getFlips(adjusted);
            long cost = FlippingItem.getValueOfMatchedOffers(adjusted, true);
            long profit = FlippingItem.getProfit(adjusted);
            long gross = 0;
            long tax = 0;
            long net = 0;
            long sold = 0;
            for (OfferEvent offer : adjusted)
            {
                if (!offer.isBuy())
                {
                    sold = Math.addExact(sold, offer.getCurrentQuantityInTrade());
                    gross = Math.addExact(gross, Math.multiplyExact((long) offer.getCurrentQuantityInTrade(),
                        offer.getPreTaxPrice()));
                    net = Math.addExact(net, Math.multiplyExact((long) offer.getCurrentQuantityInTrade(), offer.getPrice()));
                    tax = Math.addExact(tax, offer.getTaxPaid());
                }
            }
            Amounts amounts = new Amounts(money(profit, missing), money(cost, missing), money(gross, missing),
                money(tax, missing), money(net, missing));
            long quantity = FlippingItem.countFlipQuantity(adjusted);
            total.add(amounts, flips.size(), sold, unknownQuantity);
            if (query.kind == ReportKind.ITEMS)
            {
                rows.add(new ReportRow(key, account, method, title,
                    "Legacy matched units; activity includes unmatched sales", amounts, quantity, flips.size(),
                    ReportKind.FLIPS, key, latest));
            }
            else
            {
                for (int i = 0; i < flips.size(); i++)
                {
                    Flip flip = flips.get(i);
                    long flipCost = Math.multiplyExact((long) flip.getBuyPrice(), flip.getQuantity());
                    long flipNet = Math.multiplyExact((long) flip.getSellPrice(), flip.getQuantity());
                    Amounts flipAmounts = new Amounts(money(Math.subtractExact(flipNet, flipCost), 0),
                        money(flipCost, 0), money(0, 1), money(0, 1), money(flipNet, 0));
                    rows.add(new ReportRow(key + ":" + i, account, method, title,
                        (flip.isMarginCheck() ? "Margin check" : "Legacy flip")
                            + (flip.isOngoing() ? "; ongoing" : "")
                            + "; source tax unavailable on rounded legacy rows",
                        flipAmounts, flip.getQuantity(), 1, null, null, flip.getTime()));
                }
            }
        }
    }

    private Map<String, PartialOffer> reservations(AccountData snapshot, int itemId)
    {
        Map<String, PartialOffer> result = new HashMap<>();
        for (RecipeFlipGroup group : snapshot.getRecipeFlipGroups())
        {
            // Global reservation scope is intentional: the recipe date is not the offer's report period.
            group.getOfferIdToPartialOffer(itemId).forEach((id, component) ->
            {
                PartialOffer previous = result.get(id);
                if (previous == null)
                {
                    result.put(id, component.clone());
                }
                else
                {
                    previous.amountConsumed = Math.addExact(previous.amountConsumed, component.amountConsumed);
                }
            });
        }
        return result;
    }

    private void recipes(String account, AccountData snapshot, ReportQuery query, String method,
                         List<ReportRow> rows, List<String> warnings, Totals total)
    {
        int groupIndex = 0;
        for (RecipeFlipGroup group : snapshot.getRecipeFlipGroups())
        {
            String rawKey = group.getRecipeKey() == null ? "unresolved:" + groupIndex : group.getRecipeKey();
            groupIndex++;
            String key = recipeKey(account, rawKey);
            String title = group.getNameForSearch();
            if (!matches(title, query.search) || (query.groupKey != null && !query.groupKey.equals(key)))
            {
                continue;
            }
            Totals groupTotal = new Totals();
            long executions = 0;
            Instant latest = null;
            int index = 0;
            for (RecipeFlip flip : group.getRecipeFlips())
            {
                int rowIndex = index++;
                if (flip.getTimeOfCreation() == null)
                {
                    warnings.add("Undated legacy recipe excluded for " + account + ": " + title);
                    continue;
                }
                if (!inPeriod(flip.getTimeOfCreation(), query))
                {
                    continue;
                }
                latest = latest == null || flip.getTimeOfCreation().isAfter(latest) ? flip.getTimeOfCreation() : latest;
                long inputMissing = missing(flip.getInputs());
                long outputMissing = missing(flip.getOutputs());
                long net = flip.getRevenue();
                long tax = flip.getTaxPaid();
                long cost = flip.getExpense();
                Amounts amounts = new Amounts(money(inputMissing + outputMissing == 0 ? flip.getProfit() : 0,
                    inputMissing + outputMissing),
                    money(cost, inputMissing), money(Math.addExact(net, tax), outputMissing),
                    money(tax, outputMissing), money(net, outputMissing));
                long sold = flip.getOutputs().values().stream().flatMap(values -> values.values().stream())
                    .mapToLong(component -> component.amountConsumed).sum();
                long count = flip.getRecipeCountMade(group.getRecipe());
                executions = Math.addExact(executions, count);
                groupTotal.add(amounts, 1, sold, outputMissing + inputMissing > 0 ? sold : 0);
                if (query.kind == ReportKind.RECIPE_FLIPS)
                {
                    rows.add(new ReportRow(key + ":" + rowIndex, account, method, title,
                        "Recorded " + flip.getTimeOfCreation() + "; estimated recipe execution count", amounts,
                        count, 1, null, null, flip.getTimeOfCreation()));
                }
            }
            if (groupTotal.count == 0)
            {
                continue;
            }
            Amounts amounts = groupTotal.amounts();
            total.add(amounts, groupTotal.count, groupTotal.quantity, groupTotal.unknownQuantity);
            if (query.kind == ReportKind.RECIPES)
            {
                rows.add(new ReportRow(key, account, method, title, "Legacy recipe executions (estimated)",
                    amounts, executions, groupTotal.count, ReportKind.RECIPE_FLIPS, key, latest));
            }
        }
    }

    private long missing(Map<Integer, Map<String, PartialOffer>> components)
    {
        return components.values().stream().flatMap(values -> values.values().stream())
            .filter(component -> component.amountConsumed > 0 && component.getOffer() == null).count();
    }

    private boolean inPeriod(Instant time, ReportQuery query)
    {
        // The exclusive lower bound is part of the preserved legacy behavior.
        return (query.fromInclusive == null || time.isAfter(query.fromInclusive))
            && (query.toExclusive == null || time.isBefore(query.toExclusive));
    }

    private boolean matches(String title, String search)
    {
        return search == null || search.isEmpty()
            || title.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT));
    }

    private static Money money(long known, long unknown)
    {
        return new Money(unknown == 0 ? known : null, known, unknown, true);
    }

    @Getter
    public static final class LegacyReport
    {
        private final Segment segment;
        private final List<ReportRow> rows;
        private final List<String> warnings;

        private LegacyReport(Segment segment, List<ReportRow> rows, List<String> warnings)
        {
            this.segment = segment;
            this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        }
    }

    private static final class Totals
    {
        private final long[] known = new long[5];
        private final long[] unknown = new long[5];
        private long count;
        private long quantity;
        private long unknownQuantity;

        private void add(Amounts amounts, long count, long quantity, long unknownQuantity)
        {
            Money[] values = {amounts.profit, amounts.cost, amounts.gross, amounts.tax, amounts.net};
            for (int i = 0; i < values.length; i++)
            {
                known[i] = Math.addExact(known[i], values[i].knownSubtotalGp);
                unknown[i] = Math.addExact(unknown[i], values[i].unknownCount);
            }
            this.count = Math.addExact(this.count, count);
            this.quantity = Math.addExact(this.quantity, quantity);
            this.unknownQuantity = Math.addExact(this.unknownQuantity, unknownQuantity);
        }

        private Amounts amounts()
        {
            return new Amounts(money(known[0], unknown[0]), money(known[1], unknown[1]),
                money(known[2], unknown[2]), money(known[3], unknown[3]), money(known[4], unknown[4]));
        }
    }
}
