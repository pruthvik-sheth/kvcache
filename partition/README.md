# partition

Key-space partitioning and request routing.

## Responsibility

Given a `TensorKey`, determines which node(s) own it. Exposes the partition
map to the `client` module for routing and to `replication` for replica
placement.

## Strategy

Consistent hashing with virtual nodes. The ring is stored in `core` cluster
state; this module provides the lookup logic.

## Package Layout

```
api/        PartitionRouter interface, Ring
impl/       ConsistentHashRouter
model/      VirtualNode, PartitionRange
exception/  NoOwnerException
```

