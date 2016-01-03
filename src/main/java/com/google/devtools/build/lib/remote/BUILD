package(
    default_visibility = ["//src:__subpackages__"],
)

java_library(
    name = "remote",
    srcs = glob(["*.java"]),
    data = [
        "//src/main/tools:process-wrapper",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:build-base",
        "//src/main/java/com/google/devtools/build/lib:concurrent",
        "//src/main/java/com/google/devtools/build/lib:events",
        "//src/main/java/com/google/devtools/build/lib:io",
        "//src/main/java/com/google/devtools/build/lib:packages-internal",
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib:shell",
        "//src/main/java/com/google/devtools/build/lib:util",
        "//src/main/java/com/google/devtools/build/lib:vfs",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/rules/apple",
        "//src/main/java/com/google/devtools/build/lib/rules/cpp",
        "//src/main/java/com/google/devtools/build/lib/standalone:standalone",
        "//src/main/java/com/google/devtools/build/lib/worker:worker",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/protobuf:remote_protocol_proto",
        "//third_party:guava",
        "//third_party:hazelcast",
        "//third_party:jsr107",
        "//third_party:protobuf",
    ],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
)
