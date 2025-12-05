package com.flippingutilities.model;

import java.util.Objects;

public final class TimeseriesCacheKey {
    private final int itemId;
    private final Timestep timestep;

    public TimeseriesCacheKey(int itemId, Timestep timestep) {
        this.itemId = itemId;
        this.timestep = timestep;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TimeseriesCacheKey that = (TimeseriesCacheKey) o;
        return itemId == that.itemId && timestep == that.timestep;
    }

    @Override
    public int hashCode() {
        return Objects.hash(itemId, timestep);
    }
}
