# Write-Ahead Log (WAL) Design Document

## Overview

The WAL is the durability layer of our distributed KV cache. Every mutation
(put or delete) is appended to the WAL on disk before the in-memory HashMap
is updated. On crash, we replay the WAL to rebuild memory state.

The WAL is NOT our primary data store — memory is. The WAL exists purely
so that a node restart doesn't mean a cold, empty cache.

### Goals
- **Durability**: No acknowledged write is lost on crash
- **Fast writes**: Appending must not bottleneck the cache's throughput
- **Fast recovery**: Replaying the log on startup should be quick
- **Corruption detection**: Detect and skip partially written or corrupted entries
- **Bounded disk usage**: Old entries are cleaned up after snapshots

### Non-Goals
- We do NOT need to read individual entries by key from the WAL (that's what memory is for)
- We do NOT need sorted order on disk (no range queries against the WAL)
- We do NOT need to support concurrent readers during normal operation

---

## Entry Format

Every WAL entry is a fixed-layout binary record. The format is designed so
that a reader can always determine where one entry ends and the next begins,
and can verify integrity of each entry independently.

```
┌─────────────┬─────────────┬──────────┬─────────────┬───────────┬───────────────┬─────────────┬──────────┐
│ entry_length │ sequence_num │ op_type  │ key_length  │ key_bytes │ value_length  │ value_bytes │ crc32    │
│ (4 bytes)    │ (8 bytes)    │ (1 byte) │ (4 bytes)   │ (variable)│ (4 bytes)     │ (variable)  │ (4 bytes)│
└─────────────┴─────────────┴──────────┴─────────────┴───────────┴───────────────┴─────────────┴──────────┘
```

### Field descriptions

**entry_length** (4 bytes, int32, big-endian)
Total byte count of everything AFTER this field, up to and including the CRC.
This lets the reader know exactly how many bytes to consume for this entry.
If the reader hits EOF before consuming entry_length bytes, the entry is
incomplete (crash during write) and should be discarded.

**sequence_num** (8 bytes, int64, big-endian)
Monotonically increasing counter starting at 1. Every entry gets the next
number in the sequence. Used for:
- Ordering during replay (apply operations in the order they happened)
- Snapshot coordination (snapshot records "I captured everything up to
  sequence N", so recovery only replays entries after N)
- Replication (followers can say "send me everything after sequence N")

**op_type** (1 byte)
- 0x01 = PUT (store a key-value pair)
- 0x02 = DELETE (remove a key)

For DELETE entries, value_length is 0 and value_bytes is empty.

**key_length** (4 bytes, int32, big-endian)
Length of the key in bytes. Maximum key size: 1KB (1024 bytes).
Keys in our system are structured as "session:layer:position" strings
encoded as UTF-8. They won't be large.

**key_bytes** (variable)
The raw key bytes. Length is exactly key_length.

**value_length** (4 bytes, int32, big-endian)
Length of the value in bytes. For our LLM KV cache, values are serialized
attention tensors. These can be large — up to a few MB per tensor.
For DELETE operations, this is 0.

**value_bytes** (variable)
The raw value bytes. Length is exactly value_length.

**crc32** (4 bytes, uint32, big-endian)
CRC32 checksum computed over ALL preceding bytes in this entry (from
sequence_num through value_bytes — NOT including entry_length itself).

On replay, the reader:
1. Reads entry_length
2. Reads that many bytes
3. Splits off the last 4 bytes as the stored CRC
4. Computes CRC over the remaining bytes
5. Compares: match = valid entry, mismatch = corrupted entry

### Why this format?

- **entry_length first**: Allows the reader to read a fixed 4 bytes, then
  allocate exactly the right buffer for the rest. No guessing.
- **CRC at the end**: The CRC covers all content bytes. If we crash mid-write,
  either entry_length won't match available bytes (detected as EOF), or the
  CRC won't match (detected as corruption). Both cases are handled safely.
- **Big-endian**: Convention for network/binary protocols. Consistent, no
  platform-dependent byte order issues.

---

## Fsync Policy

**Decision: Batch fsync every 100ms**

We do NOT fsync after every individual entry. Reasoning:

- Our system is a cache, not a database of record. If we lose the last
  100ms of cache entries on crash, the LLM just recomputes them. Annoying
  but not catastrophic.
- Fsync per entry would limit us to ~1000 writes/sec on most SSDs
  (fsync takes ~1ms). With batch fsync, we can do 100,000+ writes/sec.
- This matches how most real caches handle durability — Redis AOF in
  "everysec" mode does the same thing.

### How it works

A background "sync thread" runs on a 100ms interval:
1. Wake up every 100ms
2. Call fsync() on the active segment file
3. Record the last synced sequence number

This means:
- Worst case data loss on crash: ~100ms of writes
- Average case: ~50ms of writes
- All writes before the last sync are guaranteed durable

### Configurable

The sync interval should be configurable:
- `wal.sync.interval.ms = 100` (default, good balance)
- `wal.sync.interval.ms = 0` (fsync every entry, safest, slowest)
- `wal.sync.interval.ms = 1000` (fsync every second, fastest, most risk)

---

## Log Segmentation

**Decision: 64MB per segment file**

The WAL is split into multiple segment files rather than one ever-growing file.

```
wal/
├── segment-00000001.wal    (64MB, SEALED — full, no more writes)
├── segment-00000002.wal    (64MB, SEALED)
└── segment-00000003.wal    (23MB, ACTIVE — currently being written to)
```

### Segment lifecycle

1. **ACTIVE**: The current segment accepting writes. Only one segment is
   active at a time. Writes are appended to this segment.

2. **SEALED**: When the active segment reaches 64MB, it is closed (sealed)
   and a new active segment is created. Sealed segments are immutable —
   never written to again.

3. **DELETED**: When a snapshot captures all data up to sequence N, any
   sealed segment whose entries are ALL ≤ N can be safely deleted. The
   WALCompactor handles this.

### Naming convention

Segments are named with zero-padded sequence numbers: `segment-XXXXXXXX.wal`
The number corresponds to the first sequence number in that segment.
This makes it trivial to sort segments in order and find the right segment
for any given sequence number.

### Why 64MB?

- Small enough that recovery doesn't take forever reading one huge file
- Large enough that we're not creating new files every few seconds
- Typical SSD write speeds (500MB/s+) mean a full segment is written in ~130ms
- Configurable: `wal.segment.size.mb = 64`

---

## Corruption Handling

**Decision: Skip corrupted entry, stop replaying that segment**

During replay, if we encounter a corrupted entry (CRC mismatch or
unexpected EOF), we:

1. Log a warning with the sequence number and segment file
2. Discard that entry
3. Stop reading further entries in that segment
4. Continue to the next segment (if any)

### Why stop at the first corruption?

If entry N is corrupted, entries N+1, N+2, etc. in the same segment MIGHT
be valid, but they also might not be. The most common corruption cause is
a crash during write — which means entry N is the last entry being written,
and there are no entries after it. Trying to parse bytes after a corrupted
entry risks misinterpreting garbage as valid entries.

Stopping at the first corruption is the safe, conservative choice that
every production WAL implementation uses.

### What about corruption in sealed segments?

This would indicate a disk error, not a crash during write (since sealed
segments are immutable). This is a serious error — we log it at ERROR level
and continue replaying subsequent segments. The corrupted entries are lost,
but the system remains available.

---

## Thread Safety

**Decision: Single writer, protected by a lock**

Only one thread can append to the WAL at a time. We use a ReentrantLock
(not synchronized) so we can do try-lock for non-blocking paths if needed.

### Why single writer?

- Sequence numbers must be strictly ordered — concurrent writers would
  need coordination anyway
- The WAL file is a single file — concurrent appends would interleave bytes
  and corrupt the file
- The lock is held only for the duration of serializing and writing one
  entry (microseconds). This will not be a bottleneck — the memory HashMap
  update after the WAL write takes longer.

### Readers

The WALReader (used during replay on startup) runs single-threaded before
the node accepts traffic. No concurrent reader/writer issues.

The WALCompactor reads segment metadata (first/last sequence numbers) but
does not read entry contents. It only needs to know if a segment is safe
to delete.

---

## API (Public Interface)

The WAL module exposes one main interface that the storage engine depends on:

```
interface WAL extends Lifecycle {

    // Append a put operation to the log. Returns the assigned sequence number.
    long appendPut(byte[] key, byte[] value);

    // Append a delete operation to the log. Returns the assigned sequence number.
    long appendDelete(byte[] key);

    // Replay all entries from the given sequence number (inclusive).
    // Calls the visitor for each valid entry in order.
    void replay(long fromSequence, WALEntryVisitor visitor);

    // Get the current (latest) sequence number.
    long getCurrentSequence();

    // Force an fsync of the active segment. Blocks until complete.
    void sync();

    // Delete all segments with entries up to the given sequence number.
    void truncate(long upToSequence);
}
```

```
interface WALEntryVisitor {
    // Called for each valid entry during replay.
    void onPut(long sequence, byte[] key, byte[] value);
    void onDelete(long sequence, byte[] key);
}
```

```
interface Lifecycle {
    void start();
    void stop();
    boolean isRunning();
}
```

### Why a Visitor pattern for replay?

During replay, we don't want to load all entries into a list (could be
millions of entries, would consume tons of memory). Instead, the WALReader
calls the visitor for each entry as it reads it. The StorageEngine provides
a visitor that applies each entry to the HashMap.

### Why WAL extends Lifecycle?

start() opens the active segment file and starts the background sync thread.
stop() flushes, fsyncs, and closes all files. The server calls these during
startup and shutdown.

---

## Implementation Classes

### WALEntry (sealed interface)

Represents a single log entry. Two variants:
- PutEntry: sequence, key, value, crc
- DeleteEntry: sequence, key, crc

Used internally for serialization/deserialization. Not exposed beyond the
WAL module.

### WALWriter

Responsible for:
- Maintaining the active segment file (FileChannel)
- Assigning sequence numbers (AtomicLong counter)
- Serializing entries to the binary format described above
- Writing serialized bytes to the file
- Rotating to a new segment when size limit is reached
- The lock for single-writer thread safety lives here

### WALReader

Responsible for:
- Reading a segment file from beginning to end
- Deserializing binary entries
- Verifying CRC checksums
- Calling the WALEntryVisitor for each valid entry
- Handling corruption (skip + stop in that segment)

### WALSegment

Represents one segment file. Knows:
- Its file path
- Its state (ACTIVE or SEALED)
- The range of sequence numbers it contains (first and last)
- Its size on disk

### WALCompactor

Background task that:
- Runs periodically (e.g., every 60 seconds)
- Checks which segments contain only entries ≤ the latest snapshot sequence
- Deletes those segments
- Does NOT run until the snapshot system (Phase 3) is built. But we include
  the truncate() API now so the interface is ready.

### InMemoryWAL

A test implementation of the WAL interface. Stores entries in an ArrayList
in memory. No files, no CRC, no segments. Used by other modules' tests
so they don't need a real filesystem.

---

## File I/O Details

### Writing (WALWriter)

We use java.nio.channels.FileChannel for all I/O:
- FileChannel.write(ByteBuffer) for appending entries
- FileChannel.force(true) for fsync (flushes both data and metadata)

We allocate a reusable ByteBuffer per writer to avoid allocation on every
write. For entries with large values (tensor data), we use a direct
ByteBuffer to avoid copying through the Java heap.

### Reading (WALReader)

We use FileChannel.read(ByteBuffer) with a buffer:
1. Read 4 bytes → entry_length
2. Read entry_length bytes into buffer
3. Verify CRC
4. Parse fields
5. Call visitor
6. Repeat until EOF

### Why FileChannel over RandomAccessFile?

FileChannel provides:
- Direct ByteBuffer support (avoids one memory copy)
- Explicit force() for fsync (vs fd.sync() which is less controlled)
- Better integration with Java NIO

### Why not MappedByteBuffer (mmap)?

Memory-mapped files are fast for reads but have downsides for a WAL:
- File size must be known upfront or remapped when growing
- Harder to control when data is flushed to disk (OS decides)
- fsync semantics with mmap are platform-dependent
- We don't need random reads — we only append and sequential-read on replay

FileChannel with explicit write + force is simpler and more predictable.

---

## Configuration

```yaml
wal:
  directory: /data/kvcache/wal      # Where segment files are stored
  segment:
    size_mb: 64                      # Max segment size before rotation
  sync:
    interval_ms: 100                 # Batch fsync interval (0 = every entry)
  max_entry_size_mb: 16              # Reject entries larger than this
  compaction:
    interval_seconds: 60             # How often compactor checks for old segments
```

---

## Recovery Sequence

On node startup:

```
1. List all segment files in wal.directory
2. Sort by segment number (ascending)
3. Read the last segment's last valid sequence number → this is where we left off
4. If a snapshot exists (Phase 3, not yet built):
     - Load snapshot
     - Set replay_from = snapshot_sequence + 1
   Else:
     - Set replay_from = 0 (replay everything)
5. For each segment in order:
     - Skip segments whose entries are all < replay_from
     - Replay entries ≥ replay_from via WALEntryVisitor
6. After replay completes, open a new active segment
7. Set sequence counter to (last replayed sequence + 1)
8. Start the background sync thread
9. Node is ready to accept traffic
```

---

## Testing Strategy

### Unit tests
- Serialize an entry → deserialize → verify all fields match
- Verify CRC detects single-bit corruption
- Verify entry_length mismatch is detected as corruption
- Verify sequence numbers are monotonically increasing

### Integration tests
- Write 1000 entries → close → replay → verify all 1000 are recovered
- Write 1000 entries → corrupt the last entry (truncate file by N bytes)
  → replay → verify 999 are recovered, corrupted one is skipped
- Write entries until segment rotates → verify two segment files exist
  → replay across both → verify all entries recovered
- Write entries → call truncate(500) → verify segments up to 500 are deleted

### Crash simulation tests
- Write entries in a loop, kill the process at a random point (or simulate
  by truncating the file mid-entry) → restart → verify recovery works and
  no valid entries are lost
- Write a large entry (multi-MB tensor) → truncate the file mid-value
  → verify CRC catches it

### Performance tests
- Measure append throughput: target 100,000+ entries/sec for small entries
- Measure replay speed: target 500,000+ entries/sec (sequential read is fast)
- Measure fsync latency at different batch intervals

---

## Open Questions (to resolve during implementation)

1. Should we pre-allocate segment files (fallocate) to avoid filesystem
   fragmentation? Probably yes on Linux, but adds complexity.

2. Should the WALCompactor run on a virtual thread or a scheduled executor?
   Either works; scheduled executor is simpler for periodic tasks.

3. Should we add a WAL entry for "snapshot completed at sequence N" so that
   the WAL itself records when it's safe to truncate? This would make the
   WAL self-contained. Decide in Phase 3.