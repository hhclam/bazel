java_binary(
    name = "remote_worker",
    data = ["//src:libunix"],
    jvm_flags = ["-Djava.library.path=$(GENDIR)/src/main/native"],
    main_class = "com.google.devtools.build.remote.RemoteWorker",
    visibility = ["//visibility:public"],
    runtime_deps = ["//src/tools/remote_worker/src/main/java/com/google/devtools/build/remote"],
)
