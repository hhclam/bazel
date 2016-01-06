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
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.actions.ActionContextProvider;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.buildtool.buildevent.BuildStartingEvent;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.common.options.OptionsBase;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * RemoteModule provides distributed cache and remote execution for Bazel.
 */
public class RemoteModule extends BlazeModule {
  private CommandEnvironment env;
  private BuildRequest buildRequest;
  private RemoteActionCache actionCache = null;
  private RemoteWorkExecutor workExecutor = null;
  private final ListeningExecutorService executorService;

  public RemoteModule() {
    // Create a pool of daemon threads for the work executor.
    executorService = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(5,
      new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
          Thread thread = Executors.defaultThreadFactory().newThread(runnable);
          thread.setDaemon(true);
          return thread;
        }
      }));
  }

  @Override
  public Iterable<ActionContextProvider> getActionContextProviders() {
    if (actionCache != null) {
        return ImmutableList.<ActionContextProvider>of(
            new RemoteActionContextProvider(env, buildRequest, actionCache, workExecutor));
    }
    return ImmutableList.<ActionContextProvider>of();
  }

  @Override
  public void beforeCommand(Command command, CommandEnvironment env) {
    this.env = env;
    env.getEventBus().register(this);
  }

  @Override
  public void afterCommand() {
    this.env = null;
    this.buildRequest = null;
  }

  @Subscribe
  public void buildStarting(BuildStartingEvent event) {
    buildRequest = event.getRequest();
    RemoteOptions options = buildRequest.getOptions(RemoteOptions.class);

    // Don't provide the remote spawn unless at least action cache is initialized.
    if (actionCache == null && (
            options.memcacheProvider != null &&
            (options.hazelcastConfiguration != null || options.hazelcastNode != null))) {
      MemcacheActionCache cache = new MemcacheActionCache(
          this.env.getRuntime().getExecRoot(), options,
          new HazelcastCacheFactory().create(options));
      actionCache = cache;
      if (workExecutor == null && options.restWorkerUrl != null) {
          try {
              workExecutor = new RestWorkExecutor(
                  cache, executorService, new URL(options.restWorkerUrl));
          } catch (MalformedURLException e) {
              env.getReporter().handle(
                  Event.warn("Not a valid value for --rest_worker_url. Work will run locally."));
          }
      }
    }
  }

  @Override
  public Iterable<Class<? extends OptionsBase>> getCommandOptions(Command command) {
    return command.builds()
        ? ImmutableList.<Class<? extends OptionsBase>>of(RemoteOptions.class)
        : ImmutableList.<Class<? extends OptionsBase>>of();
  }
}
