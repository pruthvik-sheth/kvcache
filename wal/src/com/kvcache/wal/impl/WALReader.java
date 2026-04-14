package com.kvcache.wal.impl;

import com.kvcache.wal.api.WALEntry;
import com.kvcache.wal.api.WALEntryVisitor;
import com.kvcache.wal.exception.WALCorruptionException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

/**
 * Reads and replays entries from a single WAL segment file.
 *
 * <p>The read loop implements the design doc § "Reading (WALReader)" algorithm:
 * <ol>
 *   <li>Read 4 bytes → {@code entry_length}</li>
 *   <li>Read {@code entry_length} bytes into a buffer</li>
 *   <li>Split off the last 4 bytes as the stored CRC</li>
 *   <li>Compute CRC32 over the remaining bytes</li>
 *   <li>On match: parse and deliver to visitor. On mismatch: stop.</li>
 * </ol>
 *
 * <p>Corruption handling (design doc § "Corruption Handling"): when a CRC
 * mismatch or truncated read is detected, reading stops at that point in
 * the segment and the method returns. The caller ({@code WALWriter.replay})
 * continues with the next segment file.
 *
 * <p>Not thread-safe. Instantiate once per replay call.
 */
final class WALReader {

    /** Sanity cap — rejects malformed {@code entry_length} values > 256 MiB. */
    private static final int MAX_ENTRY_LENGTH = 256 * 1024 * 1024;

    private final CRC32 crc = new CRC32();

    /**
     * Reads all valid entries from {@code segmentPath} whose sequence numbers
     * are ≥ {@code fromSequence} and delivers them to {@code visitor}.
     *
     * <p>Stops reading at the first corrupted or truncated entry and returns
     * normally (no exception). The {@link WALCorruptionException} is only
     * thrown for errors in SEALED segments where a torn-write cannot explain
     * the corruption — callers may choose to log it at ERROR rather than WARN.
     *
     * @param segmentPath   path to the segment file
     * @param fromSequence  inclusive lower bound; entries below this are read
     *                      (to advance the file position and verify CRC) but not
     *                      delivered to the visitor
     * @param visitor       receives each valid entry in sequence order
     * @return the sequence number of the last valid entry read, or {@code -1}
     *         if no valid entries were found
     */
    long readSegment(Path segmentPath, long fromSequence, WALEntryVisitor visitor) {
        long lastValidSequence = -1;

        try (FileChannel channel = FileChannel.open(segmentPath, StandardOpenOption.READ)) {

            ByteBuffer lenBuf = ByteBuffer.allocate(Integer.BYTES);

            while (true) {
                // ── Step 1: read entry_length (4 bytes) ──────────────────────
                lenBuf.clear();
                int lenRead = readFully(channel, lenBuf);

                if (lenRead == 0) {
                    break; // clean EOF — normal end of segment
                }
                if (lenRead < Integer.BYTES) {
                    warn(segmentPath, "truncated entry_length (%d of 4 bytes) — torn write", lenRead);
                    break;
                }

                lenBuf.flip();
                int entryLength = lenBuf.getInt();

                if (entryLength <= 0 || entryLength > MAX_ENTRY_LENGTH) {
                    warn(segmentPath, "invalid entry_length %d — stopping segment", entryLength);
                    break;
                }
                if (entryLength < Integer.BYTES) {
                    // Minimum valid entry has at least a 4-byte CRC
                    warn(segmentPath, "entry_length %d too small to contain CRC", entryLength);
                    break;
                }

                // ── Step 2: read entryLength bytes (content + CRC) ───────────
                ByteBuffer entryBuf = ByteBuffer.allocate(entryLength);
                int entryRead = readFully(channel, entryBuf);

                if (entryRead < entryLength) {
                    warn(segmentPath,
                            "truncated entry body (%d of %d bytes) — torn write",
                            entryRead, entryLength);
                    break;
                }

                entryBuf.flip(); // position=0, limit=entryLength

                // ── Step 3 & 4: verify CRC ────────────────────────────────────
                // Layout: content bytes [0 .. entryLength-5], CRC [entryLength-4 .. entryLength-1]
                int contentLength = entryLength - Integer.BYTES;
                byte[] content = new byte[contentLength];
                entryBuf.get(content);               // advances position to entryLength-4
                int storedCrc = entryBuf.getInt();   // reads final 4 bytes

                crc.reset();
                crc.update(content);
                int computedCrc = (int) crc.getValue();

                if (storedCrc != computedCrc) {
                    warn(segmentPath,
                            "CRC mismatch (stored=0x%08x computed=0x%08x) — stopping segment",
                            Integer.toUnsignedLong(storedCrc),
                            Integer.toUnsignedLong(computedCrc));
                    break;
                }

                // ── Step 5: parse content ─────────────────────────────────────
                // Format: sequence(8) + op_type(1) + key_length(4) + key + value_length(4) + value
                ByteBuffer c = ByteBuffer.wrap(content);

                if (contentLength < Long.BYTES + 1 + Integer.BYTES + Integer.BYTES) {
                    warn(segmentPath, "content too short (%d bytes) to parse header", contentLength);
                    break;
                }

                long sequence = c.getLong();
                byte opType   = c.get();
                int  keyLen   = c.getInt();

                if (keyLen <= 0 || keyLen > c.remaining() - Integer.BYTES) {
                    warn(segmentPath, "invalid key_length %d at sequence %d", keyLen, sequence);
                    break;
                }

                byte[] key = new byte[keyLen];
                c.get(key);

                int valLen = c.getInt();
                if (valLen < 0 || valLen > c.remaining()) {
                    warn(segmentPath, "invalid value_length %d at sequence %d", valLen, sequence);
                    break;
                }

                byte[] value = new byte[valLen];
                if (valLen > 0) c.get(value);

                lastValidSequence = sequence;

                // Deliver to visitor only if sequence is in range
                if (sequence >= fromSequence) {
                    switch (opType) {
                        case WALEntry.OP_PUT    -> visitor.onPut(sequence, key, value);
                        case WALEntry.OP_DELETE -> visitor.onDelete(sequence, key);
                        default -> {
                            warn(segmentPath, "unknown op_type 0x%02x at sequence %d", opType, sequence);
                            // Unknown op — treat as corruption, stop
                            return lastValidSequence;
                        }
                    }
                }
            }

        } catch (IOException e) {
            System.err.printf("[WALReader] I/O error reading %s: %s%n", segmentPath, e.getMessage());
        }

        return lastValidSequence;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Reads bytes into {@code buf} until it is full or the channel reaches EOF.
     *
     * @return total bytes read; {@code 0} means the channel was already at EOF
     *         when called (clean end of segment)
     */
    private static int readFully(FileChannel channel, ByteBuffer buf) throws IOException {
        int total = 0;
        while (buf.hasRemaining()) {
            int n = channel.read(buf);
            if (n == -1) break;
            total += n;
        }
        return total;
    }

    private static void warn(Path segmentPath, String fmt, Object... args) {
        System.err.printf("[WALReader] " + fmt + " in %s%n",
                appendArg(args, segmentPath.getFileName()));
    }

    private static Object[] appendArg(Object[] args, Object extra) {
        Object[] extended = new Object[args.length + 1];
        System.arraycopy(args, 0, extended, 0, args.length);
        extended[args.length] = extra;
        return extended;
    }
}
