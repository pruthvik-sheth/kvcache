load("@rules_java//java:defs.bzl", "java_library", "java_test")

# Partition module: key-space partitioning and request routing.

java_library(
    name = "partition",
    srcs = glob(["src/**/*.java"], allow_empty = True),
    exports = [
        "//core",
    ],
    visibility = ["//visibility:public"],
)

# Uncomment once test sources exist:
# java_test(
#     name = "partition_test",
#     srcs = glob(["test/**/*.java"], allow_empty = True),
#     test_class = "com.kvcache.partition.AllTests",
#     deps = [
#         ":partition",
#         "//core",
#         "@maven//:org_junit_jupiter_junit_jupiter_api",
#         "@maven//:org_junit_jupiter_junit_jupiter_engine",
#         "@maven//:org_junit_platform_junit_platform_launcher",
#     ],
# )
