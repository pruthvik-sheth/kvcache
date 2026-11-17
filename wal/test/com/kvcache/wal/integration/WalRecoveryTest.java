package com.kvcache.wal.integration;

import com.kvcache.wal.api.WALEntryVisitor;
import com.kvcache.wal.impl.WALWriter;
import com.kvcache.wal.model.WALConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalRecoveryTest {
    @Test
    void replaysPutThenDeleteAfterRestart(@TempDir Path directory) throws Exception {
        WALConfig config = WALConfig.withDefaults(directory);
        byte[] key = {1};
        byte[] value = {42};
        WALWriter writer = new WALWriter(config);
        writer.start();
        long putSequence;
        long deleteSequence;
        try {
            putSequence = writer.appendPut(key, value);
            deleteSequence = writer.appendDelete(key);
        } finally {
            writer.stop();
        }

        WALWriter recovered = new WALWriter(config);
        recovered.start();
        try {
            List<String> operations = new ArrayList<>();
            recovered.replay(0, new WALEntryVisitor() {
                @Override
                public void onPut(long sequence, byte[] actualKey, byte[] actualValue) {
                    operations.add("put");
                    assertEquals(putSequence, sequence);
                    assertArrayEquals(key, actualKey);
                    assertArrayEquals(value, actualValue);
                }

                @Override
                public void onDelete(long sequence, byte[] actualKey) {
                    operations.add("delete");
                    assertEquals(deleteSequence, sequence);
                    assertArrayEquals(key, actualKey);
                }
            });
            assertEquals(List.of("put", "delete"), operations);
        } finally {
            recovered.stop();
        }
    }

    @Test
    void rolloverWithSyncEveryWriteDoesNotThrowAndReplaysEachEntryOnce(@TempDir Path directory) throws Exception {
        assertRolloverReplaysEveryEntryOnce(directory, 0L);
    }

    @Test
    void rolloverWithBackgroundSyncDoesNotThrowAndReplaysEachEntryOnce(@TempDir Path directory) throws Exception {
        assertRolloverReplaysEveryEntryOnce(directory, 50L);
    }

    private static void assertRolloverReplaysEveryEntryOnce(Path directory, long syncIntervalMs) throws Exception {
        WALConfig config = new WALConfig(directory, 256L, syncIntervalMs, WALConfig.DEFAULT_MAX_ENTRY_SIZE_BYTES);
        int entryCount = 30; // ~66 bytes each, so many 256-byte segments
        List<Long> written = new ArrayList<>();
        List<byte[]> keys = new ArrayList<>();

        WALWriter writer = new WALWriter(config);
        writer.start();
        try {
            for (int i = 0; i < entryCount; i++) {
                byte[] key = {(byte) (i + 1)};
                byte[] value = new byte[40];
                java.util.Arrays.fill(value, (byte) i);
                keys.add(key);
                if (i % 5 == 4) {
                    written.add(writer.appendDelete(key));
                } else {
                    written.add(writer.appendPut(key, value));
                }
            }
        } finally {
            writer.stop();
        }

        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(directory)) {
            long segmentCount = files.filter(p -> p.getFileName().toString().endsWith(".wal")).count();
            assertTrue(segmentCount > 3, "expected several segments but found " + segmentCount);
        }

        WALWriter recovered = new WALWriter(config);
        recovered.start();
        try {
            List<Long> replayedSequences = new ArrayList<>();
            List<byte[]> replayedKeys = new ArrayList<>();
            recovered.replay(0, new WALEntryVisitor() {
                @Override
                public void onPut(long sequence, byte[] key, byte[] value) {
                    replayedSequences.add(sequence);
                    replayedKeys.add(key);
                }

                @Override
                public void onDelete(long sequence, byte[] key) {
                    replayedSequences.add(sequence);
                    replayedKeys.add(key);
                }
            });
            assertEquals(written, replayedSequences);
            assertEquals(entryCount, replayedKeys.size());
            for (int i = 0; i < entryCount; i++) {
                assertArrayEquals(keys.get(i), replayedKeys.get(i));
            }
            assertEquals(written.get(entryCount - 1) + 1, recovered.appendPut(new byte[] {99}, new byte[] {1}));
        } finally {
            recovered.stop();
        }
    }
}
