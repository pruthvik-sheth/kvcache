package com.kvcache.wal.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Deletes WAL segment files that are no longer needed for recovery.
 *
 * <p>A segment is safe to delete when all of its entries have been captured
 * in a snapshot, i.e. its last entry's sequence number is ≤ the snapshot
 * sequence. The compactor reads each sealed segment to determine its last
 * valid sequence number, then deletes any file that qualifies.
 *
 * <p>The active segment (the one currently being written to) is always
 * skipped — it is identified by the path passed to {@link #compact}.
 *
 * <p>Status: stub. The snapshot system (Phase 3) will drive regular
 * compaction calls. The {@link #compact} method is wired today so the
 * {@link WAL#truncate} API works end-to-end and tests can exercise deletion.
 *
 * <p>See design doc § "WALCompactor", § "Segment lifecycle — DELETED",
 * and § "Bounded disk usage".
 */
final class WALCompactor {

    private final Path      directory;
    private final WALReader reader;

    WALCompactor(Path directory, WALReader reader) {
        this.directory = directory;
        this.reader    = reader;
    }

    /**
     * Scans all segment files and deletes those whose last valid entry sequence
     * is ≤ {@code upToSequence}.
     *
     * <p>Only SEALED segments are considered. The active segment ({@code skipPath})
     * is never deleted. If {@code skipPath} is {@code null} (WAL was stopped
     * cleanly and has no active segment), all qualifying segments are deleted.
     *
     * @param upToSequence  highest sequence number that has been snapshotted
     * @param skipPath      absolute path of the current active segment, or
     *                      {@code null} if there is no active segment
     */
    void compact(long upToSequence, Path skipPath) {
        List<Path> segments = listSegmentPaths();

        for (Path segPath : segments) {
            if (segPath.equals(skipPath)) {
                continue; // never delete the active segment
            }

            long lastSeq = findLastSequence(segPath);
            if (lastSeq == -1) {
                // Could not determine last sequence — skip to be safe.
                System.err.printf("[WALCompactor] cannot determine last sequence for %s, skipping%n",
                        segPath.getFileName());
                continue;
            }

            if (lastSeq <= upToSequence) {
                tryDelete(segPath, lastSeq);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Reads {@code segPath} to find the sequence number of its last valid entry.
     * Returns {@code -1} if the segment is empty or unreadable.
     */
    private long findLastSequence(Path segPath) {
        AtomicLong max = new AtomicLong(-1);
        reader.readSegment(segPath, 0, new com.kvcache.wal.api.WALEntryVisitor() {
            @Override
            public void onPut(long sequence, byte[] key, byte[] value) {
                if (sequence > max.get()) max.set(sequence);
            }
            @Override
            public void onDelete(long sequence, byte[] key) {
                if (sequence > max.get()) max.set(sequence);
            }
        });
        return max.get();
    }

    private void tryDelete(Path segPath, long lastSeq) {
        try {
            Files.deleteIfExists(segPath);
            System.out.printf("[WALCompactor] deleted %s (lastSeq=%d)%n",
                    segPath.getFileName(), lastSeq);
        } catch (IOException e) {
            System.err.printf("[WALCompactor] failed to delete %s: %s%n",
                    segPath.getFileName(), e.getMessage());
        }
    }

    private List<Path> listSegmentPaths() {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(WALSegment.SEGMENT_PREFIX)
                                && name.endsWith(WALSegment.SEGMENT_EXTENSION);
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            System.err.printf("[WALCompactor] cannot list %s: %s%n", directory, e.getMessage());
            return List.of();
        }
    }
}
