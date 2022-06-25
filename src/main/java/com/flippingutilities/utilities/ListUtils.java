package com.flippingutilities.utilities;

import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.IntStream;

public class ListUtils {
    /**
     * Partition a list of items into n sublists based on n conditions passed in. Perhaps this should be a static method?
     * The first condition puts items that meet its criteria in the first arraylist in the sublists array, the nth
     * conditions puts the items in the nth arraylist in the sublists array.
     *
     * @param items      to partition into sub lists
     * @param conditions conditions to partition on
     * @return
     */
    public static <T> List<T>[] partition(List<T> items, Predicate<T>... conditions) {
        List<T>[] subLists = new ArrayList[conditions.length];

        IntStream.range(0, subLists.length).forEach(i -> subLists[i] = new ArrayList<>());

        for (T item : items) {
            for (int i = 0; i < conditions.length; i++) {
                if (conditions[i].test(item)) {
                    subLists[i].add(item);
                }
            }
        }
        return subLists;
    }

    /**
     * Guava has a util function for converting a list to a map, but you are only
     * allowed to provide it a function which generates the keys of the map...it will
     * use the values of the list as the values of the map
     * <p>
     * This method allows you to provide a function that generates a k,v pair based on
     * a value in the provided list
     */
    public static <K, V, L> Map<K, V> toMap(
        List<L> values,
        Function<L, Pair<K, V>> keyValueFunction) {
        Map<K, V> m = new HashMap<>();
        values.forEach(l -> {
            Pair<K, V> pair = keyValueFunction.apply(l);
            m.put(pair.getKey(), pair.getValue());
        });
        return m;
    }
}
