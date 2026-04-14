package com.kvcache.core.exception;

/**
 * Base runtime exception for all KVCache errors.
 *
 * <p>Every module defines its own subclass (e.g. {@code WALException},
 * {@code StorageException}) so callers can catch at whatever granularity
 * they need: a specific module's exception, or this base class to catch
 * anything from the KVCache stack.
 *
 * <p>Using a runtime exception (rather than checked) avoids forcing every
 * interface method to declare {@code throws KVCacheException}. Callers that
 * need to handle errors explicitly catch the relevant subclass.
 */
public class KVCacheException extends RuntimeException {

    public KVCacheException(String message) {
        super(message);
    }

    public KVCacheException(String message, Throwable cause) {
        super(message, cause);
    }
}
