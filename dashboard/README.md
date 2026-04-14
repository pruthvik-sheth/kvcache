# dashboard

Operational web UI for the KVCache cluster.

## Responsibility

Read-only visibility into cluster health. Renders:
- Node membership and gossip state.
- Per-shard replication lag.
- WAL write throughput and storage utilisation.
- Active session count and eviction rate.

## Stack

**Javalin 6** — thin HTTP wrapper over Jetty. Chosen to avoid a full web
framework while keeping the UI self-contained (no separate React build step
for the first version).

## Package Layout

```
api/        DashboardServer interface
impl/       JavalinDashboardServer
model/      ClusterSnapshot, NodeStats
```
