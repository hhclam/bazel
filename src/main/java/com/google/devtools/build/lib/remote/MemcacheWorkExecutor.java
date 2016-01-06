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
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.remote.RemoteProtocol.FileEntry;
import com.google.devtools.build.lib.remote.RemoteProtocol.RemoteWorkRequest;
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

/**
 * Implementation of {@link RemoteWorkExecutor} that uses MemcacheActionCache and protobufs for
 * communicating the work, inputs and outputs.
 */
@ThreadSafe
public class MemcacheWorkExecutor implements RemoteWorkExecutor {
  protected final ListeningExecutorService executorService;

  /**
   * A cache used to store the input and output files as well as the build status
   * of the remote work.
   */
  protected final MemcacheActionCache cache;

  /**
   * Execution root for running this work locally.
   */
  private final Path execRoot;

  public MemcacheWorkExecutor(MemcacheActionCache cache, ListeningExecutorService executorService,
                              Path execRoot) {
    this.cache = cache;
    this.executorService = executorService;
    this.execRoot = execRoot;
  }

  @Override
  public ListenableFuture<Response> submit(
      Path execRoot,
      String actionOutputKey,
      Collection<String> arguments,
      Collection<ActionInput> inputs,
      ImmutableMap<String, String> environment,
      Collection<? extends ActionInput> outputs,
      int timeout) throws IOException {
    RemoteWorkRequest.Builder work = RemoteWorkRequest.newBuilder();
    work.setOutputKey(actionOutputKey);

    // Save all input files to cache.
    for (ActionInput input : inputs) {
      Path file = execRoot.getRelative(input.getExecPathString());
           
      if (file.isDirectory()) {
        // TODO(alpha): Need to handle these cases.
        continue;
      }
 
      String contentKey = cache.putFileIfNotExist(file);
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
    work.setTimeout(timeout);
    return submit(work.build());
  }

  /**
   * Submit a work in the form of protobuf. This method executes the work locally.
   */
  public ListenableFuture<Response> submit(RemoteWorkRequest work) throws IOException {
    return executorService.submit(new Callable<Response>(){
        public Response call() throws IOException {
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
            return new Response(result.getTerminationStatus().success(),
                                stdout.toString(),
                                stderr.toString(),
                                "");
          } catch (CommandException e) {
            return new Response(false, stdout.toString(), stderr.toString(), e.toString());
          }
        }
      });
  }
}
