package com.kvcache.wal.exception;

import com.kvcache.core.exception.KVCacheException;

/**
 * Thrown when a WAL segment file contains a corrupted or truncated entry.
 *
 * <p>Corruption is detected during replay when either:
 * <ul>
 *   <li>The CRC32 computed over the entry's content bytes does not match
 *       the stored CRC (see design doc § "Entry Format" — crc32 field), or</li>
 *   <li>The file ends before {@code entry_length} bytes have been consumed
 *       (a torn write from a crash mid-entry).</li>
 * </ul>
 *
 * <p>On encountering corruption the {@code WALReader} logs a warning and
 * stops reading further entries in the affected segment. It does NOT throw
 * this exception as an unrecoverable failure during normal replay — the
 * exception is used to signal the condition up the call stack so the caller
 * can record which segment and sequence number were affected.
 *
 * <p>See design doc § "Corruption Handling".
 */
public class WALCorruptionException extends KVCacheException {

    private final String segmentPath;
    private final long   sequenceNumber;

    /**
     * @param segmentPath    path to the segment file where corruption was detected
     * @param sequenceNumber sequence number of the corrupted entry, or {@code -1}
     *                       if the entry could not be parsed far enough to extract it
     * @param message        human-readable description of the corruption
     */
    public WALCorruptionException(String segmentPath, long sequenceNumber, String message) {
        super("WAL corruption in " + segmentPath + " at sequence " + sequenceNumber
                + ": " + message);
        this.segmentPath    = segmentPath;
        this.sequenceNumber = sequenceNumber;
    }

    /**
     * @param segmentPath    path to the segment file where corruption was detected
     * @param sequenceNumber sequence number of the corrupted entry
     * @param message        human-readable description of the corruption
     * @param cause          the underlying I/O exception, if any
     */
    public WALCorruptionException(String segmentPath, long sequenceNumber,
                                  String message, Throwable cause) {
        super("WAL corruption in " + segmentPath + " at sequence " + sequenceNumber
                + ": " + message, cause);
        this.segmentPath    = segmentPath;
        this.sequenceNumber = sequenceNumber;
    }

    /** Returns the path of the segment where corruption was detected. */
    public String getSegmentPath() {
        return segmentPath;
    }

    /**
     * Returns the sequence number of the corrupted entry, or {@code -1} if
     * the entry was too truncated to extract a sequence number.
     */
    public long getSequenceNumber() {
        return sequenceNumber;
    }
}
