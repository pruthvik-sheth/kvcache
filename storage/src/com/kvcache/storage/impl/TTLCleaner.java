package com.kvcache.storage.impl;

import com.kvcache.core.model.CacheKey;
import com.kvcache.storage.model.MemTableEntry;
import com.kvcache.storage.model.StorageConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

final class TTLCleaner implements Runnable {

    private final MemTable memTable;
    private final int sampleSize;

    TTLCleaner(MemTable memTable, StorageConfig config) {
        this.memTable   = memTable;
        this.sampleSize = config.ttlCleanupSampleSize();
    }

    @Override
    public void run() {
        long nowMs = System.currentTimeMillis();

        List<CacheKey> sampled = new ArrayList<>(sampleSize);
        int seen = 0;

        for (Map.Entry<CacheKey, MemTableEntry> e : memTable.entrySet()) {
            seen++;
            if (sampled.size() < sampleSize) {
                sampled.add(e.getKey());
            } else {
                int slot = ThreadLocalRandom.current().nextInt(seen);
                if (slot < sampleSize) {
                    sampled.set(slot, e.getKey());
                }
            }
        }

        for (CacheKey key : sampled) {
            MemTableEntry current = memTable.getRaw(key);
            if (current != null && current.isExpired(nowMs)) {
                memTable.remove(key);
            }
        }
    }
}
