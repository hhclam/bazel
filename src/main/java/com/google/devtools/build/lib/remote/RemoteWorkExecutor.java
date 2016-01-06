package com.google.devtools.build.lib.remote;

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.vfs.Path;

import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;

import java.io.IOException;
import java.util.Collection;

/**
 * Runs a work item remotely.
 */
@ThreadCompatible
public interface RemoteWorkExecutor {

  /**
   * The response of running a remote work.
   */
  class Response {
    private final boolean success;
    private final String out;
    private final String err;
    private final String exception;

    public boolean success() {
      return success;
    }

    public String getOut() {
      return out;
    }

    public String getErr() {
      return err;
    }

    Response(boolean success, String out, String err, String exception) {
      this.success = success;
      this.out = out;
      this.err = err;
      this.exception = exception;
    }
  }

  /**
   * Submit the work to this work executor.
   * The output of running this action should be written to {@link RemoteActionCache} indexed
   * by |actionOutputKey|.
   * 
   * Returns a future for the response of this work request.
   */
  public ListenableFuture<Response> submit(
      Path execRoot,
      String actionOutputKey,
      Collection<String> arguments,
      Collection<ActionInput> inputs,
      ImmutableMap<String, String> environment,
      Collection<? extends ActionInput> outputs,
      int timeout) throws IOException;
}
