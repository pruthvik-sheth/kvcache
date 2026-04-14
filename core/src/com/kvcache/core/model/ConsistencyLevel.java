package com.kvcache.core.model;

/**
 * Read/write consistency policy for cache operations.
 *
 * <p>Controls how many replicas must acknowledge a write, or respond to a read,
 * before the operation is considered complete. This is specified per-request,
 * not per-cluster, so latency-sensitive workloads can use {@link #ONE} while
 * durability-sensitive writes use {@link #QUORUM} or {@link #ALL}.
 *
 * <p>The WAL provides crash durability independently of this setting.
 * Consistency level only governs how many nodes must acknowledge before the
 * client call returns.
 */
public enum ConsistencyLevel {

    /**
     * One replica must acknowledge. Lowest latency; the response may come from
     * a replica that is slightly behind the leader.
     */
    ONE,

    /**
     * A majority of replicas ({@code floor(N/2) + 1}) must acknowledge.
     * Balances latency and consistency. Default for most operations.
     */
    QUORUM,

    /**
     * All replicas must acknowledge. Strongest consistency guarantee;
     * the operation fails if any replica is unreachable.
     */
    ALL
}
