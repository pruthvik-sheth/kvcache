package com.kvcache.storage.impl;

import com.kvcache.core.model.CacheKey;
import com.kvcache.storage.model.MemTableEntry;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class MemTable {

    private final ConcurrentHashMap<CacheKey, MemTableEntry> map = new ConcurrentHashMap<>();
    private final AtomicLong memoryUsed = new AtomicLong();

    MemTableEntry put(CacheKey key, MemTableEntry entry) {
        MemTableEntry old = map.put(key, entry);
        long delta = (long) entry.sizeBytes() - (old != null ? old.sizeBytes() : 0L);
        memoryUsed.addAndGet(delta);
        return old;
    }

    MemTableEntry get(CacheKey key, long nowMs) {
        MemTableEntry entry = map.get(key);
        if (entry == null) return null;

        if (entry.isExpired(nowMs)) {
            if (map.remove(key, entry)) {
                memoryUsed.addAndGet(-entry.sizeBytes());
            }
            return null;
        }

        MemTableEntry updated = entry.withAccessTime(nowMs);
        map.replace(key, entry, updated);
        return updated;
    }

    MemTableEntry getRaw(CacheKey key) {
        return map.get(key);
    }

    MemTableEntry remove(CacheKey key) {
        MemTableEntry old = map.remove(key);
        if (old != null) {
            memoryUsed.addAndGet(-old.sizeBytes());
        }
        return old;
    }

    boolean contains(CacheKey key) {
        return map.containsKey(key);
    }

    long memoryUsedBytes() {
        return memoryUsed.get();
    }

    int size() {
        return map.size();
    }

    Set<Map.Entry<CacheKey, MemTableEntry>> entrySet() {
        return map.entrySet();
    }

    void loadEntry(CacheKey key, MemTableEntry entry) {
        map.put(key, entry);
        memoryUsed.addAndGet(entry.sizeBytes());
    }

    void clear() {
        map.clear();
        memoryUsed.set(0);
    }
}
