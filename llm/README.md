# llm

Attention-tensor–oriented interface for LLM inference servers.

## Responsibility

Translates LLM-native concepts (session ID, layer index, head range) into
storage-layer keys and byte ranges. The `client` module uses this interface;
the `storage` module doesn't know about LLM semantics.

## Zero-Copy Design

Reads return `MemorySegment` (Java 21 Panama API) backed by the storage
layer's memory-mapped files. No intermediate byte-array copies on the hot
path.

## Package Layout

```
api/        KVCacheClient interface, TensorDescriptor, SessionId
impl/       DefaultKVCacheClient
model/      LayerRange, HeadRange, AttentionTensor
exception/  SessionNotFoundException, TensorShapeException
```

## References

- Java 21 Panama Foreign Memory API (JEP 454)
