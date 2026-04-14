package com.kvcache.core.api;

/**
 * Contract for components with explicit start/stop semantics.
 *
 * <p>Implemented by anything that manages background threads, open file handles,
 * or network connections. The composition root in {@code server} calls these
 * in dependency order at startup and in reverse order at shutdown.
 *
 * <p>Both {@link #start()} and {@link #stop()} are idempotent: calling them
 * on a component that is already in the target state must be a no-op.
 */
public interface Lifecycle {

    /**
     * Starts the component and allocates its resources.
     *
     * @throws Exception if the component cannot start (e.g. port already bound,
     *                   file not found). The caller should treat this as fatal.
     */
    void start() throws Exception;

    /**
     * Stops the component and releases all resources.
     *
     * <p>Implementations must not throw if already stopped.
     *
     * @throws Exception if the component cannot shut down cleanly. Resources
     *                   should still be released on a best-effort basis.
     */
    void stop() throws Exception;

    /**
     * Returns {@code true} if the component has been started and not yet stopped.
     */
    boolean isRunning();
}
