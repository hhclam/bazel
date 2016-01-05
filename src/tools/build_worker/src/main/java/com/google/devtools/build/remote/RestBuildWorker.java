package com.google.devtools.build.remote;

import com.google.devtools.build.lib.remote.HazelcastCacheFactory;
import com.google.devtools.build.lib.remote.RemoteOptions;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.JavaIoFileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.UnixFileSystem;
import com.google.devtools.common.options.OptionsParser;

import java.util.Collections;
import javax.cache.Cache;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

/**
 * A build worker that runs a build action. It accepts work and communicates using HTTP.
 */
public class RestBuildWorker
{
    public static void main(String[] args) throws Exception
    {
        OptionsParser parser = OptionsParser.newOptionsParser(RemoteOptions.class,
                                                              BuildWorkerOptions.class);
        parser.parseAndExitUponError(args);
        RemoteOptions remoteOptions = parser.getOptions(RemoteOptions.class);
        BuildWorkerOptions buildWorkerOptions = parser.getOptions(BuildWorkerOptions.class);

        if (remoteOptions.memcacheProvider == null ||
            remoteOptions.hazelcastConfiguration == null ||
            buildWorkerOptions.workPath == null) {
            printUsage(parser);
            return;
        }

        Path workPath = getFileSystem().getPath(buildWorkerOptions.workPath);
        Cache<String, byte[]> cache = new HazelcastCacheFactory().create(remoteOptions);

        // Initialize the jetty server.
        ServletContextHandler context = new ServletContextHandler(
            ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");
        Server server = new Server(8080);
        server.setHandler(context);
        context.addServlet(new ServletHolder(new BuildRequestServlet(
            workPath,
            buildWorkerOptions,
            cache)), "/build-request");

        server.start();
        server.join();
    }

    public static void printUsage(OptionsParser parser) {
        System.out.println("Usage: build_worker \n\n"
                           + "Starts a build worker that runs as a HTTP server and memcache node.");
        System.out.println(parser.describeOptions(Collections.<String, String>emptyMap(),
                                                  OptionsParser.HelpVerbosity.LONG));
    }
    
    static FileSystem getFileSystem() {
        return OS.getCurrent() == OS.WINDOWS
        ? new JavaIoFileSystem() : new UnixFileSystem();
    }
}
