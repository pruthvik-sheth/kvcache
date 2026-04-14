# Design Overview

High-level architecture for KVCache. For module-specific decisions, see
`docs/<module>-design.md`.

## Guiding Principles

1. **Availability first** — every design decision is evaluated against the
   question "what happens when this node / rack / AZ fails?"
2. **Interface-driven I/O** — all I/O (disk, network, time) is behind
   interfaces. Real fakes, not mocks. Inspired by FoundationDB simulation.
3. **No magic** — no DI frameworks, no annotation processors, no runtime
   reflection. Explicit wiring in the composition root (`server`).

---

## Module Responsibilities

| Module        | Responsibility                                           |
|---------------|----------------------------------------------------------|
| `proto`       | Protobuf/gRPC service definitions                        |
| `core`        | EventBus, shared interfaces, common types                |
| `wal`         | Write-Ahead Log for crash-safe durability                |
| `storage`     | KV tensor persistence (WAL-backed, Panama zero-copy)     |
| `partition`   | Consistent-hash partitioning and routing                 |
| `replication` | Leader election, log replication, failover               |
| `gossip`      | SWIM-based cluster membership and failure detection      |
| `llm`         | Attention-tensor–oriented KV-cache interface             |
| `client`      | Public SDK: routing, retries, hedged reads, failover     |
| `server`      | Composition root — manual DI, gRPC server startup        |
| `dashboard`   | Read-only operational web UI (Javalin)                   |

## Module Dependency Graph

```
proto ← (no deps)
core ← proto
wal ← core
storage ← core, wal
partition ← core
replication ← core, storage, partition, proto
gossip ← core, proto
llm ← core, storage
client ← core, partition, proto
server ← all of the above
dashboard ← core, server
```

---

## Write Path (happy path)

```
LLM Inference Server
  → client (route via partition)
    → server gRPC handler
      → llm (translate tensor descriptor → storage key)
        → storage (write)
          → wal (append + fsync)
          ← sequence number
        ← ack
      ← ack
    ← ack (+ async replication to followers via replication)
  ← ack
```

## Read Path (happy path)

```
LLM Inference Server
  → client (route to primary, or nearest replica)
    → server gRPC handler
      → llm (translate tensor descriptor)
        → storage (mmap read → MemorySegment)
        ← MemorySegment (zero-copy)
      ← bytes / MemorySegment
    ← response
  ← response
```

---

## Failure Recovery

| Failure scenario         | Detection            | Recovery                          |
|--------------------------|----------------------|-----------------------------------|
| Process crash            | OS kills process     | WAL replay on restart             |
| Node unreachable         | Gossip (SWIM)        | Replication failover to follower  |
| AZ outage                | Gossip               | Cross-AZ replica takes over       |
| Corrupt segment          | CRC check on read    | Replay stops; alert + manual      |
| Split-brain              | Leader epoch fencing | Stale leader rejects writes       |

---

## Key References

- **JEP 454** — Foreign Memory Access API (Java 21)
- **SWIM paper** — Das, Gupta, Motivala (2002)
- `docs/wal-design.md` — WAL design (Phase 1)
