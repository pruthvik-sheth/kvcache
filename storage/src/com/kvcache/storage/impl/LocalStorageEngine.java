package com.kvcache.storage.impl;

import com.kvcache.core.model.CacheKey;
import com.kvcache.core.model.CacheValue;
import com.kvcache.storage.api.EvictionStrategy;
import com.kvcache.storage.api.StorageEngine;
import com.kvcache.storage.exception.EntryTooLargeException;
import com.kvcache.storage.exception.StorageException;
import com.kvcache.storage.model.MemTableEntry;
import com.kvcache.storage.model.StorageConfig;
import com.kvcache.storage.model.StorageStats;
import com.kvcache.wal.api.WAL;
import com.kvcache.wal.api.WALEntryVisitor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class LocalStorageEngine implements StorageEngine {

    private static final int WAL_VALUE_PREFIX_BYTES = 16;

    private final WAL wal;
    private final StorageConfig config;
    private final EvictionStrategy evictionStrategy;
    private final MemTable memTable;
    private final SnapshotManager snapshotManager;

    private final AtomicLong totalPuts    = new AtomicLong();
    private final AtomicLong totalGets    = new AtomicLong();
    private final AtomicLong totalDeletes = new AtomicLong();
    private final AtomicLong cacheHits    = new AtomicLong();
    private final AtomicLong cacheMisses  = new AtomicLong();
    private final AtomicLong evictionCount = new AtomicLong();

    private volatile boolean running = false;
    private ScheduledExecutorService scheduler;

    public LocalStorageEngine(WAL wal, StorageConfig config, EvictionStrategy evictionStrategy) {
        this.wal               = wal;
        this.config            = config;
        this.evictionStrategy  = evictionStrategy;
        this.memTable          = new MemTable();
        this.snapshotManager   = new SnapshotManager(config, memTable, wal);
    }

    @Override
    public void start() throws Exception {
        if (running) return;

        wal.start();
        recover();

        ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(
                1, Thread.ofVirtual().name("storage-bg").factory());
        pool.setRemoveOnCancelPolicy(true);
        scheduler = pool;

        scheduler.scheduleAtFixedRate(
                new TTLCleaner(memTable, config),
                config.ttlCleanupIntervalMs(),
                config.ttlCleanupIntervalMs(),
                TimeUnit.MILLISECONDS);

        running = true;
    }

    @Override
    public void stop() throws Exception {
        if (!running) return;
        running = false;

        scheduler.shutdown();

        try {
            snapshotManager.forceSnapshot();
        } catch (Exception ignored) {}

        snapshotManager.stop();
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

        maybeEvict();

        long now = System.currentTimeMillis();
        byte[] keyBytes = key.toWireString().getBytes(StandardCharsets.UTF_8);
        byte[] walValue = encodeWALValue(now, ttlMs, value);
        long seq = wal.appendPut(keyBytes, walValue);

        int sizeBytes = keyBytes.length + value.length + MemTableEntry.ENTRY_OVERHEAD;
        memTable.put(key, new MemTableEntry(value, now, ttlMs, now, sizeBytes));

        totalPuts.incrementAndGet();
        snapshotManager.onWALAppend();

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
        snapshotManager.onWALAppend();

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
                evictionCount.get(),
                snapshotManager.getLastSnapshotSequence(),
                snapshotManager.getEntriesSinceSnapshot());
    }

    @Override
    public void forceSnapshot() {
        try {
            snapshotManager.forceSnapshot();
        } catch (IOException e) {
            throw new StorageException("Force snapshot failed", e);
        }
    }

    @Override
    public long getCurrentSequence() {
        return wal.getCurrentSequence();
    }

    private void recover() throws IOException {
        long snapshotSeq = snapshotManager.loadLatestSnapshot();
        long replayFrom  = snapshotSeq >= 0 ? snapshotSeq + 1 : 0L;

        wal.replay(replayFrom, new WALEntryVisitor() {
            @Override
            public void onPut(long sequence, byte[] key, byte[] value) {
                if (value.length < WAL_VALUE_PREFIX_BYTES) return;

                ByteBuffer buf = ByteBuffer.wrap(value);
                long timestamp = buf.getLong();
                long ttlMs     = buf.getLong();
                byte[] raw     = new byte[value.length - WAL_VALUE_PREFIX_BYTES];
                buf.get(raw);

                CacheKey cacheKey = CacheKey.fromWireString(
                        new String(key, StandardCharsets.UTF_8));
                int sizeBytes = key.length + raw.length + MemTableEntry.ENTRY_OVERHEAD;
                memTable.loadEntry(cacheKey,
                        new MemTableEntry(raw, timestamp, ttlMs, timestamp, sizeBytes));
            }

            @Override
            public void onDelete(long sequence, byte[] key) {
                CacheKey cacheKey = CacheKey.fromWireString(
                        new String(key, StandardCharsets.UTF_8));
                memTable.remove(cacheKey);
            }
        });
    }

    private void maybeEvict() {
        if (memTable.memoryUsedBytes() <= config.evictionThresholdBytes()) return;

        while (memTable.memoryUsedBytes() > config.evictionTargetBytes()) {
            List<CacheKey> candidates = evictionStrategy.selectForEviction(
                    memTable.entrySet(), 64);

            if (candidates.isEmpty()) break;

            for (CacheKey key : candidates) {
                if (memTable.memoryUsedBytes() <= config.evictionTargetBytes()) break;
                if (memTable.remove(key) != null) {
                    evictionCount.incrementAndGet();
                }
            }
        }
    }

    private static byte[] encodeWALValue(long timestamp, long ttlMs, byte[] value) {
        ByteBuffer buf = ByteBuffer.allocate(WAL_VALUE_PREFIX_BYTES + value.length);
        buf.putLong(timestamp);
        buf.putLong(ttlMs);
        buf.put(value);
        return buf.array();
    }
}
