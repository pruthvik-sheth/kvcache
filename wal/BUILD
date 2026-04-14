load("@rules_java//java:defs.bzl", "java_library", "java_test")

# WAL module: Write-Ahead Log for crash-safe durability.
# See docs/wal-design.md.

java_library(
    name = "wal",
    srcs = glob(["src/**/*.java"], allow_empty = True),
    deps = [
        "//core",
    ],
    visibility = ["//visibility:public"],
)

java_test(
    name = "wal_test",
    srcs = glob(["test/**/*.java"], allow_empty = True),
    test_class = "com.kvcache.wal.WalTestSuite",
    deps = [
        ":wal",
        "//core",
        "@maven//:org_junit_jupiter_junit_jupiter_api",
        "@maven//:org_junit_jupiter_junit_jupiter_engine",
        "@maven//:org_junit_platform_junit_platform_launcher",
    ],
)
