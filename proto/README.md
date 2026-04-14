# proto

Shared Protobuf and gRPC service definitions for the KVCache cluster.

## Responsibility

Owns all `.proto` files used for inter-node communication. No hand-written
Java lives here — only service contracts that feed code generation via
`java_grpc_library`.

## Design Decisions

- All inter-node RPCs are defined here, keeping the wire protocol in one place.
- `java_grpc_library` generates both blocking and async stubs.

## Adding Proto Files

1. Add `.proto` files under `src/`.
2. Update `BUILD` — uncomment `proto_library`, `java_proto_library`, and
   `java_grpc_library` rules; remove the stub `java_library`.
3. Update dependents to reference `:proto` (the grpc library target).
