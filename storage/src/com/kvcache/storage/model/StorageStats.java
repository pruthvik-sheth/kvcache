package com.kvcache.storage.model;

public record StorageStats(
        long entryCount,
        long memoryUsedBytes,
        long maxMemoryBytes,
        double memoryUsagePercent,
        long totalPuts,
        long totalGets,
        long totalDeletes,
        long cacheHits,
        long cacheMisses,
        double hitRate,
        long evictionCount,
        long lastSnapshotSequence,
        long entriesSinceSnapshot) {}
