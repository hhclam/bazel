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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.devtools.build.lib.actions.ActionContextProvider;
import com.google.devtools.build.lib.actions.Executor.ActionContext;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.rules.test.StandaloneTestStrategy;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.CommandEnvironment;

import java.io.IOException;

/**
 * Provide a remote execution context.
 */
public class RemoteActionContextProvider extends ActionContextProvider {
  private final CommandEnvironment env;
  private final ImmutableList<ActionContext> strategies;

  public RemoteActionContextProvider(CommandEnvironment env, BuildRequest buildRequest,
                                     RemoteActionCache actionCache, RemoteWorkExecutor workExecutor) {
    this.env = env;
    BlazeRuntime runtime = env.getRuntime();
    boolean verboseFailures = buildRequest.getOptions(ExecutionOptions.class).verboseFailures;
    Builder<ActionContext> strategiesBuilder = ImmutableList.builder();
    strategiesBuilder.add(
        new RemoteSpawnStrategy(env.getClientEnv(), runtime.getExecRoot(),
                                buildRequest.getOptions(RemoteOptions.class), verboseFailures,
                                actionCache, workExecutor));
    this.strategies = strategiesBuilder.build();
  }

  @Override
  public Iterable<ActionContext> getActionContexts() {
    return strategies;
  }
}
