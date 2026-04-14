package com.kvcache.wal.impl;

import com.kvcache.wal.model.WALSegmentInfo;
import com.kvcache.wal.model.WALSegmentInfo.State;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Write-side handle for a single WAL segment file.
 *
 * <p>Owns the {@link FileChannel} used to append entries. Only {@code WALWriter}
 * creates instances; everything else interacts with segment metadata via the
 * immutable {@link WALSegmentInfo} record returned by {@link #toInfo()}.
 *
 * <p>A segment begins ACTIVE (accepting writes) and transitions to SEALED exactly
 * once when {@link #seal(long)} is called. After sealing, the {@link FileChannel}
 * is closed and no further writes are allowed. See design doc § "Segment lifecycle".
 *
 * <p>Not thread-safe. All access is serialised by the {@code ReentrantLock} in
 * {@code WALWriter}.
 */
final class WALSegment {

    static final String SEGMENT_PREFIX    = "segment-";
    static final String SEGMENT_EXTENSION = ".wal";

    private final Path path;
    private final long firstSequence;
    private       FileChannel channel;
    private       State       state;
    private       long        lastSequence;   // -1 while ACTIVE
    private       long        sizeBytes;

    /**
     * Creates and opens a new ACTIVE segment file.
     *
     * @param path          absolute path where the file will be created;
     *                      must not already exist
     * @param firstSequence sequence number of the first entry that will be
     *                      written to this segment
     * @throws IOException if the file cannot be created or opened
     */
    WALSegment(Path path, long firstSequence) throws IOException {
        this.path          = path;
        this.firstSequence = firstSequence;
        this.lastSequence  = -1;
        this.state         = State.ACTIVE;
        this.sizeBytes     = 0;
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    // -------------------------------------------------------------------------
    // Write operations (ACTIVE state only)
    // -------------------------------------------------------------------------

    /**
     * Appends all bytes remaining in {@code buffer} to the segment.
     * Advances the buffer's position to its limit.
     *
     * @throws IllegalStateException if the segment is not ACTIVE
     * @throws IOException           if the write fails
     */
    void write(ByteBuffer buffer) throws IOException {
        if (state != State.ACTIVE) {
            throw new IllegalStateException("Cannot write to a SEALED segment: " + path);
        }
        while (buffer.hasRemaining()) {
            sizeBytes += channel.write(buffer);
        }
    }

    /**
     * Forces all written data to durable storage (equivalent to {@code fdatasync}).
     * Uses {@code force(false)} — metadata updates (access time, etc.) are not
     * flushed, matching the design doc recommendation for fdatasync semantics.
     *
     * @throws IOException if the fsync fails or the channel is closed
     */
    void force() throws IOException {
        if (channel != null && channel.isOpen()) {
            channel.force(false);
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Seals this segment: records the last sequence number, fsyncs, closes the
     * channel, and transitions to SEALED. Calling {@link #write} after sealing
     * throws {@link IllegalStateException}.
     *
     * @param lastSequence sequence number of the last entry written
     * @throws IOException if the final fsync or close fails
     */
    void seal(long lastSequence) throws IOException {
        if (state == State.SEALED) return;
        this.lastSequence = lastSequence;
        this.state        = State.SEALED;
        try {
            if (channel != null && channel.isOpen()) {
                channel.force(false);
                channel.close();
            }
        } finally {
            channel = null;
        }
    }

    /**
     * Closes the channel without recording a last-sequence number. Used on
     * error paths and shutdown before the last sequence is known.
     */
    void closeQuietly() {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        } catch (IOException ignored) {
            // best-effort on error path
        } finally {
            channel = null;
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Returns an immutable snapshot of this segment's metadata. */
    WALSegmentInfo toInfo() {
        return new WALSegmentInfo(path, state, firstSequence, lastSequence, sizeBytes);
    }

    Path  getPath()          { return path; }
    State getState()         { return state; }
    long  getFirstSequence() { return firstSequence; }
    long  getLastSequence()  { return lastSequence; }
    long  getSizeBytes()     { return sizeBytes; }
    boolean isActive()       { return state == State.ACTIVE; }

    /** Updates the last-sequence tracking (called after each successful write). */
    void updateLastSequence(long seq) { this.lastSequence = seq; }

    // -------------------------------------------------------------------------
    // Naming helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the canonical filename for a segment whose first entry has the
     * given sequence number. Example: {@code 1 → "segment-00000001.wal"}.
     *
     * <p>Lexicographic sort order equals chronological order because the number
     * is zero-padded to 8 digits — see design doc § "Naming convention".
     */
    static String segmentFileName(long firstSequence) {
        return String.format("%s%08d%s", SEGMENT_PREFIX, firstSequence, SEGMENT_EXTENSION);
    }

    /**
     * Parses the first-sequence number from a canonical segment filename.
     *
     * @throws IllegalArgumentException if the filename is not in the expected format
     */
    static long firstSequenceFromFileName(String fileName) {
        if (!fileName.startsWith(SEGMENT_PREFIX) || !fileName.endsWith(SEGMENT_EXTENSION)) {
            throw new IllegalArgumentException("Not a WAL segment file: " + fileName);
        }
        String num = fileName.substring(SEGMENT_PREFIX.length(),
                fileName.length() - SEGMENT_EXTENSION.length());
        return Long.parseLong(num);
    }
}
