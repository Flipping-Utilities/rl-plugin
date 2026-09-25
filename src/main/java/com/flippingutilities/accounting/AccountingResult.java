package com.flippingutilities.accounting;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Getter;
import lombok.Value;

/** Rebuildable facts. A null profit is incomplete, never zero. */
@Getter
public final class AccountingResult
{
    private final List<Realization> realizations;
    private final List<Allocation> allocations;
    private final List<OpenLot> openLots;
    private final List<OpeningCandidate> openingCandidates;
    private final List<String> warnings;

    public AccountingResult(List<Realization> realizations, List<Allocation> allocations,
                            List<OpenLot> openLots, List<OpeningCandidate> openingCandidates,
                            List<String> warnings)
    {
        this.realizations = immutable(realizations);
        this.allocations = immutable(allocations);
        this.openLots = immutable(openLots);
        this.openingCandidates = immutable(openingCandidates);
        this.warnings = immutable(warnings);
    }

    private static <T> List<T> immutable(List<T> source)
    {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    public enum Kind { ORDINARY, MARGIN, RECIPE }

    @Value
    public static class Realization
    {
        String id;
        String flipId;
        Kind kind;
        String recipeId;
        long accountId;
        int itemId;
        String saleSourceId;
        Instant recognizedAt;
        long quantity;
        long matchedQuantity;
        Long costGp;
        Long grossGp;
        Long taxGp;
        Long adjustmentGp;
        Long netGp;
        Long profitGp;
        boolean estimated;
    }

    @Value
    public static class Allocation
    {
        String realizationId;
        String sourceId;
        boolean buy;
        long offset;
        long quantity;
        Long amountGp;
    }

    @Value
    public static class OpenLot
    {
        String sourceId;
        int itemId;
        long offset;
        long quantity;
        Long costGp;
        Instant acquiredAt;
        Instant availableAt;
        boolean estimated;
    }

    @Value
    public static class OpeningCandidate
    {
        String sourceId;
        int itemId;
        long offset;
        long quantity;
        Long costGp;
        Instant acquiredAt;
        boolean eligible;
        String exclusionReason;
        boolean estimated;
    }
}
