package com.flippingutilities.utilities;

import java.time.Instant;

public interface Searchable {
    boolean isInInterval(TimeRange range);

    default boolean isInInterval(Instant intervalStart) {
        return isInInterval(TimeRange.from(intervalStart));
    }

    String getNameForSearch();
}
