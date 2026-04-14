# client

Public SDK for LLM inference servers to talk to the KVCache cluster.

## Responsibility

- Routes requests to the correct node via `partition`.
- Retries on transient failures with exponential backoff + jitter.
- Hedged reads: send to primary, fan out to replica if no response in P95.
- Transparent failover on node failure (informed by `gossip` cluster view).

## Design

Fat-client architecture: routing logic lives here, not in a proxy. This saves
a network hop and lets inference servers pick the closest replica.

## Package Layout

```
api/        KVCacheClusterClient interface
impl/       RoutingKVCacheClusterClient
model/      ClientConfig, RetryPolicy
exception/  ClusterUnavailableException
```
