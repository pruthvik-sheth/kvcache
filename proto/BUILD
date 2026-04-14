load("@rules_java//java:defs.bzl", "java_library")

# Proto module: owns all Protobuf/gRPC service definitions shared across the
# cluster. Once .proto files are added, replace the stub java_library below
# with the commented-out proto/grpc generation rules.

# TODO: Uncomment and populate once .proto files are added to src/:
#
# load("@rules_proto//proto:defs.bzl", "proto_library")
# load("@grpc-java//:java_grpc_library.bzl", "java_grpc_library")
#
# proto_library(
#     name = "kvcache_proto",
#     srcs = glob(["src/**/*.proto"], allow_empty = True),
#     visibility = ["//visibility:public"],
# )
#
# java_proto_library(
#     name = "kvcache_java_proto",
#     deps = [":kvcache_proto"],
#     visibility = ["//visibility:public"],
# )
#
# java_grpc_library(
#     name = "proto",
#     srcs = [":kvcache_proto"],
#     deps = [":kvcache_java_proto"],
#     visibility = ["//visibility:public"],
# )

# Stub — replaced by generated targets above when .proto files exist.
# Uses exports (not deps) so this is valid even when srcs is empty.
java_library(
    name = "proto",
    srcs = glob(["src/**/*.java"], allow_empty = True),
    exports = [
        "@maven//:com_google_protobuf_protobuf_java",
        "@maven//:io_grpc_grpc_api",
        "@maven//:io_grpc_grpc_stub",
        "@maven//:io_grpc_grpc_protobuf",
        "@maven//:javax_annotation_javax_annotation_api",
    ],
    visibility = ["//visibility:public"],
)
