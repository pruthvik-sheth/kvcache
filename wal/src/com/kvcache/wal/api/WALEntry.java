package com.kvcache.wal.api;

import java.util.Arrays;

/**
 * Represents a single entry in the Write-Ahead Log.
 *
 * <p>Sealed so that the WAL reader and writer can use exhaustive {@code switch}
 * expressions over entry types without defensive default branches. Adding a new
 * op type requires updating every switch site — which is exactly the desired
 * compile-time safety.
 *
 * <p>Two variants exist per the design doc (§ "Entry Format", op_type field):
 * <ul>
 *   <li>{@link PutEntry} — op_type 0x01, carries a key and value</li>
 *   <li>{@link DeleteEntry} — op_type 0x02, carries only a key</li>
 * </ul>
 *
 * <p>The {@code crc} field stores the CRC32 computed over {@code sequence_num}
 * through {@code value_bytes} (NOT including {@code entry_length}) as defined
 * in the binary format. It is filled by {@code WALWriter} on write and verified
 * by {@code WALReader} on replay.
 */
public sealed interface WALEntry permits WALEntry.PutEntry, WALEntry.DeleteEntry {

    /** Wire op-code for a PUT operation. */
    byte OP_PUT = 0x01;

    /** Wire op-code for a DELETE operation. */
    byte OP_DELETE = 0x02;

    /** Returns the monotonically increasing sequence number assigned by {@code WALWriter}. */
    long sequence();

    /** Returns the raw key bytes (UTF-8 encoded "session:layer:position" string). */
    byte[] key();

    /**
     * Returns the CRC32 checksum covering {@code sequence_num} through
     * {@code value_bytes} as stored in the binary record.
     * Stored as a signed {@code int} but semantically unsigned (uint32).
     */
    int crc();

    // -------------------------------------------------------------------------

    /**
     * A PUT entry — stores a key/value pair in the cache.
     *
     * @param sequence monotonically increasing log sequence number
     * @param key      UTF-8 encoded cache key bytes; max 1 KB
     * @param value    serialised attention tensor bytes; up to several MB
     * @param crc      CRC32 over sequence_num..value_bytes (unsigned, stored as int)
     */
    record PutEntry(long sequence, byte[] key, byte[] value, int crc) implements WALEntry {

        public PutEntry {
            if (key == null || key.length == 0) {
                throw new IllegalArgumentException("key must not be null or empty");
            }
            if (value == null) {
                throw new IllegalArgumentException("value must not be null");
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PutEntry other)) return false;
            return sequence == other.sequence
                    && crc == other.crc
                    && Arrays.equals(key, other.key)
                    && Arrays.equals(value, other.value);
        }

        @Override
        public int hashCode() {
            int result = Long.hashCode(sequence);
            result = 31 * result + Arrays.hashCode(key);
            result = 31 * result + Arrays.hashCode(value);
            result = 31 * result + Integer.hashCode(crc);
            return result;
        }

        @Override
        public String toString() {
            return "PutEntry[sequence=" + sequence
                    + ", keyLen=" + key.length
                    + ", valueLen=" + value.length
                    + ", crc=" + Integer.toUnsignedString(crc) + "]";
        }
    }

    // -------------------------------------------------------------------------

    /**
     * A DELETE entry — removes a key from the cache.
     *
     * <p>Per the design doc: {@code value_length} is 0 and {@code value_bytes}
     * is empty on the wire. The CRC still covers the full content section
     * (sequence_num..value_bytes), with value_bytes contributing zero bytes.
     *
     * @param sequence monotonically increasing log sequence number
     * @param key      UTF-8 encoded cache key bytes; max 1 KB
     * @param crc      CRC32 over sequence_num..value_bytes (unsigned, stored as int)
     */
    record DeleteEntry(long sequence, byte[] key, int crc) implements WALEntry {

        public DeleteEntry {
            if (key == null || key.length == 0) {
                throw new IllegalArgumentException("key must not be null or empty");
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DeleteEntry other)) return false;
            return sequence == other.sequence
                    && crc == other.crc
                    && Arrays.equals(key, other.key);
        }

        @Override
        public int hashCode() {
            int result = Long.hashCode(sequence);
            result = 31 * result + Arrays.hashCode(key);
            result = 31 * result + Integer.hashCode(crc);
            return result;
        }

        @Override
        public String toString() {
            return "DeleteEntry[sequence=" + sequence
                    + ", keyLen=" + key.length
                    + ", crc=" + Integer.toUnsignedString(crc) + "]";
        }
    }
}
