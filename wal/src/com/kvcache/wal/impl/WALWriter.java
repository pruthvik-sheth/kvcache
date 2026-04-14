package com.kvcache.wal.impl;

import com.kvcache.core.exception.KVCacheException;
import com.kvcache.wal.api.WAL;
import com.kvcache.wal.api.WALEntry;
import com.kvcache.wal.api.WALEntryVisitor;
import com.kvcache.wal.exception.WALFullException;
import com.kvcache.wal.model.WALConfig;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import java.util.zip.CRC32;

/**
 * Production implementation of {@link WAL}.
 *
 * <p>Write path (design doc § "Writing (WALWriter)"):
 * <ol>
 *   <li>Acquire {@code writeLock} (single writer, see § "Thread Safety")</li>
 *   <li>Validate entry size against {@code maxEntrySizeBytes}</li>
 *   <li>Assign the next sequence number via {@link AtomicLong}</li>
 *   <li>Serialise to a heap {@code ByteBuffer} and compute CRC32 inline</li>
 *   <li>Write to the active {@link WALSegment} via {@code FileChannel.write}</li>
 *   <li>Rotate to a new segment if the size limit is reached</li>
 *   <li>Release lock; fsync happens out-of-band on the background sync thread</li>
 * </ol>
 *
 * <p>Entry binary format (design doc § "Entry Format"):
 * <pre>
 *   entry_length (4) | sequence (8) | op_type (1) | key_length (4)
 *   | key_bytes (N) | value_length (4) | value_bytes (M) | crc32 (4)
 * </pre>
 * All multi-byte integers are big-endian. {@code entry_length} is the byte
 * count of everything after the length field itself. CRC32 covers the content
 * section (sequence through value_bytes) but NOT entry_length.
 *
 * <p>Recovery (design doc § "Recovery Sequence"): callers must invoke
 * {@link #replay} to rebuild in-memory state and advance the sequence counter
 * before issuing any appends on an existing WAL directory.
 */
public final class WALWriter implements WAL {

    private final WALConfig    config;
    private final WALReader    reader    = new WALReader();
    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicLong   sequence  = new AtomicLong(0);
    private final CRC32        crc32     = new CRC32(); // reset before use; held under writeLock

    private volatile WALSegment            activeSegment; // null until first write
    private volatile boolean               running = false;
    private          ScheduledExecutorService syncExecutor;

    public WALWriter(WALConfig config) {
        this.config = config;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Creates the WAL directory if it does not exist and starts the background
     * fsync thread. Does NOT create an active segment — segments are created
     * lazily on the first write so the sequence counter can be set correctly
     * by {@link #replay} before any appends.
     */
    @Override
    public void start() throws Exception {
        if (running) return;

        Files.createDirectories(config.directory());

        running = true;

        if (config.syncIntervalMs() > 0) {
            syncExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = Thread.ofVirtual().unstarted(r);
                t.setName("wal-sync");
                return t;
            });
            syncExecutor.scheduleAtFixedRate(
                    this::syncQuietly,
                    config.syncIntervalMs(),
                    config.syncIntervalMs(),
                    TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Performs a final fsync, seals the active segment, and shuts down the
     * background sync thread.
     */
    @Override
    public void stop() throws Exception {
        if (!running) return;
        running = false;

        if (syncExecutor != null) {
            syncExecutor.shutdown();
            syncExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }

        writeLock.lock();
        try {
            if (activeSegment != null) {
                try {
                    activeSegment.seal(sequence.get());
                } catch (IOException e) {
                    activeSegment.closeQuietly();
                } finally {
                    activeSegment = null;
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // =========================================================================
    // Write operations
    // =========================================================================

    @Override
    public long appendPut(byte[] key, byte[] value) {
        validateKey(key);
        if (value == null) throw new IllegalArgumentException("value must not be null");
        return appendEntry(WALEntry.OP_PUT, key, value);
    }

    @Override
    public long appendDelete(byte[] key) {
        validateKey(key);
        return appendEntry(WALEntry.OP_DELETE, key, new byte[0]);
    }

    /**
     * Core append logic. Serialises, assigns sequence, writes, optionally
     * rotates the segment, and returns the assigned sequence number.
     */
    private long appendEntry(byte opType, byte[] key, byte[] value) {
        // Validate size BEFORE acquiring the lock to fail fast.
        // content = seq(8) + op(1) + keyLen(4) + key + valLen(4) + val
        int contentLen   = 8 + 1 + 4 + key.length + 4 + value.length;
        int totalOnWire  = 4 + contentLen + 4; // entry_length field + content + crc
        if (totalOnWire > config.maxEntrySizeBytes()) {
            throw new WALFullException(totalOnWire, config.maxEntrySizeBytes());
        }

        writeLock.lock();
        try {
            if (!running) {
                throw new KVCacheException("WALWriter is not running");
            }

            long seq = sequence.incrementAndGet();

            // Lazy segment creation: first write after start() or after rotation.
            if (activeSegment == null) {
                activeSegment = createSegment(seq);
            }

            byte[] entry = serialise(seq, opType, key, value, contentLen);
            activeSegment.write(ByteBuffer.wrap(entry));
            activeSegment.updateLastSequence(seq);

            // Rotate when the segment has reached the size limit.
            if (activeSegment.getSizeBytes() >= config.segmentSizeBytes()) {
                rotateSegment(seq);
            }

            // If sync-every-write is configured, fsync inline.
            if (config.syncIntervalMs() == 0) {
                activeSegment.force();
            }

            return seq;

        } catch (IOException e) {
            throw new KVCacheException("WAL write failed: " + e.getMessage(), e);
        } finally {
            writeLock.unlock();
        }
    }

    // =========================================================================
    // Replay
    // =========================================================================

    /**
     * Replays all valid entries from every segment file in the WAL directory.
     * Entries with sequence &lt; {@code fromSequence} are read (for CRC
     * verification) but not delivered to the visitor.
     *
     * <p>After replay, the sequence counter is advanced to the highest sequence
     * seen so that new appends do not reuse sequence numbers.
     *
     * <p>See design doc § "Recovery Sequence".
     */
    @Override
    public void replay(long fromSequence, WALEntryVisitor visitor) {
        List<Path> segments = listSegmentPaths();
        if (segments.isEmpty()) return;

        // Wrap visitor to track the maximum replayed sequence.
        long[] maxSeen = { sequence.get() };
        WALEntryVisitor tracking = new WALEntryVisitor() {
            @Override
            public void onPut(long seq, byte[] key, byte[] value) {
                if (seq > maxSeen[0]) maxSeen[0] = seq;
                visitor.onPut(seq, key, value);
            }
            @Override
            public void onDelete(long seq, byte[] key) {
                if (seq > maxSeen[0]) maxSeen[0] = seq;
                visitor.onDelete(seq, key);
            }
        };

        for (Path segPath : segments) {
            long last = reader.readSegment(segPath, fromSequence, tracking);
            if (last > maxSeen[0]) maxSeen[0] = last;
        }

        // Advance counter to max seen so new appends continue from there.
        sequence.updateAndGet(current -> Math.max(current, maxSeen[0]));
    }

    // =========================================================================
    // Sync and truncate
    // =========================================================================

    @Override
    public void sync() {
        WALSegment seg = activeSegment; // volatile read — no lock needed
        if (seg != null) {
            try {
                seg.force();
            } catch (IOException e) {
                System.err.printf("[WALWriter] sync failed: %s%n", e.getMessage());
            }
        }
    }

    /**
     * Deletes all sealed segments whose last entry sequence is ≤
     * {@code upToSequence}. Delegates to {@link WALCompactor}.
     *
     * <p>See design doc § "WALCompactor" and § "Segment lifecycle — DELETED".
     */
    @Override
    public void truncate(long upToSequence) {
        Path activePath = (activeSegment != null) ? activeSegment.getPath() : null;
        new WALCompactor(config.directory(), reader).compact(upToSequence, activePath);
    }

    @Override
    public long getCurrentSequence() {
        return sequence.get();
    }

    // =========================================================================
    // Serialisation
    // =========================================================================

    /**
     * Serialises one entry to a byte array.
     *
     * <p>Binary layout (design doc § "Entry Format", big-endian):
     * <pre>
     *   [entry_length: 4][sequence: 8][op_type: 1]
     *   [key_length: 4][key: N][value_length: 4][value: M][crc32: 4]
     * </pre>
     * {@code entry_length} = contentLen + 4 (for the trailing CRC).
     * CRC covers bytes 4 through (4 + contentLen - 1), i.e. everything
     * except {@code entry_length} itself.
     */
    private byte[] serialise(long seq, byte opType, byte[] key, byte[] value, int contentLen) {
        byte[] entry = new byte[4 + contentLen + 4];
        ByteBuffer buf = ByteBuffer.wrap(entry); // big-endian by default

        buf.putInt(contentLen + 4);   // entry_length (does NOT count itself)
        buf.putLong(seq);              // sequence_num
        buf.put(opType);               // op_type
        buf.putInt(key.length);        // key_length
        buf.put(key);                  // key_bytes
        buf.putInt(value.length);      // value_length
        if (value.length > 0) {
            buf.put(value);            // value_bytes (absent for DELETE)
        }

        // CRC over content bytes: entry[4 .. 4+contentLen-1]
        crc32.reset();
        crc32.update(entry, 4, contentLen);
        buf.putInt((int) crc32.getValue());

        return entry;
    }

    // =========================================================================
    // Segment management
    // =========================================================================

    private WALSegment createSegment(long firstSeq) {
        Path segPath = config.directory().resolve(WALSegment.segmentFileName(firstSeq));
        try {
            return new WALSegment(segPath, firstSeq);
        } catch (IOException e) {
            throw new KVCacheException("Failed to create WAL segment " + segPath + ": " + e.getMessage(), e);
        }
    }

    /** Seals the active segment and sets {@code activeSegment} to null. */
    private void rotateSegment(long lastSeq) throws IOException {
        try {
            activeSegment.seal(lastSeq);
        } finally {
            activeSegment = null;
        }
    }

    /** Lists all segment files in the WAL directory, sorted chronologically. */
    private List<Path> listSegmentPaths() {
        try (Stream<Path> stream = Files.list(config.directory())) {
            return stream
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(WALSegment.SEGMENT_PREFIX)
                                && name.endsWith(WALSegment.SEGMENT_EXTENSION);
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new KVCacheException("Failed to list WAL segments in "
                    + config.directory() + ": " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private void syncQuietly() {
        try {
            sync();
        } catch (Exception e) {
            System.err.printf("[WALWriter] background sync threw: %s%n", e.getMessage());
        }
    }

    private static void validateKey(byte[] key) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("key must not be null or empty");
        }
    }
}
