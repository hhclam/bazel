package com.google.devtools.build.lib.remote;

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;

/**
 * Runs a work item remotely.
 */
@ThreadCompatible
public interface RemoteWorkExecutor {
  /**
   * Submit the work to this work executor.
   */
  public String putFileIfNotExist(Path file) throws IOException;
}
