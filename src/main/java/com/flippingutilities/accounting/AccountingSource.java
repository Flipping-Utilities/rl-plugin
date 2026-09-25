package com.flippingutilities.accounting;

import java.time.Instant;
import java.util.Objects;
import lombok.Value;

/** An observed filled batch, not an order's cumulative quantity. Null money/time stays unknown. */
@Value
public class AccountingSource
{
    String id;
    String orderId;
    long accountId;
    int itemId;
    boolean buy;
    long quantity;
    Long amountGp;
    Long taxGp;
    Instant time;
    long sequence;
    boolean marginEligible;
    boolean restricted;
    boolean estimated;

    public AccountingSource(String id, String orderId, long accountId, int itemId, boolean buy,
                            long quantity, Long amountGp, Long taxGp, Instant time, long sequence,
                            boolean marginEligible, boolean restricted, boolean estimated)
    {
        this.id = Objects.requireNonNull(id, "source id");
        this.orderId = Objects.requireNonNull(orderId, "order id");
        if (quantity <= 0 || (amountGp != null && amountGp < 0) || (taxGp != null && taxGp < 0))
        {
            throw new IllegalArgumentException("Batch quantity must be positive and primitive amounts nonnegative");
        }
        this.accountId = accountId;
        this.itemId = itemId;
        this.buy = buy;
        this.quantity = quantity;
        this.amountGp = amountGp;
        this.taxGp = taxGp;
        this.time = time;
        this.sequence = sequence;
        this.marginEligible = marginEligible;
        this.restricted = restricted;
        this.estimated = estimated;
    }
}
