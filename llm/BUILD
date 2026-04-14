load("@rules_java//java:defs.bzl", "java_library", "java_test")

# LLM module: attention-tensor-oriented interface over the storage layer.

java_library(
    name = "llm",
    srcs = glob(["src/**/*.java"], allow_empty = True),
    exports = [
        "//core",
        "//storage",
    ],
    visibility = ["//visibility:public"],
)

# Uncomment once test sources exist:
# java_test(
#     name = "llm_test",
#     srcs = glob(["test/**/*.java"], allow_empty = True),
#     test_class = "com.kvcache.llm.AllTests",
#     deps = [
#         ":llm",
#         "//core",
#         "//storage",
#         "@maven//:org_junit_jupiter_junit_jupiter_api",
#         "@maven//:org_junit_jupiter_junit_jupiter_engine",
#         "@maven//:org_junit_platform_junit_platform_launcher",
#     ],
# )
