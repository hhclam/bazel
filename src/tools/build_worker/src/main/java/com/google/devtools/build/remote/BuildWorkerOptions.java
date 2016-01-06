package com.google.devtools.build.remote;

import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionsBase;

/**
 * Options for build worker.
 */
public class BuildWorkerOptions extends OptionsBase {
  @Option(
    name = "listen_port",
    defaultValue = "8080",
    category = "build_worker",
    help = "Listening port for the getty server."
  )
  public int listenPort;
 
  @Option(
    name = "work_path",
    defaultValue = "null",
    category = "build_worker",
    help = "A directory for the build worker to do work."
  )
  public String workPath;
 
  @Option(
    name = "debug",
    defaultValue = "false",
    category = "build_worker",
    help = "Turn this one for debugging remote job failure. There will be extra messages and the " +
           "work directory will be preserved in the case of failure."
  )
  public boolean debug;
}
