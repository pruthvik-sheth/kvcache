# core

Foundation module. Every other module (except `proto`) depends on `core`.

## Responsibility

- **EventBus** — intra-process pub/sub so modules communicate without direct
  references to each other. See `CLAUDE.md`: "Modules communicate through an
  EventBus, not direct calls."
- **Shared interfaces** — I/O abstractions that let implementations be swapped
  for fakes in tests (inspired by FoundationDB's simulation testing).
- **Common value types** — records and enums shared across modules.

## Package Layout

```
api/        NodeId, ClusterConfig, EventBus interface
impl/       DefaultEventBus
model/      ClusterState, NodeRole, etc.
exception/  KVCacheException base class
```
