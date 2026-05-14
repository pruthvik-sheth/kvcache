package com.kvcache.storage.exception;

import com.kvcache.core.exception.KVCacheException;

public class EntryTooLargeException extends KVCacheException {

    private final long entrySizeBytes;
    private final long maxEntrySizeBytes;

    public EntryTooLargeException(long entrySizeBytes, long maxEntrySizeBytes) {
        super(String.format(
                "entry size %d bytes exceeds maximum allowed size of %d bytes",
                entrySizeBytes, maxEntrySizeBytes));
        this.entrySizeBytes = entrySizeBytes;
        this.maxEntrySizeBytes = maxEntrySizeBytes;
    }

    public long entrySizeBytes() {
        return entrySizeBytes;
    }

    public long maxEntrySizeBytes() {
        return maxEntrySizeBytes;
    }
}
