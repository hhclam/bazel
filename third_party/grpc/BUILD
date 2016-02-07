package(default_visibility = ["//visibility:public"])

licenses(["notice"])

java_import(
    name = "grpc-jar",
    jars = [
        "grpc-all-0.13.2.jar",
    ],
)

filegroup(
    name = "grpc-java-plugin",
    srcs = select({
        "//third_party:windows_mingw": ["protoc-gen-grpc-java-0.13.2-windows-x86_32.exe"],
        "//third_party:windows_msys64_mingw64": ["protoc-gen-grpc-java-0.13.2-windows-x86_64.exe"],
        "//third_party:windows_msys64": ["protoc-gen-grpc-java-0.13.2-windows-x86_64.exe"],
        "//third_party:windows_clang": ["protoc-gen-grpc-java-0.13.2-windows-x86_64.exe"],
        "//third_party:darwin": ["protoc-gen-grpc-java-0.13.2-osx-x86_64.exe"],
        "//third_party:k8": ["protoc-gen-grpc-java-0.13.2-linux-x86_64.exe"],
        "//third_party:piii": ["protoc-gen-grpc-java-0.13.2-linux-x86_32.exe"],
        "//third_party:arm": ["protoc-gen-grpc-java-0.13.2-linux-x86_32.exe"],
        "//third_party:freebsd": ["protoc-gen-grpc-java-0.13.2-linux-x86_32.exe"],
    }),
)
