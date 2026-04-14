package com.kvcache.core.model;

/**
 * Typed identifier for a cluster node.
 *
 * <p>Wrapping a {@code String} in a record prevents {@code NodeId} values from
 * being accidentally passed where plain strings are expected (and vice versa),
 * which catches a class of bugs at compile time rather than at runtime.
 *
 * <p>The string value is typically either a human-readable name (e.g.
 * {@code "node-1"}) or a {@code "host:port"} address (e.g.
 * {@code "10.0.0.1:7000"}). The format is not enforced here; callers
 * define the convention.
 *
 * @param value the node identifier string; must not be blank
 */
public record NodeId(String value) {

    public NodeId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("NodeId value must not be blank");
        }
    }

    /** Returns the raw identifier string. Equivalent to {@link #value()}. */
    @Override
    public String toString() {
        return value;
    }
}
