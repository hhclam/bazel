// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.remote;

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputFileCache;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.remote.RemoteProtocol.FileEntry;
import com.google.devtools.build.lib.remote.RemoteProtocol.RemoteWorkRequest;
import com.google.devtools.build.lib.remote.RemoteProtocol.RemoteWorkResponse;
import com.google.devtools.build.lib.remote.RemoteWorkGrpc.RemoteWorkFutureStub;
import com.google.devtools.build.lib.shell.Command;
import com.google.devtools.build.lib.shell.CommandException;
import com.google.devtools.build.lib.shell.CommandResult;
import com.google.devtools.build.lib.shell.TerminationStatus;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;

/**
 * Implementation of {@link RemoteWorkExecutor} that uses MemcacheActionCache and gRPC for
 * communicating the work, inputs and outputs.
 */
@ThreadSafe
public class MemcacheWorkExecutor implements RemoteWorkExecutor {
  /**
   * A cache used to store the input and output files as well as the build status
   * of the remote work.
   */
  protected final MemcacheActionCache cache;

  /**
   * Execution root for running this work locally.
   */
  private final Path execRoot;

  /**
   * Information needed to run work remotely.
   */
  private final String host;
  private final int port;

  private static final int MAX_WORK_SIZE_BYTES = 1024 * 1024 * 512;

  public MemcacheWorkExecutor(MemcacheActionCache cache, String host, int port) {
    this.cache = cache;
    this.execRoot = null;
    this.host = host;
    this.port = port;
  }

  public MemcacheWorkExecutor(MemcacheActionCache cache, Path execRoot) {
    this.cache = cache;
    this.execRoot = execRoot;
    this.host = null;
    this.port = 0;
  }

  @Override
  public ListenableFuture<RemoteWorkResponse> executeRemotely(
      Path execRoot,
      ActionInputFileCache actionCache,
      String actionOutputKey,
      Collection<String> arguments,
      Collection<ActionInput> inputs,
      ImmutableMap<String, String> environment,
      Collection<? extends ActionInput> outputs,
      int timeout) throws IOException {
    RemoteWorkRequest.Builder work = RemoteWorkRequest.newBuilder();
    work.setOutputKey(actionOutputKey);

    long workSize = 0;
    for (ActionInput input : inputs) {
      Path file = execRoot.getRelative(input.getExecPathString());
      if (file.isDirectory()) {
        continue;
      }
      workSize += file.getFileSize();
    }

    if (workSize > MAX_WORK_SIZE_BYTES) {
      throw new WorkTooLargeException("Work is too large: " + workSize + " bytes.");
    }

    // Save all input files to cache.
    for (ActionInput input : inputs) {
      Path file = execRoot.getRelative(input.getExecPathString());
           
      if (file.isDirectory()) {
        // TODO(alpha): Need to handle these cases. 
        continue;
      }
 
      String contentKey = cache.putFileIfNotExist(actionCache, input);
      work.addInputFilesBuilder()
          .setPath(input.getExecPathString())
          .setContentKey(contentKey)
          .setExecutable(file.isExecutable());
    }

    work.addAllArguments(arguments);
    work.getMutableEnvironment().putAll(environment);
    for (ActionInput output : outputs) {
      work.addOutputFilesBuilder()
          .setPath(output.getExecPathString());
    }

    ManagedChannel channel = NettyChannelBuilder.forAddress(host, port).usePlaintext(true).build();
    RemoteWorkFutureStub stub = RemoteWorkGrpc.newFutureStub(channel);
    work.setTimeout(timeout);
    return stub.executeSynchronously(work.build());
  }

  /**
   * Execute a work item locally.
   */
  public RemoteWorkResponse executeLocally(RemoteWorkRequest work) throws IOException {
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    try {
      // Prepare directories and input files.
      for (FileEntry input : work.getInputFilesList()) {
	Path file = execRoot.getRelative(input.getPath());
	FileSystemUtils.createDirectoryAndParents(file.getParentDirectory());
	cache.writeFile(input.getContentKey(), file, input.getExecutable());
      }

      // Prepare directories for output files.
      List<Path> outputs = new ArrayList<>();
      for (FileEntry output : work.getOutputFilesList()) {
	Path file = execRoot.getRelative(output.getPath());
	outputs.add(file);
	FileSystemUtils.createDirectoryAndParents(file.getParentDirectory());
      }
              
      Command cmd = new Command(work.getArgumentsList().toArray(new String[]{}),
				work.getEnvironment(),
				new File(execRoot.getPathString()));
      CommandResult result = cmd.execute(Command.NO_INPUT,
					 Command.NO_OBSERVER,
					 stdout,
					 stderr,
					 true);
      cache.putActionOutput(work.getOutputKey(), execRoot, outputs);
      return RemoteWorkResponse.newBuilder()
	.setSuccess(result.getTerminationStatus().success())
	.setOut(stdout.toString())
	.setErr(stderr.toString())
	.setException("")
	.build();
    } catch (CommandException e) {
      return RemoteWorkResponse.newBuilder()
	.setSuccess(false)
	.setOut(stdout.toString())
	.setErr(stderr.toString())
	.setException(e.toString())
	.build();
    }
  }
}
