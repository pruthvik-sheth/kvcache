# storage

Persistent storage for KV attention tensors.

## Responsibility

Owns the on-disk layout of KV tensors. All writes go through the WAL first.
Reads use Panama Foreign Memory API (`MemorySegment`) for zero-copy access
into mapped tensor files.

## Write Path

```
caller → StorageEngine.write() → WAL.append() → fsync → index update → ack
```

## Package Layout

```
api/        StorageEngine interface, TensorKey, TensorValue
impl/       MappedFileStorageEngine (Panama MemorySegment)
model/      StorageMetrics, SegmentIndex
exception/  StorageException
```

## References

- Java 21 Panama Foreign Memory API (JEP 454)
