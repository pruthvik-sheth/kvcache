package com.kvcache.core.model;

/**
 * Lifecycle state of a cluster node as tracked by the gossip subsystem.
 *
 * <p>The intended state machine is:
 * <pre>
 *   JOINING ──► ALIVE ──► SUSPECT ──► DEAD
 *                    └──► LEAVING
 * </pre>
 *
 * <p>The SUSPECT state exists to allow indirect-ping probing (per the SWIM
 * protocol) before a node is declared DEAD. A direct ping timeout alone is
 * insufficient to distinguish a dead node from a temporarily congested
 * network path.
 */
public enum NodeState {

    /** The node is bootstrapping and not yet ready to serve traffic. */
    JOINING,

    /** The node is reachable and serving requests normally. */
    ALIVE,

    /**
     * The node missed a direct ping. The gossip layer is issuing indirect
     * probes via other nodes before promoting this node to {@link #DEAD}.
     */
    SUSPECT,

    /** The node is confirmed unreachable after all probe attempts failed. */
    DEAD,

    /**
     * The node has signalled a graceful shutdown and is draining in-flight
     * requests. It will not accept new work.
     */
    LEAVING
}
