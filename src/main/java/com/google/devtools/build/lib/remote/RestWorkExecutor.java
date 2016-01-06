package com.google.devtools.build.lib.remote;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.remote.RemoteProtocol.RemoteWorkRequest;
import com.google.devtools.build.lib.remote.RemoteProtocol.RemoteWorkResponse;
import com.google.devtools.build.lib.vfs.Path;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;

import java.io.IOException;
import java.net.URL;
import java.net.URISyntaxException;
import java.util.List;
import java.util.concurrent.Callable;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.DefaultHttpClient;

/**
 * A remote work executor that is based on REST. A REST server that is compatible
 * with this class is implemented in src/tools/build_worker.
 */
@ThreadSafe
class RestWorkExecutor extends MemcacheWorkExecutor {
  /**
   * This is a thread safe http client although performance might not be ideal.
   * TODO(alpha): Consider having a dedicated thread for this http communication.
   */
  private final URL workerUrl;

  public RestWorkExecutor(MemcacheActionCache cache,
                          ListeningExecutorService executorService,
                          URL restWorkerUrl) {
    // |execRoot| is only used for running the work locally which will not happen for class.
    super(cache, executorService, null);
    this.workerUrl = restWorkerUrl;
  }

  @Override
  public ListenableFuture<Response> submit(RemoteWorkRequest work) throws IOException {
    return executorService.submit(new Callable<Response>(){
      public Response call() throws URISyntaxException,
              InvalidProtocolBufferException, IOException {
        // It is probably not ideal having one http client per Callable.
        // TODO(alpha): Should have a connection pool to get better performance.
        final HttpClient httpClient = new DefaultHttpClient();
        HttpPost post = new HttpPost(workerUrl.toURI());
        StringEntity workJson = new StringEntity(JsonFormat.printer().print(work), "UTF-8");
        post.addHeader("content-type", "application/json");
        post.setEntity(workJson);
        HttpResponse response = httpClient.execute(post);

        // The REST call is blocking and returns when the work is done.
        // TODO(alpha): Change this to an async call by listening to the completion event.
        String responseJson = new BasicResponseHandler().handleResponse(response);
        RemoteWorkResponse.Builder workResponse = RemoteWorkResponse.newBuilder();
        JsonFormat.parser().merge(responseJson, workResponse);
        return new Response(workResponse.getSuccess(), workResponse.getOut(),
                            workResponse.getErr(), workResponse.getException());
      }
    });
  }
}
