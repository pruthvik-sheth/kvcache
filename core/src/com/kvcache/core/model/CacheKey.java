package com.kvcache.core.model;

/**
 * Identifies a single KV attention tensor in the cache.
 *
 * <p>Keys map directly onto the transformer attention layout:
 * a session (one inference request), a layer (one transformer block),
 * and a position (one token in the sequence).
 *
 * <p>The WAL wire format for a key is the UTF-8 string
 * {@code "{sessionId}:{layerIndex}:{tokenPosition}"}. The colon delimiter
 * is safe because session IDs are UUIDs (no colons). Maximum serialised
 * size is well under the 1 KB WAL key limit.
 *
 * @param sessionId     uniquely identifies the inference session (e.g. a UUID)
 * @param layerIndex    transformer layer index, zero-based
 * @param tokenPosition position in the token sequence, zero-based
 */
public record CacheKey(String sessionId, int layerIndex, int tokenPosition) {

    public CacheKey {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (layerIndex < 0) {
            throw new IllegalArgumentException("layerIndex must be >= 0, got " + layerIndex);
        }
        if (tokenPosition < 0) {
            throw new IllegalArgumentException("tokenPosition must be >= 0, got " + tokenPosition);
        }
    }

    /**
     * Serialises this key to the WAL wire format: {@code "sessionId:layerIndex:tokenPosition"}.
     */
    public String toWireString() {
        return sessionId + ":" + layerIndex + ":" + tokenPosition;
    }

    /**
     * Parses a wire-format string produced by {@link #toWireString()}.
     *
     * @throws IllegalArgumentException if {@code wire} is not a valid wire-format key
     */
    public static CacheKey fromWireString(String wire) {
        if (wire == null) {
            throw new IllegalArgumentException("wire string must not be null");
        }
        // Split into exactly 3 parts: sessionId may not contain colons (UUID format).
        String[] parts = wire.split(":", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("invalid CacheKey wire format: '" + wire + "'");
        }
        try {
            return new CacheKey(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid CacheKey wire format: '" + wire + "'", e);
        }
    }
}
