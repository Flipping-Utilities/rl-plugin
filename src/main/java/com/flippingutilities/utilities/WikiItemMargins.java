package com.flippingutilities.utilities;

import com.google.gson.annotations.JsonAdapter;
import lombok.Data;

/**
 * Latest prices for one item from the wiki API (v2 /osrs/latest).
 *
 * The wiki states prices may exceed 32 bit integers (over max cash), so high/low are Long.
 * A null high/low means the wiki has never seen that side traded; consumers that need an
 * int (offer prices, GE listings cannot exceed max cash) use the saturating capped
 * accessors.
 */
@Data
public class WikiItemMargins {
    @JsonAdapter(LenientLongAdapter.class)
    Long high;

    @JsonAdapter(LenientLongAdapter.class)
    Long highTime;

    @JsonAdapter(LenientLongAdapter.class)
    Long low;

    @JsonAdapter(LenientLongAdapter.class)
    Long lowTime;

    /** True when the wiki has an instant-buy (high) price for this item. */
    public boolean hasHigh() {
        return high != null && high > 0;
    }

    /** True when the wiki has an instant-sell (low) price for this item. */
    public boolean hasLow() {
        return low != null && low > 0;
    }

    /**
     * The instant-buy price saturated into an int (GE offers cannot exceed max cash, so
     * clamping preserves comparison semantics). 0 when the wiki has no data.
     */
    public int getHighCapped() {
        return cap(high);
    }

    /** The instant-sell price saturated into an int; 0 when the wiki has no data. */
    public int getLowCapped() {
        return cap(low);
    }

    private static int cap(Long value) {
        if (value == null) {
            return 0;
        }
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }
}
