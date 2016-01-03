package com.google.devtools.build.lib.remote;

import com.google.common.hash.HashCode;
import com.google.common.io.Files;

import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.remote.RemoteProtocol.CacheEntry;
import com.google.devtools.build.lib.remote.RemoteProtocol.FileEntry;
import com.google.protobuf.ByteString;

import java.io.IOException;
import java.util.Collection;

import javax.cache.Cache;

/**
 * A RemoteActionCache implementation that uses memcache as a distributed storage
 * for files and action output.
 *
 * The thread satefy is guranteed by the underlying memcache client.
 */
@ThreadSafe
public class MemcacheActionCache implements RemoteActionCache {
  private final Path execRoot;
  private final RemoteOptions options;
  private final Cache<String, byte[]> cache;

  /**
   * Construct an action cache using JCache API.
   */
  public MemcacheActionCache(Path execRoot, RemoteOptions options, Cache<String, byte[]> cache) {
    this.execRoot = execRoot;
    this.options = options;
    this.cache = cache;
  }

  @Override
  public void putFile(String key, Path file) throws IOException {
      // TODO(alpha): I should put the file content as chunks to avoid reading the entire
      // file into memory.
      cache.put(key,
                CacheEntry.newBuilder()
                    .setFileContent(ByteString.readFrom(file.getInputStream()))
                    .build().toByteArray());
  }

  @Override
  public void writeFile(String key, Path dest) throws IOException, CacheNotFoundException {
      byte[] data = cache.get(key);
      if (data == null) {
          throw new CacheNotFoundException("File content cannot be found with key: " + key);
      }
      CacheEntry.parseFrom(data)
          .getFileContent()
          .writeTo(dest.getOutputStream());
  }

  @Override
  public boolean containsFile(String key) {
      return cache.containsKey(key);
  }

  @Override
  public boolean writeActionOutput(String key, Path execRoot)
          throws IOException, CacheNotFoundException {
      byte[] data = cache.get(key);
      if (data == null)
          return false;
      CacheEntry cacheEntry = CacheEntry.parseFrom(data);
      for (FileEntry file : cacheEntry.getFilesList()) {
          writeFile(file.getContentKey(), execRoot.getRelative(file.getPath()));
      }
      return true;
  }

  @Override
  public void putActionOutput(String key,
                              Collection<? extends ActionInput> outputs) throws IOException {
      CacheEntry.Builder actionOutput = CacheEntry.newBuilder();
      for (ActionInput output : outputs) {
          // First put the file content to cache.
          Path file = execRoot.getRelative(output.getExecPathString());
          if (file.isSymbolicLink() || file.isDirectory())
              continue;
          String contentKey = HashCode.fromBytes(file.getMD5Digest()).toString();
          actionOutput.addFilesBuilder()
                  .setPath(output.getExecPathString())
                  .setContentKey(contentKey);
          if (containsFile(contentKey))
              continue;
          putFile(contentKey, file);
      }
      cache.put(key, actionOutput.build().toByteArray());
  }
}
