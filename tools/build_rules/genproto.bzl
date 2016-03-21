# Copyright 2014 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# This is a quick and dirty rule to make Bazel compile itself.  It
# only supports Java.

proto_filetype = FileType([".proto"])

def gensrcjar_impl(ctx):
  out = ctx.outputs.srcjar

  ctx.action(
    command=' '.join([
        "JAR='%s'" % ctx.executable._jar.path,
        "OUTPUT='%s'" % out.path,
        "PROTO_COMPILER='%s'" % ctx.executable._proto_compiler.path,
        "GRPC_JAVA_PLUGIN='%s'" % ctx.executable.grpc_java_plugin.path if \
	    ctx.executable.grpc_java_plugin else "",
        "SOURCE='%s'" % ctx.file.src.path,
        ctx.executable._gensrcjar.path,
    ]),
    inputs=([ctx.file.src] + ctx.files._gensrcjar + ctx.files._jar +
            ctx.files._jdk + ctx.files._proto_compiler),
    outputs=[out],
    mnemonic="GenProtoSrcJar",
    use_default_shell_env=True)

  return struct(runfiles=ctx.runfiles(collect_default=True))

gensrcjar = rule(
    gensrcjar_impl,
    attrs = {
        "src": attr.label(
            allow_files = proto_filetype,
            single_file = True,
        ),
        "grpc_java_plugin": attr.label(
            cfg = HOST_CFG,
            executable = True,
            single_file = True,
        ),
        "_gensrcjar": attr.label(
            default = Label("@bazel_tools//tools/build_rules:gensrcjar"),
            executable = True,
        ),
        # TODO(bazel-team): this should be a hidden attribute with a default
        # value, but Skylark needs to support select first.
        "_proto_compiler": attr.label(
            default = Label("@bazel_tools//third_party/protobuf:protoc"),
            allow_files = True,
            executable = True,
            single_file = True,
        ),
        "_jar": attr.label(
            default = Label("@bazel_tools//tools/jdk:jar"),
            allow_files = True,
            executable = True,
            single_file = True,
        ),
        # The jdk dependency is required to ensure dependent libraries are found
        # when we invoke jar (see issue #938).
        # TODO(bazel-team): Figure out why we need to pull this in explicitly;
        # the jar dependency above should just do the right thing on its own.
        "_jdk": attr.label(
            default = Label("@bazel_tools//tools/jdk:jdk"),
            allow_files = True,
        ),
    },
    outputs = {"srcjar": "lib%{name}.srcjar"},
)

# TODO(bazel-team): support proto => proto dependencies too
def java_proto_library(name, src, use_grpc_plugin=False):
  grpc_java_plugin = None
  if use_grpc_plugin:
    grpc_java_plugin = "//external:grpc-java-plugin"
  gensrcjar(name=name + "_srcjar", src=src, grpc_java_plugin=grpc_java_plugin)

  deps = ["@bazel_tools//third_party/protobuf"]
  if use_grpc_plugin:
    deps += ["//external:grpc-jar", "//external:guava"]
  native.java_library(
    name=name,
    srcs=[name + "_srcjar"],
    deps=deps,
    # The generated code has lots of 'rawtypes' warnings.
    javacopts=["-Xlint:-rawtypes"],
)

def proto_java_library(name, src):
  print("Deprecated: use java_proto_library() instead, proto_java_library " +
        "will be removed in version 0.2.1")
  java_proto_library(name, src)
