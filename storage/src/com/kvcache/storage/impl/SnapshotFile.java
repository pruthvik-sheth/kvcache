package com.kvcache.storage.impl;

import com.kvcache.core.model.CacheKey;
import com.kvcache.storage.model.MemTableEntry;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

final class SnapshotFile {

    private SnapshotFile() {}

    static void write(Path path, long snapshotSequence, Map<CacheKey, MemTableEntry> entries)
            throws IOException {

        try (FileOutputStream fos = new FileOutputStream(path.toFile())) {
            CRC32 crc32 = new CRC32();
            CheckedOutputStream cos = new CheckedOutputStream(fos, crc32);
            DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(cos));

            dos.writeLong(snapshotSequence);
            dos.writeInt(entries.size());

            for (Map.Entry<CacheKey, MemTableEntry> e : entries.entrySet()) {
                byte[] keyBytes = e.getKey().toWireString().getBytes(StandardCharsets.UTF_8);
                MemTableEntry entry = e.getValue();

                dos.writeInt(keyBytes.length);
                dos.write(keyBytes);
                dos.writeInt(entry.value().length);
                dos.write(entry.value());
                dos.writeLong(entry.timestamp());
                dos.writeLong(entry.ttlMs());
            }

            dos.flush();

            long checksum = crc32.getValue();

            fos.write((int) ((checksum >> 24) & 0xFF));
            fos.write((int) ((checksum >> 16) & 0xFF));
            fos.write((int) ((checksum >>  8) & 0xFF));
            fos.write((int) ((checksum       ) & 0xFF));

            fos.getFD().sync();
        }
    }

    static SnapshotData read(Path path) throws IOException {
        try (FileInputStream fis = new FileInputStream(path.toFile())) {
            CRC32 crc32 = new CRC32();
            CheckedInputStream cis = new CheckedInputStream(fis, crc32);
            DataInputStream dis = new DataInputStream(cis);

            long snapshotSequence = dis.readLong();
            int entryCount = dis.readInt();

            Map<CacheKey, MemTableEntry> entries = new HashMap<>(entryCount * 2);
            for (int i = 0; i < entryCount; i++) {
                int keyLen = dis.readInt();
                byte[] keyBytes = new byte[keyLen];
                dis.readFully(keyBytes);

                int valueLen = dis.readInt();
                byte[] valueBytes = new byte[valueLen];
                dis.readFully(valueBytes);

                long timestamp = dis.readLong();
                long ttlMs = dis.readLong();

                CacheKey key = CacheKey.fromWireString(
                        new String(keyBytes, StandardCharsets.UTF_8));
                int sizeBytes = keyLen + valueLen + MemTableEntry.ENTRY_OVERHEAD;
                MemTableEntry entry = new MemTableEntry(valueBytes, timestamp, ttlMs, timestamp, sizeBytes);
                entries.put(key, entry);
            }

            long computedCrc = crc32.getValue();

            int b0 = fis.read(), b1 = fis.read(), b2 = fis.read(), b3 = fis.read();
            if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) {
                throw new IOException("Truncated snapshot file: CRC bytes missing in " + path);
            }
            long storedCrc = ((long) (b0 & 0xFF) << 24)
                           | ((long) (b1 & 0xFF) << 16)
                           | ((long) (b2 & 0xFF) <<  8)
                           |  (long) (b3 & 0xFF);

            if (computedCrc != storedCrc) {
                throw new IOException(String.format(
                        "CRC mismatch in %s: stored=%08x, computed=%08x",
                        path, storedCrc, computedCrc));
            }

            return new SnapshotData(snapshotSequence, entries);
        }
    }

    record SnapshotData(long snapshotSequence, Map<CacheKey, MemTableEntry> entries) {}
}
