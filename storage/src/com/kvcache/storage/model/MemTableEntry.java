package com.kvcache.storage.model;

public record MemTableEntry(
        byte[] value,
        long timestamp,
        long ttlMs,
        long lastAccessTime,
        int sizeBytes) {

    public static final int ENTRY_OVERHEAD = 64;

    public boolean isExpired(long nowMs) {
        return ttlMs > 0 && (timestamp + ttlMs) < nowMs;
    }

    public MemTableEntry withAccessTime(long nowMs) {
        return new MemTableEntry(value, timestamp, ttlMs, nowMs, sizeBytes);
    }
}
