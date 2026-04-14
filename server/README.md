# server

Composition root — wires all modules together and starts the node process.

## Responsibility

No business logic. Sole purpose: construct every module with its real
dependencies and start the gRPC server and gossip service.

## Startup Order

1. Load `NodeConfig` from environment / config file.
2. Construct `WAL` → `StorageEngine` → `PartitionRouter`.
3. Construct `GossipService`, `ReplicationManager`.
4. Register gRPC service implementations.
5. Start gRPC server (Netty), gossip fanout, and replication heartbeat.

## Package Layout

```
impl/       KVCacheServer (main entry point), NodeWiring
model/      NodeConfig, ServerMetrics
exception/  StartupException
```
