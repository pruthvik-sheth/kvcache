package com.kvcache.storage.exception;

import com.kvcache.core.exception.KVCacheException;

public class StorageException extends KVCacheException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
