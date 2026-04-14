load("@rules_java//java:defs.bzl", "java_library", "java_test")

# Core module: EventBus, shared interfaces, and common value types.
# All other modules depend on this one.

java_library(
    name = "core",
    srcs = glob(["src/**/*.java"], allow_empty = True),
    visibility = ["//visibility:public"],
)

# Uncomment once test sources exist:
# java_test(
#     name = "core_test",
#     srcs = glob(["test/**/*.java"], allow_empty = True),
#     test_class = "com.kvcache.core.AllTests",
#     deps = [
#         ":core",
#         "@maven//:org_junit_jupiter_junit_jupiter_api",
#         "@maven//:org_junit_jupiter_junit_jupiter_engine",
#         "@maven//:org_junit_platform_junit_platform_launcher",
#     ],
# )
