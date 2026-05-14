package com.kvcache.storage.impl;

import com.kvcache.core.model.CacheKey;
import com.kvcache.core.model.CacheValue;
import com.kvcache.storage.api.StorageEngine;
import com.kvcache.storage.exception.EntryTooLargeException;
import com.kvcache.storage.model.MemTableEntry;
import com.kvcache.storage.model.StorageConfig;
import com.kvcache.storage.model.StorageStats;
import com.kvcache.wal.impl.InMemoryWAL;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

public final class InMemoryStorageEngine implements StorageEngine {

    private final StorageConfig config;
    private final InMemoryWAL wal;
    private final MemTable memTable;

    private final AtomicLong totalPuts    = new AtomicLong();
    private final AtomicLong totalGets    = new AtomicLong();
    private final AtomicLong totalDeletes = new AtomicLong();
    private final AtomicLong cacheHits    = new AtomicLong();
    private final AtomicLong cacheMisses  = new AtomicLong();

    private volatile boolean running = false;

    public InMemoryStorageEngine() {
        this(StorageConfig.defaultConfig("/tmp/kvcache-snapshots-unused"));
    }

    public InMemoryStorageEngine(StorageConfig config) {
        this.config   = config;
        this.wal      = new InMemoryWAL();
        this.memTable = new MemTable();
    }

    @Override
    public void start() {
        if (running) return;
        wal.start();
        running = true;
    }

    @Override
    public void stop() {
        if (!running) return;
        running = false;
        wal.stop();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public long put(CacheKey key, byte[] value, long ttlMs) {
        if (value.length > config.maxEntrySizeBytes()) {
            throw new EntryTooLargeException(value.length, config.maxEntrySizeBytes());
        }

        byte[] keyBytes = key.toWireString().getBytes(StandardCharsets.UTF_8);
        long seq = wal.appendPut(keyBytes, value);

        long now = System.currentTimeMillis();
        int sizeBytes = keyBytes.length + value.length + MemTableEntry.ENTRY_OVERHEAD;
        memTable.put(key, new MemTableEntry(value, now, ttlMs, now, sizeBytes));

        totalPuts.incrementAndGet();
        return seq;
    }

    @Override
    public long put(CacheKey key, byte[] value) {
        return put(key, value, 0L);
    }

    @Override
    public CacheValue get(CacheKey key) {
        totalGets.incrementAndGet();
        MemTableEntry entry = memTable.get(key, System.currentTimeMillis());
        if (entry == null) {
            cacheMisses.incrementAndGet();
            return null;
        }
        cacheHits.incrementAndGet();
        return CacheValue.of(entry.value(), entry.timestamp(), entry.ttlMs());
    }

    @Override
    public boolean delete(CacheKey key) {
        byte[] keyBytes = key.toWireString().getBytes(StandardCharsets.UTF_8);
        wal.appendDelete(keyBytes);
        MemTableEntry removed = memTable.remove(key);
        totalDeletes.incrementAndGet();
        return removed != null;
    }

    @Override
    public boolean contains(CacheKey key) {
        return memTable.contains(key);
    }

    @Override
    public StorageStats getStats() {
        long hits   = cacheHits.get();
        long misses = cacheMisses.get();
        long total  = hits + misses;
        double hitRate = total == 0 ? Double.NaN : (double) hits / total;
        long memUsed = memTable.memoryUsedBytes();
        long maxMem  = config.maxMemoryBytes();
        return new StorageStats(
                memTable.size(),
                memUsed,
                maxMem,
                maxMem > 0 ? (double) memUsed / maxMem * 100.0 : 0.0,
                totalPuts.get(),
                totalGets.get(),
                totalDeletes.get(),
                hits,
                misses,
                hitRate,
                0L,
                -1L,
                0L);
    }

    @Override
    public void forceSnapshot() {}

    @Override
    public long getCurrentSequence() {
        return wal.getCurrentSequence();
    }

    public InMemoryWAL wal() {
        return wal;
    }
}
