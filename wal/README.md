# wal

Write-Ahead Log — Phase 1 implementation target.

## Responsibility

Provides crash-safe durability. Every write is logged here before any
acknowledgment is sent to the caller. On node restart, the WAL is replayed
to recover in-memory state.

## Design

See `docs/wal-design.md` for the full design document (source of truth).

Key decisions:
- **Segmented log** — bounded recovery time, enables compaction.
- **Sealed interface `WALEntry`** — exhaustive pattern matching in the reader.
- **`WAL` interface in `api/`** — `InMemoryWAL` fake in `test/fixtures/` for
  tests; real `SegmentedWAL` in `impl/`.

## Design Doc

See `docs/wal-design.md`.
