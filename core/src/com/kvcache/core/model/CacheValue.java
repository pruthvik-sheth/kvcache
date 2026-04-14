package com.kvcache.core.model;

import java.util.Arrays;

/**
 * A cached KV attention tensor together with its storage metadata.
 *
 * <p>{@code data} holds the raw serialised tensor bytes. LLM KV cache tensors
 * can be several megabytes each; the storage layer wraps this array in a
 * {@code MemorySegment} on the hot read path to avoid copies. This record
 * is used for the WAL write path and for inter-module communication on the
 * EventBus where the data is small or already copied.
 *
 * <p>Because {@code byte[]} uses reference equality by default, this record
 * overrides {@link #equals}, {@link #hashCode}, and {@link #toString}.
 *
 * @param data        raw tensor bytes; must not be null or empty
 * @param sizeBytes   byte length of {@code data}; must equal {@code data.length}
 * @param timestampMs epoch-millisecond timestamp of the write
 * @param ttlMs       time-to-live in milliseconds; {@code 0} means no expiry
 */
public record CacheValue(byte[] data, int sizeBytes, long timestampMs, long ttlMs) {

    public CacheValue {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("data must not be null or empty");
        }
        if (sizeBytes != data.length) {
            throw new IllegalArgumentException(
                    "sizeBytes " + sizeBytes + " does not match data.length " + data.length);
        }
        if (ttlMs < 0) {
            throw new IllegalArgumentException("ttlMs must be >= 0, got " + ttlMs);
        }
    }

    /**
     * Convenience factory that derives {@code sizeBytes} from the array length.
     */
    public static CacheValue of(byte[] data, long timestampMs, long ttlMs) {
        return new CacheValue(data, data.length, timestampMs, ttlMs);
    }

    /**
     * Returns {@code true} if this entry has expired relative to {@code nowMs}.
     * Always returns {@code false} when {@code ttlMs == 0} (no expiry).
     */
    public boolean isExpired(long nowMs) {
        return ttlMs > 0 && (timestampMs + ttlMs) < nowMs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CacheValue other)) return false;
        return sizeBytes == other.sizeBytes
                && timestampMs == other.timestampMs
                && ttlMs == other.ttlMs
                && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(data);
        result = 31 * result + Integer.hashCode(sizeBytes);
        result = 31 * result + Long.hashCode(timestampMs);
        result = 31 * result + Long.hashCode(ttlMs);
        return result;
    }

    @Override
    public String toString() {
        return "CacheValue[sizeBytes=" + sizeBytes
                + ", timestampMs=" + timestampMs
                + ", ttlMs=" + ttlMs + "]";
    }
}
