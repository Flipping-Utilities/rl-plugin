package com.flippingutilities.utilities;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MathUtilsTest
{
    @Test
    public void roundedQuotientRoundsToTheNearestResultInsteadOfTruncating()
    {
        //a true per-item profit of 1.88 gp must not display as 1 gp
        assertEquals(2L, MathUtils.roundedQuotient(47_031_100L, 24_999_927L));
        assertEquals(-2L, MathUtils.roundedQuotient(-47_031_100L, 24_999_927L));

        assertEquals(1L, MathUtils.roundedQuotient(1_200L, 1_000L));
        assertEquals(2L, MathUtils.roundedQuotient(1_800L, 1_000L));
        assertEquals(0L, MathUtils.roundedQuotient(400L, 1_000L));

        //trillions over millions stay precise in double arithmetic
        assertEquals(361_445L, MathUtils.roundedQuotient(9_036_100_000_000L, 24_999_927L));
    }
}
