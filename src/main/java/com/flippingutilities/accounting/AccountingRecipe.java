package com.flippingutilities.accounting;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import lombok.Getter;
import lombok.Value;

@Getter
public final class AccountingRecipe
{
    private final String id;
    private final long accountId;
    private final Instant recordedAt;
    private final List<Component> inputs;
    private final List<Component> outputs;
    private final Long coinCostGp;

    public AccountingRecipe(String id, long accountId, Instant recordedAt, List<Component> inputs,
                            List<Component> outputs, Long coinCostGp)
    {
        this.id = Objects.requireNonNull(id, "recipe id");
        this.accountId = accountId;
        this.recordedAt = recordedAt;
        this.inputs = Collections.unmodifiableList(new ArrayList<>(inputs));
        this.outputs = Collections.unmodifiableList(new ArrayList<>(outputs));
        this.coinCostGp = coinCostGp;
    }

    @Value
    public static class Component
    {
        String sourceId;
        long quantity;

        public Component(String sourceId, long quantity)
        {
            this.sourceId = Objects.requireNonNull(sourceId, "component source");
            if (quantity <= 0)
            {
                throw new IllegalArgumentException("Recipe component quantity must be positive");
            }
            this.quantity = quantity;
        }
    }
}
