load("@rules_java//java:defs.bzl", "java_library", "java_binary")

# Server module: composition root — wires all modules and starts the node.

java_library(
    name = "server_lib",
    srcs = glob(["src/**/*.java"], allow_empty = True),
    exports = [
        "//proto",
        "//core",
        "//wal",
        "//storage",
        "//partition",
        "//replication",
        "//gossip",
        "//client",
        "//llm",
        "@maven//:io_grpc_grpc_api",
        "@maven//:io_grpc_grpc_stub",
        "@maven//:io_grpc_grpc_netty",
        "@maven//:io_grpc_grpc_protobuf",
        "@maven//:javax_annotation_javax_annotation_api",
    ],
    visibility = ["//visibility:public"],
)

# Uncomment and set main_class when the server entrypoint is implemented:
# java_binary(
#     name = "server",
#     main_class = "com.kvcache.server.KVCacheServer",
#     runtime_deps = [":server_lib"],
#     visibility = ["//visibility:public"],
# )
