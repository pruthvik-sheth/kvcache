# replication

Leader-based replication with configurable durability.

## Responsibility

- Leader election per partition shard.
- Log replication from leader to followers.
- Configurable acknowledgment policy (sync/async, quorum size).
- Automatic failover when the leader is unresponsive.

## Durability Policy

The replication factor and ack policy are configurable at the cluster level
and per-shard. Default: synchronous replication to 2 replicas before ACK.

## Package Layout

```
api/        ReplicationManager interface, AckPolicy
impl/       LeaderReplicationManager, FollowerReplicationHandler
model/      ReplicaSet, ReplicationLag, LeaderEpoch
exception/  ReplicationException, LeaderNotFoundException
```

