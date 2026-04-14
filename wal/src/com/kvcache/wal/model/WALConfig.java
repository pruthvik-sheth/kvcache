package com.kvcache.wal.model;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable configuration for a {@code WALWriter} instance.
 *
 * <p>All size and interval values match the defaults documented in the design
 * doc (§ "Configuration"). Override them via {@link Builder} when the defaults
 * are not appropriate for the deployment.
 *
 * @param directory          directory where segment files are stored
 * @param segmentSizeBytes   rotate to a new segment when the active one
 *                           reaches this size (default 64 MiB)
 * @param syncIntervalMs     call {@code fsync} every N milliseconds on a
 *                           background thread; {@code 0} means fsync after
 *                           every individual append (default 100 ms)
 * @param maxEntrySizeBytes  reject appends whose total serialised size exceeds
 *                           this limit (default 16 MiB)
 */
public record WALConfig(
        Path directory,
        long segmentSizeBytes,
        long syncIntervalMs,
        int  maxEntrySizeBytes) {

    /** 64 MiB — design doc § "Log Segmentation". */
    public static final long DEFAULT_SEGMENT_SIZE_BYTES  = 64L * 1024 * 1024;
    /** 100 ms — design doc § "Fsync Policy". */
    public static final long DEFAULT_SYNC_INTERVAL_MS    = 100L;
    /** 16 MiB — design doc § "Configuration". */
    public static final int  DEFAULT_MAX_ENTRY_SIZE_BYTES = 16 * 1024 * 1024;

    public WALConfig {
        Objects.requireNonNull(directory, "directory must not be null");
        if (segmentSizeBytes <= 0) {
            throw new IllegalArgumentException(
                    "segmentSizeBytes must be > 0, got " + segmentSizeBytes);
        }
        if (syncIntervalMs < 0) {
            throw new IllegalArgumentException(
                    "syncIntervalMs must be >= 0, got " + syncIntervalMs);
        }
        if (maxEntrySizeBytes <= 0) {
            throw new IllegalArgumentException(
                    "maxEntrySizeBytes must be > 0, got " + maxEntrySizeBytes);
        }
    }

    /** Returns a config with all defaults applied to the given directory. */
    public static WALConfig withDefaults(Path directory) {
        return new WALConfig(
                directory,
                DEFAULT_SEGMENT_SIZE_BYTES,
                DEFAULT_SYNC_INTERVAL_MS,
                DEFAULT_MAX_ENTRY_SIZE_BYTES);
    }
}
