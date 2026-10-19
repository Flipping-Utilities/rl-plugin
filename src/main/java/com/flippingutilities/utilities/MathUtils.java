package com.flippingutilities.utilities;

public class MathUtils {

    public static long gcd(long a, long b) {
        while (b > 0) {
            long temp = b;
            b = a % b;
            a = temp;
        }
        return a;
    }

    public static long lcm(long a, long b) {
        return a * (b / gcd(a, b));
    }

    /**
     * Divides two longs, rounding to the nearest result instead of truncating toward zero.
     * Per-item averages such as profit each rely on this: truncating turns a true value of
     * 1.88 gp/ea into "1 gp/ea", dropping almost half the profit from the display.
     */
    public static long roundedQuotient(long dividend, long divisor) {
        return Math.round((double) dividend / divisor);
    }

}
