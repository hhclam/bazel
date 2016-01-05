package com.google.devtools.build.remote;

import com.google.devtools.build.lib.remote.RemoteActionCache;
import com.google.devtools.build.lib.vfs.Path;

import java.io.IOException;
import javax.cache.Cache;

import javax.cache.Cache;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class BuildRequestServlet extends HttpServlet {
    private final Path workPath;
    private final BuildWorkerOptions options;
    private final Cache<String, byte[]> cache;

    public BuildRequestServlet(Path workPath, BuildWorkerOptions options,
                               Cache<String, byte[]> cache) {
        this.workPath = workPath;
        this.options = options;
        this.cache = cache;
    }

    @Override
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("text/html");
        response.setStatus(HttpServletResponse.SC_OK);
        response.getWriter().println("<h1> SimpleServlet</h1>");
        System.out.println("## got work.");
    }
}
