package com.kvcache.core.api;

/**
 * Health check contract for components that expose operational status.
 *
 * <p>Used by the dashboard module to display per-component health and by
 * the gossip module to determine whether the local node should advertise
 * itself as ready to serve traffic.
 */
public interface HealthCheck {

    /**
     * Returns {@code true} if the component is operating normally and ready
     * to handle requests.
     */
    boolean isHealthy();

    /**
     * Returns a human-readable description of the component's current state.
     * When unhealthy, this must include enough detail to diagnose the failure
     * (e.g. "WAL segment write failed: No space left on device").
     */
    String getStatus();
}
