package com.google.devtools.build.lib.remote;

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.remote.RemoteProtocol.RemoteWorkRequest;
b
import java.io.IOException;

/**
 * A remote work executor that is based on REST. A REST server that is compatible
 * with this class is implemented in src/tools/build_worker.
 */
@ThreadSafe
class RestWorkExecutor implements RemoteWorkExecutor {
  /**
   * A cache used to store the input and output files as well as the build status
   * of the remote work.
   */
  private final MemcacheActionCache cache;

  public RestWorkExecutor(MemcacheActionCache cache) {
    this.cache = cache;
  }

  @Override
  public ListenableFuture<Response> submit() throws IOException {
    return null;
  }
}
