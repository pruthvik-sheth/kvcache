package com.kvcache.wal.api;

import com.kvcache.core.api.Lifecycle;
import com.kvcache.wal.exception.WALCorruptionException;
import com.kvcache.wal.exception.WALFullException;

/**
 * Public interface for the Write-Ahead Log.
 *
 * <p>Every cache mutation (put or delete) must be appended here and
 * acknowledged before the in-memory store is updated. On node restart the
 * WAL is replayed via {@link #replay} to rebuild in-memory state.
 *
 * <p>Extends {@link Lifecycle}: {@code start()} opens the active segment file
 * and launches the background sync thread; {@code stop()} flushes, fsyncs,
 * and closes all files cleanly.
 *
 * <p>Thread safety: {@code appendPut} and {@code appendDelete} are safe to
 * call from multiple threads — the implementation serialises writers with a
 * single {@code ReentrantLock}. {@code replay} is intended to be called
 * single-threaded during node startup before traffic is accepted.
 *
 * <p>See design doc § "API (Public Interface)" and § "Thread Safety".
 */
public interface WAL extends Lifecycle {

    /**
     * Appends a PUT operation to the log.
     *
     * <p>The call blocks until the entry is written to the OS page cache.
     * Whether it is also fsynced depends on the configured sync interval
     * (see design doc § "Fsync Policy").
     *
     * @param key   UTF-8 encoded cache key; must not be null or empty;
     *              maximum length 1 KB (see design doc § "Entry Format")
     * @param value serialised tensor bytes; must not be null;
     *              maximum length controlled by {@code wal.max_entry_size_mb}
     * @return the sequence number assigned to this entry (≥ 1, strictly
     *         increasing across all appends on this node)
     * @throws WALFullException       if the entry exceeds the configured
     *                                maximum entry size
     * @throws WALCorruptionException if the underlying file cannot be written
     */
    long appendPut(byte[] key, byte[] value);

    /**
     * Appends a DELETE operation to the log.
     *
     * <p>On the wire, DELETE entries have {@code value_length = 0} and no
     * value bytes (see design doc § "Entry Format").
     *
     * @param key UTF-8 encoded cache key; must not be null or empty
     * @return the sequence number assigned to this entry
     * @throws WALFullException       if the entry exceeds the configured size limit
     * @throws WALCorruptionException if the underlying file cannot be written
     */
    long appendDelete(byte[] key);

    /**
     * Replays all valid WAL entries whose sequence number is ≥
     * {@code fromSequence}, calling {@code visitor} for each in order.
     *
     * <p>If a corrupted entry is encountered (CRC mismatch or truncated bytes),
     * replay stops at that point in the segment and continues from the next
     * segment — see design doc § "Corruption Handling".
     *
     * <p>This method is intended to be called once during node startup
     * (see design doc § "Recovery Sequence") before {@link #start()} completes
     * its startup phase.
     *
     * @param fromSequence inclusive lower bound; pass {@code 0} to replay
     *                     everything
     * @param visitor      called once per valid entry in sequence order
     */
    void replay(long fromSequence, WALEntryVisitor visitor);

    /**
     * Returns the sequence number most recently assigned by this WAL instance.
     * Returns {@code 0} if no entries have been appended yet.
     */
    long getCurrentSequence();

    /**
     * Forces an fsync of the active segment, blocking until the OS confirms
     * the data is on durable storage.
     *
     * <p>Normally called by the background sync thread on its configured
     * interval. Callers that need stronger durability guarantees (e.g.
     * replication checkpointing) may call this directly.
     */
    void sync();

    /**
     * Deletes all sealed segments whose entries are entirely at or below
     * {@code upToSequence}.
     *
     * <p>Safe to call after a snapshot has recorded all state up to
     * {@code upToSequence} — those WAL entries are no longer needed for
     * recovery. The active segment is never deleted.
     *
     * <p>See design doc § "WALCompactor" and § "Segment lifecycle".
     *
     * @param upToSequence inclusive upper bound for deletion
     */
    void truncate(long upToSequence);
}
