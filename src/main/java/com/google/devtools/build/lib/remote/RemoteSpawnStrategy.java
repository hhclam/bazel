// Copyright 2014 The Bazel Authors. All rights reserved.
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
import com.google.common.collect.Iterables;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputFileCache;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ActionMetadata;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionStrategy;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnActionContext;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.analysis.AnalysisUtils;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.rules.apple.AppleConfiguration;
import com.google.devtools.build.lib.rules.apple.AppleHostInfo;
import com.google.devtools.build.lib.rules.fileset.FilesetActionContext;
import com.google.devtools.build.lib.shell.AbnormalTerminationException;
import com.google.devtools.build.lib.shell.Command;
import com.google.devtools.build.lib.shell.CommandException;
import com.google.devtools.build.lib.shell.TerminationStatus;
import com.google.devtools.build.lib.standalone.StandaloneSpawnStrategy;
import com.google.devtools.build.lib.util.CommandFailureUtils;
import com.google.devtools.build.lib.util.Preconditions;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.OsUtils;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.SearchPath;
import com.google.protobuf.ByteString;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.UUID;

/**
 * Strategy that uses subprocessing to execute a process.
 */
@ExecutionStrategy(name = { "remote" }, contextType = SpawnActionContext.class)
public class RemoteSpawnStrategy implements SpawnActionContext {
  private final boolean verboseFailures;
  private final Path processWrapper;
  private final Path execRoot;
  private final BlazeDirectories blazeDirs;
  private final StandaloneSpawnStrategy standaloneStrategy;
  private final RemoteActionCache remoteActionCache;
  private final RemoteOptions options;

  public RemoteSpawnStrategy(
        Map<String, String> clientEnv,
        BlazeDirectories blazeDirs,
        Path execRoot,
        RemoteOptions options,
        boolean verboseFailures) {
      System.out.println("Creating remote spawn strategy.");
    this.verboseFailures = verboseFailures;
    this.execRoot = execRoot;
    this.processWrapper = execRoot.getRelative(
        "_bin/process-wrapper" + OsUtils.executableExtension());
    this.blazeDirs = blazeDirs;
    this.standaloneStrategy = new StandaloneSpawnStrategy(blazeDirs.getExecRoot(), verboseFailures);
    this.remoteActionCache = new MemcacheActionCache(execRoot, options, new HazelcastCacheFactory().create(options));
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
            hasher.putBytes(inputFileCache.getDigest(input).toByteArray());
        } catch (IOException e) {
            // TODO(alpha: Don't care now. I should find out how this happens.
        }
    }

    // Save the action output if found in the remote action cache.
    String actionInputHash = hasher.hash().toString();
    try {
        if (remoteActionCache.writeActionOutput(actionInputHash, execRoot)) {
            Event.info(spawn.getMnemonic() + " reuse action outputs from cache");
            return;
        }
    } catch (IOException e) {
        throw new UserExecException("Failed to write action output.", e);
    } catch (CacheNotFoundException e) {
        eventHandler.handle(
            Event.warn(
                spawn.getMnemonic()
                + " some cache entries cannot be found ("
                + e
                + ")"));
    }

    // If the user requested to force update the remote action cache then execute the action
    // locally.
    standaloneStrategy.exec(spawn, actionExecutionContext);
    try {
      remoteActionCache.putActionOutput(actionInputHash, spawn.getOutputFiles());
    } catch (IOException e) {
      eventHandler.handle(
          Event.warn(
              spawn.getMnemonic()
              + " failed to save output to remote action cache ("
              + e
              + ")"));
    }
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
