package com.kvcache.storage.impl;

import com.kvcache.core.model.CacheKey;
import com.kvcache.storage.model.MemTableEntry;
import com.kvcache.storage.model.StorageConfig;
import com.kvcache.wal.api.WAL;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

final class SnapshotManager {

    private static final String FILE_PATTERN = "snapshot-%020d.dat";
    private static final String FILE_REGEX   = "snapshot-\\d{20}\\.dat";

    private final StorageConfig config;
    private final Path snapshotDir;
    private final MemTable memTable;
    private final WAL wal;

    private final AtomicLong entriesSinceSnapshot = new AtomicLong(0);
    private volatile long lastSnapshotSequence = -1L;
    private final AtomicBoolean snapshotPending = new AtomicBoolean(false);

    private final ExecutorService snapshotExecutor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("snapshot-writer").factory());

    SnapshotManager(StorageConfig config, MemTable memTable, WAL wal) {
        this.config      = config;
        this.snapshotDir = Path.of(config.snapshotDirectory());
        this.memTable    = memTable;
        this.wal         = wal;
    }

    void onWALAppend() {
        if (entriesSinceSnapshot.incrementAndGet() >= config.snapshotTriggerEntries()) {
            triggerAsyncSnapshot();
        }
    }

    private void triggerAsyncSnapshot() {
        if (snapshotPending.compareAndSet(false, true)) {
            snapshotExecutor.submit(() -> {
                try {
                    takeSnapshotInternal();
                } catch (Exception e) {
                    entriesSinceSnapshot.set(config.snapshotTriggerEntries());
                } finally {
                    snapshotPending.set(false);
                }
            });
        }
    }

    void forceSnapshot() throws IOException {
        Future<?> future = snapshotExecutor.submit(() -> {
            try {
                takeSnapshotInternal();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        try {
            future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UncheckedIOException uio) throw uio.getCause();
            throw new IOException("Snapshot failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Snapshot interrupted", e);
        }
    }

    private void takeSnapshotInternal() throws IOException {
        ensureSnapshotDirExists();

        long seq = wal.getCurrentSequence();

        Map<CacheKey, MemTableEntry> snapshot = new HashMap<>();
        for (Map.Entry<CacheKey, MemTableEntry> e : memTable.entrySet()) {
            snapshot.put(e.getKey(), e.getValue());
        }

        Path dest = snapshotDir.resolve(String.format(FILE_PATTERN, seq));
        SnapshotFile.write(dest, seq, snapshot);

        lastSnapshotSequence = seq;
        entriesSinceSnapshot.set(0);

        wal.truncate(seq);

        cleanupOldSnapshots();
    }

    long loadLatestSnapshot() throws IOException {
        Path[] files = listSnapshotFiles();
        if (files.length == 0) return -1L;

        for (int i = files.length - 1; i >= Math.max(0, files.length - config.snapshotKeepCount()); i--) {
            try {
                SnapshotFile.SnapshotData data = SnapshotFile.read(files[i]);
                for (Map.Entry<CacheKey, MemTableEntry> e : data.entries().entrySet()) {
                    memTable.loadEntry(e.getKey(), e.getValue());
                }
                lastSnapshotSequence = data.snapshotSequence();
                entriesSinceSnapshot.set(0);
                return data.snapshotSequence();
            } catch (IOException ignored) {
            }
        }
        return -1L;
    }

    private void cleanupOldSnapshots() throws IOException {
        Path[] files = listSnapshotFiles();
        int deleteCount = files.length - config.snapshotKeepCount();
        for (int i = 0; i < deleteCount; i++) {
            Files.deleteIfExists(files[i]);
        }
    }

    private void ensureSnapshotDirExists() throws IOException {
        Files.createDirectories(snapshotDir);
    }

    private Path[] listSnapshotFiles() throws IOException {
        if (!Files.exists(snapshotDir)) return new Path[0];
        try (Stream<Path> stream = Files.list(snapshotDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().matches(FILE_REGEX))
                    .sorted()
                    .toArray(Path[]::new);
        }
    }

    long getLastSnapshotSequence() { return lastSnapshotSequence; }
    long getEntriesSinceSnapshot() { return entriesSinceSnapshot.get(); }

    void stop() {
        snapshotExecutor.shutdown();
    }
}
