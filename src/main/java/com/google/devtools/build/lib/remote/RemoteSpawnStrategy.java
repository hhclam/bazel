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
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputFileCache;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ActionMetadata;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionStrategy;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnActionContext;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.standalone.StandaloneSpawnStrategy;
import com.google.devtools.build.lib.util.Preconditions;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;

/**
 * Strategy that uses a distributed cache for sharing action input and output files.
 * Optionally this strategy also support offload the work to a remote worker.
 */
@ExecutionStrategy(name = { "remote" }, contextType = SpawnActionContext.class)
public class RemoteSpawnStrategy implements SpawnActionContext {
  private final boolean verboseFailures;
  private final Path execRoot;
  private final StandaloneSpawnStrategy standaloneStrategy;
  private final RemoteOptions options;
  private final RemoteActionCache remoteActionCache;
  private final RemoteWorkExecutor remoteWorkExecutor;

  public RemoteSpawnStrategy(
      Map<String, String> clientEnv,
      Path execRoot,
      RemoteOptions options,
      boolean verboseFailures,
      RemoteActionCache actionCache,
      RemoteWorkExecutor workExecutor) {
    this.verboseFailures = verboseFailures;
    this.execRoot = execRoot;
    this.standaloneStrategy = new StandaloneSpawnStrategy(execRoot, verboseFailures);
    this.remoteActionCache = actionCache;
    this.remoteWorkExecutor = workExecutor;
    this.options = options;
  }

  /**
   * Executes the given {@code spawn}.
   */
  @Override
  public void exec(Spawn spawn,
                   ActionExecutionContext actionExecutionContext) throws ExecException {
    if (!spawn.isRemotable()) {
      standaloneStrategy.exec(spawn, actionExecutionContext);
      return;
    }

    final Executor executor = actionExecutionContext.getExecutor();
    final ActionMetadata actionMetadata = spawn.getResourceOwner();
    final ActionInputFileCache inputFileCache = actionExecutionContext.getActionInputFileCache();
    final EventHandler eventHandler = executor.getEventHandler();
    
    // Compute a hash code to uniquely identify the action plus the action inputs.
    Hasher hasher = Hashing.sha256().newHasher();

    // TODO(alpha): The action key is usually computed using the path to the tool and the arguments.
    // It does not take into account the content / versionof the system tool (e.g. gcc).
    // I should put the system configuration as part of the hash, e.g. OS, toolchain, etc.
    Preconditions.checkNotNull(actionMetadata.getKey());
    hasher.putString(actionMetadata.getKey(), Charset.defaultCharset());
    
    List<ActionInput> inputs =
        ActionInputHelper.expandMiddlemen(
            spawn.getInputFiles(), actionExecutionContext.getMiddlemanExpander());
    for (ActionInput input : inputs) {
      hasher.putString(input.getExecPathString(), Charset.defaultCharset());
      try {
        // TODO(alpha): The digest from ActionInputFileCache is used to detect local file
        // changes. It might not be sufficient to identify the input file globally in the
        // remote action cache. Consider upgrading this to a better hash algorithm with
        // less collision.
        hasher.putBytes(inputFileCache.getDigest(input).toByteArray());
      } catch (IOException e) {
        // TODO(alpha: Don't care now. I should find out how this happens.
      }
    }

    // Save the action output if found in the remote action cache.
    String actionOutputKey = hasher.hash().toString();

    // Timeout for running the remote spawn.
    int timeout = 120;
    String timeoutStr = spawn.getExecutionInfo().get("timeout");
    if (timeoutStr != null) {
      try {
        timeout = Integer.parseInt(timeoutStr);
      } catch (NumberFormatException e) {
        throw new UserExecException("could not parse timeout: ", e);
      }
    }

    try {
      if (writeActionOutput(spawn.getMnemonic(), actionOutputKey, eventHandler, true))
        return;

      FileOutErr outErr = actionExecutionContext.getFileOutErr();
      if (executeWorkRemotely(spawn.getMnemonic(),
                              actionOutputKey,
                              spawn.getArguments(),
                              inputs,
                              spawn.getEnvironment(),
                              spawn.getOutputFiles(),
                              timeout,
                              eventHandler,
                              outErr)) {
        return;
      }
        
      // If nothing works then run spawn locally.
      standaloneStrategy.exec(spawn, actionExecutionContext);
      if (remoteActionCache != null) {
        remoteActionCache.putActionOutput(actionOutputKey, spawn.getOutputFiles());
      }
    } catch (IOException e) {
      throw new UserExecException("Unexpected IO error.", e);
    }
  }

  /**
   * Submit work to execute remotly. Returns if all expected action outputs are found.
   */
  private boolean executeWorkRemotely(String mnemonic,
                                      String actionOutputKey,
                                      List<String> arguments,
                                      List<ActionInput> inputs,
                                      ImmutableMap<String, String> environment,
                                      Collection<? extends ActionInput> outputs,
                                      int timeout,
                                      EventHandler eventHandler,
                                      FileOutErr outErr)
      throws IOException {
    if (remoteWorkExecutor == null)
      return false;
    try {
      ListenableFuture<RemoteWorkExecutor.Response> future = remoteWorkExecutor.submit(
          execRoot,
          actionOutputKey,
          arguments,
          inputs,
          environment,
          outputs,
          timeout);
      RemoteWorkExecutor.Response response = future.get(timeout, TimeUnit.SECONDS);
      if (!response.success()) {
        String exception = "";
        if (!response.getException().isEmpty()) {
          exception = " (" + response.getException() + ")";
        }
        eventHandler.handle(Event.warn(
            mnemonic + " failed to execute work remotely"+ exception +", running locally"));
        return false;
      }
      if (response.getOut() != null)
        outErr.printOut(response.getOut());
      if (response.getErr() != null)
        outErr.printErr(response.getErr());
    } catch (ExecutionException e) {
      eventHandler.handle(
          Event.warn(mnemonic + " failed to execute work remotely (" + e + "), running locally"));
      return false;
    } catch (TimeoutException e) {
      eventHandler.handle(
          Event.warn(mnemonic + " timed out executing work remotely (" + e +
                     "), running locally"));
      return false;
    } catch (InterruptedException e) {
      eventHandler.handle(
          Event.warn(mnemonic + " remote work interrupted (" + e + ")"));
      return false;
    }      
    return writeActionOutput(mnemonic, actionOutputKey, eventHandler, false);
  }

  /**
   * Saves the action output from cache. Returns true if all action outputs are found.
   */
  private boolean writeActionOutput(String mnemonic, String actionOutputKey,
                                    EventHandler eventHandler, boolean ignoreCacheNotFound)
      throws IOException {
    if (remoteActionCache == null)
      return false;
    try {
      remoteActionCache.writeActionOutput(actionOutputKey, execRoot);
      Event.info(mnemonic + " reuse action outputs from cache");
      // TODO(alpha): Check if all the expected outputs are there.
      // If not run the standalone spawn.
      return true;
    } catch (CacheNotFoundException e) {
      if (!ignoreCacheNotFound) {
        eventHandler.handle(
            Event.warn(mnemonic + " some cache entries cannot be found (" + e + ")"));
      }
    }
    return false;
  }

  @Override
  public String strategyLocality(String mnemonic, boolean remotable) {
    return "remote";
  }

  @Override
  public boolean isRemotable(String mnemonic, boolean remotable) {
    // Returning true here just helps to estimate the cost of this computation is zero.
    return remotable;
  }
}
