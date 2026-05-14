# Storage Engine Design Document

## Overview

The storage engine is the central data layer of each node. It holds all
cached data in memory for fast access and coordinates with the WAL for
durability and snapshots for fast recovery.

It composes three internal components:
- **MemTable**: ConcurrentHashMap holding live data
- **WAL**: append-only log for crash durability (already built)
- **SnapshotManager**: periodic dumps of the full MemTable to disk

Other modules (replication, server, client) interact only with the
StorageEngine interface. They never touch the MemTable, WAL, or
snapshots directly.

### Goals
- **Fast reads**: sub-microsecond HashMap lookups
- **Durable writes**: every mutation is WAL-backed before acknowledgment
- **Bounded memory**: configurable max memory with automatic eviction
- **Fast recovery**: snapshot + WAL replay, not full WAL replay
- **TTL support**: entries auto-expire after a configurable duration

### Non-Goals
- No range queries (we only do point lookups by exact key)
- No transactions (single-key operations only)
- No disk-based overflow (if memory is full, we evict, not spill to disk)

---

## Write Path

Every write follows this exact sequence. Order matters for correctness.

```
put(key, value, ttlMs):
    1. Check entry size against max_entry_size limit → reject if too large
    2. Check if memory is above eviction threshold → evict if needed
    3. Append PutEntry to WAL → get back sequence number
    4. Insert into MemTable (key → CacheValue with timestamp, ttl, size)
    5. Update memory accounting (add new entry size, subtract old if overwrite)
    6. Return sequence number to caller
```

Why this order:

- Step 2 before step 3: we make room before writing, not after. If we
  wrote to WAL first and then couldn't evict, we'd have a WAL entry
  for data that was never stored.
- Step 3 before step 4: if we crash after WAL append but before HashMap
  insert, replay will redo the insert. If we did it the other way
  (HashMap first, WAL second) and crashed between them, the data would
  be in memory but not durable — and lost on restart.

### Delete Path

```
delete(key):
    1. Append DeleteEntry to WAL → get back sequence number
    2. Remove from MemTable
    3. Update memory accounting (subtract entry size)
    4. Return sequence number to caller
```

If the key doesn't exist, this is a no-op but still appended to WAL
(so that replay produces the correct final state).

---

## Read Path

```
get(key):
    1. Look up key in MemTable
    2. If not found → return empty/null (cache miss)
    3. If found → check TTL:
       a. If expired → remove from MemTable, update memory accounting,
          return empty/null (treat as miss)
       b. If valid → update access time (for LRU tracking), return value
```

Reads do NOT touch the WAL. The WAL is write-only during normal operation.

### TTL Expiration

Expired entries are cleaned up in two ways:

**Lazy expiration**: on read, if the entry is expired, delete it and
return a miss. This is what happens in the read path above.

**Active expiration**: a background task runs every 30 seconds, samples
random keys, and deletes expired ones. This prevents memory from filling
up with expired entries that nobody reads. We don't scan the entire
HashMap (too expensive) — we sample a random subset each cycle.

---

## MemTable

The MemTable is the in-memory data structure holding all live cache entries.

### Data Structure

```
ConcurrentHashMap<CacheKey, MemTableEntry>
```

Where MemTableEntry is:
```
record MemTableEntry(
    byte[] value,
    long timestamp,        // when this entry was written (epoch ms)
    long ttlMs,            // time-to-live in ms (0 = no expiry)
    long lastAccessTime,   // when this entry was last read (for LRU)
    int sizeBytes          // key size + value size + overhead
)
```

### Why ConcurrentHashMap?

- Thread-safe without external locking for read/write operations
- Lock-striped internally so concurrent reads and writes to different
  keys don't block each other
- Good enough performance for our use case — millions of ops/sec

### Memory Accounting

We track total memory usage with a single AtomicLong counter.

On every put:
```
newSize = keyBytes + valueBytes + ENTRY_OVERHEAD
oldSize = previous entry's size (if overwriting), else 0
memoryUsed.addAndGet(newSize - oldSize)
```

On every delete:
```
memoryUsed.addAndGet(-removedEntry.sizeBytes)
```

ENTRY_OVERHEAD is a constant (64 bytes) that accounts for:
- HashMap entry object header and pointers
- MemTableEntry object header
- CacheKey object

This is an estimate, not exact. Exact tracking would require
instrumentation that's more expensive than the approximation error.

---

## Eviction

When memoryUsed exceeds the eviction threshold (default: 85% of max
memory), the storage engine evicts entries until memory drops below
the target (default: 75% of max memory).

The gap between threshold (85%) and target (75%) is intentional. If
we evicted down to 84%, the next single write would trigger eviction
again. Evicting down to 75% gives breathing room.

### EvictionStrategy Interface

```
interface EvictionStrategy {
    // Select a batch of keys to evict from the MemTable.
    // Returns up to maxEntries keys ordered by eviction priority.
    List<CacheKey> selectForEviction(
        Iterable<Map.Entry<CacheKey, MemTableEntry>> entries,
        int maxEntries
    );
}
```

### LRU Eviction (default)

Selects the entries with the oldest lastAccessTime. Simple and effective.

Implementation: we don't maintain a separate LRU linked list (like
LinkedHashMap does). Instead, we sample N random entries from the
HashMap and evict the one with the oldest access time among the sample.
This is the "approximated LRU" algorithm that Redis uses.

Why sampling instead of exact LRU:
- Exact LRU requires a doubly-linked list updated on every read — extra
  pointer chasing and memory overhead
- Sampling with N=16 gives results nearly identical to exact LRU in
  practice (Redis proved this empirically)
- Simpler, faster, less memory overhead

### Cost-Aware Eviction (LLM-specific)

Selects entries that are cheapest to recompute. Each entry has an
implicit cost based on:
- Number of tokens in the session (more tokens = more expensive prefill)
- Whether the entry is a shared prefix (shared = very expensive to evict,
  many sessions would need to recompute)

Cost is estimated as:
```
cost = tokenPosition * layerCount
```

Higher cost = keep longer. Lower cost = evict first. This means:
- Early tokens in a short session → cheap → evict first
- Late tokens in a long session → expensive → keep longer

### Eviction and WAL

Evicted entries are NOT written to the WAL as delete operations. Why?
On recovery, we want to restore the full cache state from the WAL and
then let eviction happen naturally as memory fills up. If we logged
evictions, recovery would replay the evictions and we'd lose cache
entries unnecessarily.

Eviction is a runtime-only operation. It reduces memory but doesn't
affect the durable log.

---

## Snapshots

### What a Snapshot Contains

A snapshot is a serialized dump of the entire MemTable at a specific
point in time. It captures:
- The WAL sequence number at the time of the snapshot
- Every key-value pair with its metadata (timestamp, ttl)
- A CRC32 checksum over the entire file

### Snapshot File Format

```
┌──────────────────┬─────────────┬─────────────────────────────────┬──────────┐
│ snapshot_sequence │ entry_count │ entries...                      │ crc32    │
│ (8 bytes)        │ (4 bytes)   │ (variable)                      │ (4 bytes)│
└──────────────────┴─────────────┴─────────────────────────────────┴──────────┘

Each entry:
┌─────────────┬───────────┬───────────────┬─────────────┬───────────┬──────────┐
│ key_length  │ key_bytes │ value_length  │ value_bytes │ timestamp │ ttl_ms   │
│ (4 bytes)   │ (variable)│ (4 bytes)     │ (variable)  │ (8 bytes) │ (8 bytes)│
└─────────────┴───────────┴───────────────┴─────────────┴───────────┴──────────┘
```

### Snapshot File Naming

```
snapshots/
├── snapshot-00009900000.dat    (older, deletable)
└── snapshot-00009990000.dat    (latest)
```

The number is the WAL sequence number at snapshot time.

### When to Snapshot

**Trigger: every 100,000 WAL entries since the last snapshot.**

The storage engine tracks how many entries have been written since the
last snapshot. When it crosses 100,000, it triggers a snapshot on a
background thread.

Why 100,000:
- Recovery replays at most 100,000 entries (a few seconds at most)
- Snapshots of a 4GB cache take ~5-10 seconds to write
- At 10,000 writes/sec, this triggers every ~10 seconds — frequent
  enough for fast recovery, infrequent enough to not hurt throughput

Configurable: `storage.snapshot.trigger.entries = 100000`

### How Snapshots Work (Copy-on-Write)

Taking a snapshot must not block the write path. Here's the process:

```
1. Record the current WAL sequence number → snapshot_sequence
2. Create a shallow copy of the MemTable's entry set
   (ConcurrentHashMap iteration gives a weakly consistent view,
   which is fine — it captures entries that existed at approximately
   the time of the snapshot)
3. On a background thread:
   a. Open a new snapshot file
   b. Write snapshot_sequence and entry_count
   c. Iterate the copied entries, serialize each one
   d. Compute CRC over everything written
   e. Write CRC
   f. Fsync and close the file
4. After snapshot completes, tell WAL to truncate entries before
   snapshot_sequence (they're now captured in the snapshot)
```

The write path continues normally during step 3. New writes after
snapshot_sequence are not in the snapshot — they're in the WAL and
will be replayed on recovery.

### Snapshot Cleanup

Keep only the last 2 snapshots. When a new snapshot completes, delete
any snapshots older than the second most recent. Keeping 2 (not just 1)
protects against a crash during snapshot writing — if the newest snapshot
is corrupted, we fall back to the previous one.

---

## Recovery Sequence

On node startup, the storage engine orchestrates recovery:

```
1. Look for snapshot files in the snapshots directory
2. If snapshots exist:
   a. Load the most recent snapshot (verify CRC)
   b. If CRC fails, try the second most recent snapshot
   c. If both fail, fall back to full WAL replay
   d. Populate MemTable from snapshot
   e. Set replay_from = snapshot_sequence + 1
3. If no snapshots:
   a. Set replay_from = 0
4. Call wal.replay(replay_from, visitor) where visitor does:
   - onPut → insert into MemTable
   - onDelete → remove from MemTable
5. Update memory accounting to reflect loaded state
6. Start background threads:
   - Snapshot scheduler
   - TTL expiration scanner
   - Eviction monitor
7. Storage engine is ready to serve
```

---

## Configuration

```yaml
storage:
  max_memory_mb: 4096               # Maximum memory for cache entries
  eviction:
    threshold_percent: 85            # Start evicting at this memory usage
    target_percent: 75               # Evict down to this level
    strategy: lru                    # "lru" or "cost_aware"
    sample_size: 16                  # Number of random entries to sample for LRU
  snapshot:
    directory: /data/kvcache/snapshots
    trigger_entries: 100000          # Snapshot every N WAL entries
    keep_count: 2                    # Number of snapshots to retain
  ttl:
    cleanup_interval_seconds: 30     # Active expiration scan interval
    cleanup_sample_size: 100         # Keys to sample per scan
  max_entry_size_mb: 16              # Reject entries larger than this
```

---

## API (Public Interface)

```
interface StorageEngine extends Lifecycle {

    // Store a key-value pair with a TTL.
    // Returns the WAL sequence number for this write.
    long put(CacheKey key, byte[] value, long ttlMs);

    // Store a key-value pair with no expiry.
    long put(CacheKey key, byte[] value);

    // Retrieve a value by key. Returns null on miss or expiry.
    CacheValue get(CacheKey key);

    // Delete a key. Returns true if the key existed.
    boolean delete(CacheKey key);

    // Check if a key exists (without updating access time).
    boolean contains(CacheKey key);

    // Get current memory usage stats.
    StorageStats getStats();

    // Force a snapshot to be taken now. Blocks until complete.
    void forceSnapshot();

    // Get the current WAL sequence number.
    long getCurrentSequence();
}
```

```
record StorageStats(
    long entryCount,
    long memoryUsedBytes,
    long maxMemoryBytes,
    double memoryUsagePercent,
    long totalPuts,
    long totalGets,
    long totalDeletes,
    long cacheHits,
    long cacheMisses,
    double hitRate,
    long evictionCount,
    long lastSnapshotSequence,
    long entriesSinceSnapshot
)
```

---

## Implementation Classes

### LocalStorageEngine

The main implementation. Composes:
- WAL (injected via constructor — interface, not FileWAL directly)
- MemTable (created internally)
- SnapshotManager (created internally)
- EvictionStrategy (injected via constructor)

This is the only class in the module that other modules interact with
(through the StorageEngine interface).

### MemTable

Wraps ConcurrentHashMap with:
- Memory accounting (AtomicLong tracking total bytes)
- Access time tracking (updated on get for LRU)
- Iteration support (for snapshots and eviction sampling)
- TTL checking (on read)

### MemTableEntry

Record holding the value and metadata for one cache entry:
- value (byte[])
- timestamp (when written)
- ttlMs (time to live)
- lastAccessTime (for LRU)
- sizeBytes (for memory accounting)

### SnapshotManager

Handles:
- Writing snapshots to disk (serialize MemTable, CRC, fsync)
- Loading snapshots from disk (deserialize, verify CRC)
- Snapshot scheduling (count WAL entries, trigger at threshold)
- Cleanup of old snapshots

### SnapshotFile

Handles reading/writing the binary snapshot format. Similar to
WALSegment but for the snapshot format described above.

### LRUEviction

Implements EvictionStrategy. Samples random entries, picks the one
with the oldest lastAccessTime.

### CostAwareEviction

Implements EvictionStrategy. Samples random entries, picks the one
with the lowest recomputation cost (based on token position and
layer index from the CacheKey).

### TTLCleaner

Background task that:
- Runs every 30 seconds
- Samples random keys from MemTable
- Removes expired ones
- Updates memory accounting

### InMemoryStorageEngine

Test implementation. Uses the InMemoryWAL from the WAL module.
No snapshots, no disk I/O. For use in other modules' tests.

---

## Thread Safety

The storage engine is accessed by multiple threads concurrently:
- gRPC handler threads calling put/get/delete
- Background snapshot thread
- Background TTL cleaner thread
- Background eviction thread (if eviction takes time)

Thread safety is provided by:
- ConcurrentHashMap for the MemTable (lock-striped internally)
- AtomicLong for memory accounting
- WAL's own internal lock for append serialization
- SnapshotManager runs on a single background thread

No external synchronization is needed for the common path (put/get).

---

## Interaction with WAL

The storage engine owns the WAL lifecycle:
- start(): calls wal.start(), runs recovery, starts background threads
- stop(): stops background threads, forces a final snapshot,
  calls wal.stop()

After recovery, the storage engine tells the WAL the last snapshot
sequence so the WAL can truncate old segments.

---

## Testing Strategy

### Unit tests
- MemTable: put/get/delete, memory accounting, TTL expiry on read
- LRUEviction: verify it picks the least recently accessed entry
- CostAwareEviction: verify it picks the lowest cost entry
- MemTableEntry: verify sizeBytes calculation

### Integration tests
- Write 1000 entries → verify all retrievable via get
- Write entries → force snapshot → clear MemTable → load snapshot
  → verify all entries restored
- Write entries → force snapshot → write 100 more → simulate crash
  (restart storage engine) → verify all entries recovered
  (snapshot + WAL replay)
- Fill memory to eviction threshold → verify eviction triggers and
  memory drops to target
- Write entries with TTL → wait for expiry → verify get returns null
- Write entries with TTL → trigger active expiration → verify memory
  freed

### Crash simulation tests
- Write entries → take snapshot → write more → kill process → restart
  → verify snapshot + WAL replay produces correct final state
- Corrupt snapshot file → restart → verify fallback to second snapshot
  or full WAL replay