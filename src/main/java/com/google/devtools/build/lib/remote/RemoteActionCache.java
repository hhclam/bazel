package com.google.devtools.build.lib.remote;

import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.vfs.Path;

import java.io.IOException;
import java.util.Collection;

/**
 * A cache for storing artifacts (input and output) as well as the output of
 * running an action.
 */
@ThreadCompatible
public interface RemoteActionCache {
  /**
   * Put the file in cache if it is not already in it. No-op if the file is already stored in cache.
   * Returns the key for fetching the file from cache regardless if it exists in the cache or not.
   */
  public String putFileIfNotExist(Path file) throws IOException;

  /**
   * Write the file in cache identified by key to the file system. The key must uniquely identify
   * the content of the file. Throws CacheNotFoundException if the file is not found in cache.
   */
  public void writeFile(String key, Path dest, boolean executable) throws IOException, CacheNotFoundException;

  /**
   * Write the action output files identified by the key to the file system. The key must uniquely
   * identify the action and the content of action inputs.
   * Throws CacheNotFoundException if action output is not found in cache.
   */
  public void writeActionOutput(String key, Path execRoot)
          throws IOException, CacheNotFoundException;

  /**
   * Update the cache with the action outputs for the specified key.
   */
  public void putActionOutput(String key,
                              Collection<? extends ActionInput> outputs) throws IOException;

  /**
   * Update the cache with the files for the specified key.
   */
  public void putActionOutput(String key, Path execRoot, Collection<Path> files) throws IOException;
}
