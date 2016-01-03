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
   * Updates the cache entry for the specified key.
   */
  public void putFile(String key, Path file) throws IOException;

  /**
   * Save the file indexed by specified key to the path.
   */
  public void writeFile(String key, Path dest) throws IOException, CacheNotFoundException;

  /**
   * Returns true if the cache contains the file indexed by specified key.
   */
  public boolean containsFile(String key);

  /**
   * Save the action output files identified by the key. The key must uniquely
   * identify the action and the content of action inputs.
   * Returns true if the files are written to the execution root in the file system.
   * Returns false if the action output does not exist in the cache.
   */
  public boolean writeActionOutput(String key, Path execRoot)
          throws IOException, CacheNotFoundException;

  /**
   * Update the cache with the action outputs for the specified key.
   * |inputFileCache| is used to obtain the digest of the action output files.
   */
    public void putActionOutput(String key,
                                Collection<? extends ActionInput> outputs) throws IOException;
}
