load("@rules_java//java:defs.bzl", "java_library", "java_test")

# Gossip module: epidemic-style cluster membership and failure detection.

java_library(
    name = "gossip",
    srcs = glob(["src/**/*.java"], allow_empty = True),
    exports = [
        "//core",
        "//proto",
        "@maven//:io_grpc_grpc_api",
        "@maven//:io_grpc_grpc_stub",
        "@maven//:io_grpc_grpc_netty",
        "@maven//:javax_annotation_javax_annotation_api",
    ],
    visibility = ["//visibility:public"],
)

# Uncomment once test sources exist:
# java_test(
#     name = "gossip_test",
#     srcs = glob(["test/**/*.java"], allow_empty = True),
#     test_class = "com.kvcache.gossip.AllTests",
#     deps = [
#         ":gossip",
#         "//core",
#         "//proto",
#         "@maven//:org_junit_jupiter_junit_jupiter_api",
#         "@maven//:org_junit_jupiter_junit_jupiter_engine",
#         "@maven//:org_junit_platform_junit_platform_launcher",
#     ],
# )
