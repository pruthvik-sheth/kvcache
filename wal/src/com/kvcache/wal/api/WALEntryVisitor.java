package com.kvcache.wal.api;

/**
 * Callback interface used during WAL replay to apply log entries to the
 * in-memory store without buffering all entries in a list first.
 *
 * <p>The visitor pattern avoids loading the entire log into memory: the
 * {@code WALReader} calls these methods one entry at a time as it reads
 * from disk. The {@code StorageEngine} provides an implementation that
 * applies each entry directly to its in-memory HashMap.
 *
 * <p>See design doc § "Why a Visitor pattern for replay?" and
 * § "Implementation Classes — WALReader".
 *
 * <p>Implementations must be safe to call repeatedly from a single thread.
 * The {@code WALReader} does not call these methods concurrently.
 */
public interface WALEntryVisitor {

    /**
     * Called for each valid PUT entry encountered during replay, in
     * ascending sequence order.
     *
     * @param sequence the log sequence number of this entry
     * @param key      the raw key bytes as written by the original append
     * @param value    the raw value bytes as written by the original append
     */
    void onPut(long sequence, byte[] key, byte[] value);

    /**
     * Called for each valid DELETE entry encountered during replay, in
     * ascending sequence order.
     *
     * @param sequence the log sequence number of this entry
     * @param key      the raw key bytes as written by the original append
     */
    void onDelete(long sequence, byte[] key);
}
