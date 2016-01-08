package com.google.devtools.build.remote;

import com.google.common.io.CharStreams;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.remote.RemoteOptions;
import com.google.devtools.build.lib.remote.RemoteWorkExecutor;
import com.google.devtools.build.lib.remote.MemcacheActionCache;
import com.google.devtools.build.lib.remote.MemcacheWorkExecutor;
import com.google.devtools.build.lib.remote.RemoteProtocol.RemoteWorkRequest;
import com.google.devtools.build.lib.remote.RemoteProtocol.RemoteWorkResponse;
import com.google.devtools.build.lib.util.CommandFailureUtils;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import javax.cache.Cache;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class BuildRequestServlet extends HttpServlet {
    private final Path workPath;
    private final RemoteOptions remoteOptions;
    private final BuildWorkerOptions options;
    private final Cache<String, byte[]> cache;
    private final ListeningExecutorService executorService;

    public BuildRequestServlet(Path workPath, RemoteOptions remoteOptions,
                               BuildWorkerOptions options, Cache<String, byte[]> cache) {
        this.workPath = workPath;
        this.remoteOptions = remoteOptions;
        this.options = options;
        this.cache = cache;
        this.executorService = MoreExecutors.newDirectExecutorService();
    }

    @Override
    protected void doPost(HttpServletRequest request,
                          HttpServletResponse response) throws ServletException, IOException {
      response.setContentType("application/json");
      RemoteWorkResponse.Builder workResponse = RemoteWorkResponse.newBuilder();
      // TODO(alpha): Choose a better temp directory name.
      Path tempRoot = workPath.getRelative("build-" + UUID.randomUUID().toString());
      FileSystemUtils.createDirectoryAndParents(tempRoot);
      boolean deleteTree = true;
      try {
        String workJson = CharStreams.toString(request.getReader());
        RemoteWorkRequest.Builder workRequest = RemoteWorkRequest.newBuilder();
        JsonFormat.parser().merge(workJson, workRequest);
        RemoteWorkRequest work = workRequest.build();
        final MemcacheActionCache actionCache = new MemcacheActionCache(
            tempRoot,
            remoteOptions,
            cache);
        final MemcacheWorkExecutor workExecutor = new MemcacheWorkExecutor(
            actionCache,
            executorService,
            tempRoot);
        if (options.debug) {
          System.out.println("[INFO] Work received has "+ work.getInputFilesCount() +
                             " inputs files and " + work.getOutputFilesCount() + " output files.");
        }
        RemoteWorkExecutor.Response executorResponse = workExecutor.submit(work).get();
        if (options.debug) {
          if (!executorResponse.success()) {
            deleteTree = false;
            System.out.println("[WARNING] Work failed.");
            System.out.println(workJson);
          } else {
            System.out.println("[INFO] Work completed.");
          }
        }
        workResponse.setSuccess(executorResponse.success())
                .setOut(executorResponse.getOut())
                .setErr(executorResponse.getErr());
      } catch (Exception e) {
          workResponse.setSuccess(false)
                .setOut("")
                .setErr("")
                .setException(e.toString());
      } finally {
        if (deleteTree) {
          FileSystemUtils.deleteTree(tempRoot);
        } else {
          System.out.println("[WARNING] Preserving work directory " + tempRoot.toString() + ".");
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.getWriter().print(JsonFormat.printer().print(workResponse.build()));
      }
    }
}
