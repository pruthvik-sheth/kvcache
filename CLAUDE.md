# KVCache — Distributed KV Cache for LLM Serving

## What is this?
A distributed, replicated key-value cache designed for storing LLM attention
KV tensors. Think "Redis but purpose-built for LLM inference" with high
availability and disaster recovery as first-class concerns.

## Architecture
- Module-per-concern: each module owns one distributed systems concept
- Modules communicate through an EventBus, not direct calls
- All I/O is behind interfaces for testability (inspired by FoundationDB)
- No frameworks (no Spring, no Guice) — manual dependency injection via composition root

## Tech Stack
- Java 21 (use records, sealed interfaces, virtual threads, Panama Foreign Memory API)
- Bazel with Bzlmod for builds
- gRPC + Protobuf for inter-node communication
- JUnit 5 for testing
- Javalin for the dashboard web UI

## Module Dependency Graph
```
proto ← (no deps)
core ← proto
wal ← core
storage ← core, wal
partition ← core
replication ← core, storage, partition, proto
gossip ← core, proto
llm ← core, storage
client ← core, partition, proto
server ← all of the above
dashboard ← core, server
```

## Project Structure
Every module follows:
```
module-name/
├── BUILD
├── README.md
├── src/com/kvcache/modulename/
│   ├── api/          # Public interfaces (contracts)
│   ├── impl/         # Implementations
│   ├── model/        # Data types (records, enums)
│   └── exception/    # Module-specific exceptions
└── test/com/kvcache/modulename/
    ├── unit/
    ├── integration/
    └── fixtures/
```

## Conventions
- Every public class has javadoc explaining its purpose and responsibilities
- Use sealed interfaces for type hierarchies (e.g., WALEntry)
- Use records for immutable data types
- No mocking frameworks — write real fakes (e.g., InMemoryWAL)
- Every module has a README.md explaining its design decisions

## Design Docs
Design docs live in docs/ — one per module. ALWAYS read the relevant design
doc before implementing a module. The design doc is the source of truth for
all decisions. Do not deviate without noting why.

## Build Commands
```
bazel build //...          # Build everything
bazel test //...           # Test everything
bazel test //wal:wal_test  # Test one module
bazel run //server         # Run a node
```

## Current Phase
Phase 1: WAL (Write-Ahead Log)
Design doc: docs/wal-design.md