package com.kvcache.storage.api;

import com.kvcache.core.api.Lifecycle;
import com.kvcache.core.model.CacheKey;
import com.kvcache.core.model.CacheValue;
import com.kvcache.storage.exception.EntryTooLargeException;
import com.kvcache.storage.model.StorageStats;

public interface StorageEngine extends Lifecycle {

    long put(CacheKey key, byte[] value, long ttlMs);

    long put(CacheKey key, byte[] value);

    CacheValue get(CacheKey key);

    boolean delete(CacheKey key);

    boolean contains(CacheKey key);

    StorageStats getStats();

    void forceSnapshot();

    long getCurrentSequence();
}
