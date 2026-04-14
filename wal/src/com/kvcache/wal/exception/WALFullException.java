package com.kvcache.wal.exception;

import com.kvcache.core.exception.KVCacheException;

/**
 * Thrown when an append is rejected because the entry exceeds the configured
 * maximum entry size ({@code wal.max_entry_size_mb}, default 16 MB).
 *
 * <p>This is a configuration / caller error, not a disk error. The WAL file
 * itself is not affected — the entry was never written. The caller should
 * either split the value into smaller entries or increase the limit.
 *
 * <p>See design doc § "Configuration" — {@code wal.max_entry_size_mb}.
 */
public class WALFullException extends KVCacheException {

    private final int entrySizeBytes;
    private final int limitBytes;

    /**
     * @param entrySizeBytes the size of the rejected entry in bytes
     * @param limitBytes     the configured maximum entry size in bytes
     */
    public WALFullException(int entrySizeBytes, int limitBytes) {
        super("WAL entry size " + entrySizeBytes + " bytes exceeds limit of "
                + limitBytes + " bytes ("
                + (limitBytes / (1024 * 1024)) + " MB)");
        this.entrySizeBytes = entrySizeBytes;
        this.limitBytes     = limitBytes;
    }

    /** Returns the byte size of the entry that was rejected. */
    public int getEntrySizeBytes() {
        return entrySizeBytes;
    }

    /** Returns the configured maximum entry size in bytes. */
    public int getLimitBytes() {
        return limitBytes;
    }
}
