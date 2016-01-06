java_binary(
    name = "rest_build_worker",
    data = ["//src:libunix"],
    # TODO(philips): This is very fragile. Is there anything better I can do?
    jvm_flags = ["-Djava.library.path=$(BINDIR)/src/main/native"],
    main_class = "com.google.devtools.build.remote.RestBuildWorker",
    visibility = ["//visibility:public"],
    runtime_deps = ["//src/tools/build_worker/src/main/java/com/google/devtools/build/remote"],
)
