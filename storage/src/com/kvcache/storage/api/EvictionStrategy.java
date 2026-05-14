package com.kvcache.storage.api;

import com.kvcache.core.model.CacheKey;
import com.kvcache.storage.model.MemTableEntry;

import java.util.List;
import java.util.Map;

public interface EvictionStrategy {

    List<CacheKey> selectForEviction(
            Iterable<Map.Entry<CacheKey, MemTableEntry>> entries,
            int maxEntries);
}
