package com.flippingutilities.accounting;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import lombok.Getter;

/** Immutable accounting choice. Dates are resolved once, never relative to today's date. */
@Getter
public final class AccountingPlan
{
    public enum Mode { LEGACY, RECALCULATE, HYBRID, FRESH_START }

    private final String id;
    private final long accountId;
    private final Mode mode;
    private final Instant cutover;
    private final Instant purchaseCutoff;
    private final Map<String, Long> openingQuantities;

    public AccountingPlan(String id, long accountId, Mode mode, Instant cutover,
                          Instant purchaseCutoff, Map<String, Long> openingQuantities)
    {
        this.id = Objects.requireNonNull(id, "plan id");
        this.accountId = accountId;
        this.mode = Objects.requireNonNull(mode, "mode");
        if ((mode == Mode.HYBRID || mode == Mode.FRESH_START) && cutover == null)
        {
            throw new IllegalArgumentException("A dated accounting plan needs a cutover");
        }
        if (purchaseCutoff != null && (cutover == null || purchaseCutoff.isAfter(cutover)))
        {
            throw new IllegalArgumentException("Purchase cutoff must be on or before the cutover");
        }
        if ((mode == Mode.RECALCULATE || mode == Mode.LEGACY)
            && (cutover != null || purchaseCutoff != null || !openingQuantities.isEmpty()))
        {
            throw new IllegalArgumentException("Full-history modes do not take opening inventory");
        }
        Map<String, Long> selections = new LinkedHashMap<>();
        openingQuantities.forEach((source, quantity) ->
        {
            if (source == null || quantity == null || quantity <= 0)
            {
                throw new IllegalArgumentException("Opening selections need a source and positive quantity");
            }
            selections.put(source, quantity);
        });
        // Match SQLite's persisted precision before the preview runs, so reloading a plan
        // cannot move an observation from one side of its boundary to the other.
        this.cutover = cutover == null ? null : Instant.ofEpochMilli(cutover.toEpochMilli());
        this.purchaseCutoff = purchaseCutoff == null ? null : Instant.ofEpochMilli(purchaseCutoff.toEpochMilli());
        this.openingQuantities = Collections.unmodifiableMap(selections);
    }
}
