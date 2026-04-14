load("@rules_java//java:defs.bzl", "java_library", "java_test")

# Replication module: leader election and log replication across replicas.

java_library(
    name = "replication",
    srcs = glob(["src/**/*.java"], allow_empty = True),
    exports = [
        "//core",
        "//storage",
        "//partition",
        "//proto",
        "@maven//:io_grpc_grpc_api",
        "@maven//:io_grpc_grpc_stub",
        "@maven//:io_grpc_grpc_netty",
        "@maven//:io_grpc_grpc_protobuf",
        "@maven//:javax_annotation_javax_annotation_api",
    ],
    visibility = ["//visibility:public"],
)

# Uncomment once test sources exist:
# java_test(
#     name = "replication_test",
#     srcs = glob(["test/**/*.java"], allow_empty = True),
#     test_class = "com.kvcache.replication.AllTests",
#     deps = [
#         ":replication",
#         "//core",
#         "//storage",
#         "//partition",
#         "//proto",
#         "@maven//:org_junit_jupiter_junit_jupiter_api",
#         "@maven//:org_junit_jupiter_junit_jupiter_engine",
#         "@maven//:org_junit_platform_junit_platform_launcher",
#     ],
# )
