load("@rules_java//java:defs.bzl", "java_library", "java_test")

# Storage module: KV tensor persistence backed by the WAL.

java_library(
    name = "storage",
    srcs = glob(["src/**/*.java"], allow_empty = True),
    exports = [
        "//core",
        "//wal",
    ],
    visibility = ["//visibility:public"],
)

# Uncomment once test sources exist:
# java_test(
#     name = "storage_test",
#     srcs = glob(["test/**/*.java"], allow_empty = True),
#     test_class = "com.kvcache.storage.AllTests",
#     deps = [
#         ":storage",
#         "//core",
#         "//wal",
#         "@maven//:org_junit_jupiter_junit_jupiter_api",
#         "@maven//:org_junit_jupiter_junit_jupiter_engine",
#         "@maven//:org_junit_platform_junit_platform_launcher",
#     ],
# )
