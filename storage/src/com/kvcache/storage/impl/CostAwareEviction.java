package com.kvcache.storage.impl;

import com.kvcache.core.model.CacheKey;
import com.kvcache.storage.api.EvictionStrategy;
import com.kvcache.storage.model.MemTableEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public final class CostAwareEviction implements EvictionStrategy {

    private final int sampleSize;

    public CostAwareEviction(int sampleSize) {
        if (sampleSize <= 0) throw new IllegalArgumentException("sampleSize must be > 0");
        this.sampleSize = sampleSize;
    }

    @Override
    public List<CacheKey> selectForEviction(
            Iterable<Map.Entry<CacheKey, MemTableEntry>> entries,
            int maxEntries) {

        List<Map.Entry<CacheKey, MemTableEntry>> sample = new ArrayList<>(sampleSize);
        int seen = 0;

        for (Map.Entry<CacheKey, MemTableEntry> entry : entries) {
            seen++;
            if (sample.size() < sampleSize) {
                sample.add(entry);
            } else {
                int slot = ThreadLocalRandom.current().nextInt(seen);
                if (slot < sampleSize) {
                    sample.set(slot, entry);
                }
            }
        }

        sample.sort(Comparator.comparingLong(e -> cost(e.getKey())));

        List<CacheKey> result = new ArrayList<>(Math.min(maxEntries, sample.size()));
        for (int i = 0; i < Math.min(maxEntries, sample.size()); i++) {
            result.add(sample.get(i).getKey());
        }
        return result;
    }

    private static long cost(CacheKey key) {
        return (long) key.tokenPosition() * (key.layerIndex() + 1);
    }
}
