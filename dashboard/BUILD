load("@rules_java//java:defs.bzl", "java_library", "java_binary")

# Dashboard module: read-only operational web UI built with Javalin.

java_library(
    name = "dashboard_lib",
    srcs = glob(["src/**/*.java"], allow_empty = True),
    exports = [
        "//core",
        "//server:server_lib",
        "@maven//:io_javalin_javalin",
        "@maven//:org_slf4j_slf4j_api",
        "@maven//:org_slf4j_slf4j_simple",
    ],
    visibility = ["//visibility:public"],
)

# Uncomment and set main_class when the dashboard entrypoint is implemented:
# java_binary(
#     name = "dashboard",
#     main_class = "com.kvcache.dashboard.Dashboard",
#     runtime_deps = [":dashboard_lib"],
#     visibility = ["//visibility:public"],
# )
