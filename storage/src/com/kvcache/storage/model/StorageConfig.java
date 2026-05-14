package com.kvcache.storage.model;

public record StorageConfig(
        long maxMemoryBytes,
        int evictionThresholdPercent,
        int evictionTargetPercent,
        String evictionStrategy,
        int evictionSampleSize,
        String snapshotDirectory,
        int snapshotTriggerEntries,
        int snapshotKeepCount,
        long ttlCleanupIntervalMs,
        int ttlCleanupSampleSize,
        long maxEntrySizeBytes) {

    private static final long MB = 1024L * 1024L;

    public StorageConfig {
        if (maxMemoryBytes <= 0) throw new IllegalArgumentException("maxMemoryBytes must be > 0");
        if (evictionThresholdPercent <= evictionTargetPercent)
            throw new IllegalArgumentException(
                    "evictionThresholdPercent must be > evictionTargetPercent");
        if (evictionThresholdPercent > 100 || evictionTargetPercent < 0)
            throw new IllegalArgumentException("eviction percents must be in [0, 100]");
        if (evictionSampleSize <= 0)
            throw new IllegalArgumentException("evictionSampleSize must be > 0");
        if (snapshotDirectory == null || snapshotDirectory.isBlank())
            throw new IllegalArgumentException("snapshotDirectory must not be blank");
        if (snapshotTriggerEntries <= 0)
            throw new IllegalArgumentException("snapshotTriggerEntries must be > 0");
        if (snapshotKeepCount < 1)
            throw new IllegalArgumentException("snapshotKeepCount must be >= 1");
        if (ttlCleanupIntervalMs <= 0)
            throw new IllegalArgumentException("ttlCleanupIntervalMs must be > 0");
        if (ttlCleanupSampleSize <= 0)
            throw new IllegalArgumentException("ttlCleanupSampleSize must be > 0");
        if (maxEntrySizeBytes <= 0)
            throw new IllegalArgumentException("maxEntrySizeBytes must be > 0");
    }

    public long evictionThresholdBytes() {
        return maxMemoryBytes * evictionThresholdPercent / 100;
    }

    public long evictionTargetBytes() {
        return maxMemoryBytes * evictionTargetPercent / 100;
    }

    public static StorageConfig defaultConfig(String snapshotDirectory) {
        return new StorageConfig(
                64 * MB,
                85,
                75,
                "lru",
                16,
                snapshotDirectory,
                100_000,
                2,
                30_000L,
                100,
                16 * MB);
    }
}
