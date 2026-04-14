package com.kvcache.wal.impl;

import com.kvcache.wal.api.WAL;
import com.kvcache.wal.api.WALEntry;
import com.kvcache.wal.api.WALEntryVisitor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory WAL implementation for use in tests.
 *
 * <p>Stores entries in an {@link ArrayList}. No files, no CRC, no segments.
 * Other modules' tests use this as the WAL dependency so they don't need a
 * real filesystem or worry about file cleanup between test runs.
 *
 * <p>All public methods are {@code synchronized} so that multi-threaded tests
 * can safely share one instance.
 *
 * <p>See design doc § "InMemoryWAL" and § "Implementation Classes".
 */
public final class InMemoryWAL implements WAL {

    private final List<LogEntry>  entries  = new ArrayList<>();
    private final AtomicLong      sequence = new AtomicLong(0);
    private volatile boolean      running  = false;

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    public synchronized void start() {
        running = true;
    }

    @Override
    public synchronized void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // =========================================================================
    // Write operations
    // =========================================================================

    @Override
    public synchronized long appendPut(byte[] key, byte[] value) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("key must not be null or empty");
        }
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        long seq = sequence.incrementAndGet();
        entries.add(new LogEntry(seq, WALEntry.OP_PUT, key.clone(), value.clone()));
        return seq;
    }

    @Override
    public synchronized long appendDelete(byte[] key) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("key must not be null or empty");
        }
        long seq = sequence.incrementAndGet();
        entries.add(new LogEntry(seq, WALEntry.OP_DELETE, key.clone(), new byte[0]));
        return seq;
    }

    // =========================================================================
    // Replay
    // =========================================================================

    /**
     * Delivers all entries with sequence ≥ {@code fromSequence} to the visitor,
     * in the order they were appended.
     *
     * <p>The visitor receives defensive copies of the key and value arrays so
     * that mutations in the visitor cannot affect stored state.
     */
    @Override
    public synchronized void replay(long fromSequence, WALEntryVisitor visitor) {
        for (LogEntry entry : entries) {
            if (entry.sequence() >= fromSequence) {
                switch (entry.opType()) {
                    case WALEntry.OP_PUT ->
                            visitor.onPut(entry.sequence(), entry.key().clone(), entry.value().clone());
                    case WALEntry.OP_DELETE ->
                            visitor.onDelete(entry.sequence(), entry.key().clone());
                }
            }
        }
    }

    // =========================================================================
    // Other WAL operations
    // =========================================================================

    /** No-op: nothing to sync in memory. */
    @Override
    public void sync() { }

    /**
     * Removes all entries with sequence ≤ {@code upToSequence}, simulating
     * what the on-disk compactor does for sealed segments.
     */
    @Override
    public synchronized void truncate(long upToSequence) {
        entries.removeIf(e -> e.sequence() <= upToSequence);
    }

    @Override
    public long getCurrentSequence() {
        return sequence.get();
    }

    // =========================================================================
    // Test helpers
    // =========================================================================

    /** Returns the number of entries currently stored (for test assertions). */
    public synchronized int size() {
        return entries.size();
    }

    /** Clears all entries and resets the sequence counter (for test setup). */
    public synchronized void reset() {
        entries.clear();
        sequence.set(0);
    }

    // =========================================================================
    // Internal record
    // =========================================================================

    /**
     * A single stored log entry. Package-private to allow test code in the
     * same package to inspect raw entries when needed.
     */
    record LogEntry(long sequence, byte opType, byte[] key, byte[] value) { }
}
