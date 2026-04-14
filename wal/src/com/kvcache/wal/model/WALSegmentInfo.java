package com.kvcache.wal.model;

import java.nio.file.Path;

/**
 * Immutable metadata describing one WAL segment file.
 *
 * <p>A segment file is a fixed-size append-only binary file containing a
 * contiguous run of {@code WALEntry} records. Only one segment is ACTIVE
 * at a time; all others are SEALED (immutable).
 *
 * <p>Naming convention (design doc § "Naming convention"):
 * {@code segment-XXXXXXXX.wal} where {@code XXXXXXXX} is the zero-padded
 * first sequence number in the segment, e.g. {@code segment-00000001.wal}.
 * Lexicographic sort order equals chronological order.
 *
 * <p>See design doc § "Log Segmentation" and § "WALSegment".
 *
 * @param path          absolute path to the segment file on disk
 * @param state         whether this segment is still accepting writes (ACTIVE)
 *                      or closed and immutable (SEALED)
 * @param firstSequence sequence number of the first entry written to this
 *                      segment; used to determine replay start point
 * @param lastSequence  sequence number of the last entry written; {@code -1}
 *                      if the segment is ACTIVE and the last sequence is not
 *                      yet finalised
 * @param sizeBytes     current byte size of the segment file on disk
 */
public record WALSegmentInfo(
        Path path,
        State state,
        long firstSequence,
        long lastSequence,
        long sizeBytes) {

    /**
     * Lifecycle state of a WAL segment.
     *
     * <p>See design doc § "Segment lifecycle".
     */
    public enum State {

        /**
         * The segment is currently accepting appends. There is at most one
         * ACTIVE segment at any point in time.
         */
        ACTIVE,

        /**
         * The segment reached the configured size limit and was closed.
         * Its contents are immutable and it is a candidate for deletion
         * once all its entries have been snapshotted.
         */
        SEALED
    }

    public WALSegmentInfo {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        if (firstSequence < 0) {
            throw new IllegalArgumentException("firstSequence must be >= 0, got " + firstSequence);
        }
        if (lastSequence != -1 && lastSequence < firstSequence) {
            throw new IllegalArgumentException(
                    "lastSequence " + lastSequence + " < firstSequence " + firstSequence);
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be >= 0, got " + sizeBytes);
        }
    }

    /**
     * Returns {@code true} if all entries in this segment are at or below
     * {@code upToSequence}, meaning the segment is safe to delete after a
     * snapshot at that sequence number.
     *
     * <p>Always returns {@code false} for an ACTIVE segment since its
     * {@code lastSequence} is not yet finalised.
     */
    public boolean isSafeToDelete(long upToSequence) {
        return state == State.SEALED && lastSequence != -1 && lastSequence <= upToSequence;
    }

    /**
     * Returns the canonical segment filename for the given first-sequence number.
     * Example: {@code firstSequence = 1} → {@code "segment-00000001.wal"}.
     */
    public static String segmentFileName(long firstSequence) {
        return String.format("segment-%08d.wal", firstSequence);
    }
}
