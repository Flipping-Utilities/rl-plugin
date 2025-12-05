package com.flippingutilities.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * lru (least recently used) cache implementation using LinkedHashMap.
 * automatically removes the least recently accessed entry when max size is exceeded.
 * access order is maintained - both get and put operations update access time.
 */
public final class LruLinkedHashMap<K, V> extends LinkedHashMap<K, V> {
    /** default load factor for hash map resizing */
    private static final float LOAD_FACTOR = 0.75f;
    /** maximum number of entries before oldest entry is removed */
    private final int maxSize;

    /**
     * creates an lru cache with the specified maximum size.
     * @param maxSize maximum number of entries to keep in the cache
     */
    public LruLinkedHashMap(int maxSize) {
        super(maxSize, LOAD_FACTOR, true);
        this.maxSize = maxSize;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > maxSize;
    }
}
