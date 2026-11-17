package com.flippingutilities.model;

import com.flippingutilities.utilities.WikiDataSource;

import java.util.Objects;

public final class TimeseriesCacheKey {
    private final int itemId;
    private final Timestep timestep;
    private final WikiDataSource source;

    public TimeseriesCacheKey(int itemId, Timestep timestep, WikiDataSource source) {
        this.itemId = itemId;
        this.timestep = timestep;
        this.source = source;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TimeseriesCacheKey that = (TimeseriesCacheKey) o;
        return itemId == that.itemId && timestep == that.timestep && source == that.source;
    }

    @Override
    public int hashCode() {
        return Objects.hash(itemId, timestep, source);
    }
}
