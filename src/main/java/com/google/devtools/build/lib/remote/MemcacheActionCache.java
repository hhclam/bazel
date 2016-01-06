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
import java.util.ArrayList;
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
  public String putFileIfNotExist(Path file) throws IOException {
      String contentKey = HashCode.fromBytes(file.getMD5Digest()).toString();
      if (containsFile(contentKey))
          return contentKey;
      putFile(contentKey, file);
      return contentKey;
  }

  private void putFile(String key, Path file) throws IOException {
      // TODO(alpha): I should put the file content as chunks to avoid reading the entire
      // file into memory.
      cache.put(key,
                CacheEntry.newBuilder()
                    .setFileContent(ByteString.readFrom(file.getInputStream()))
                    .build().toByteArray());
  }

  @Override
  public void writeFile(String key, Path dest, boolean executable) throws IOException, CacheNotFoundException {
      byte[] data = cache.get(key);
      if (data == null) {
          throw new CacheNotFoundException("File content cannot be found with key: " + key);
      }
      CacheEntry.parseFrom(data)
          .getFileContent()
          .writeTo(dest.getOutputStream());
      dest.setExecutable(executable);
  }

  private boolean containsFile(String key) {
      return cache.containsKey(key);
  }

  @Override
  public void writeActionOutput(String key, Path execRoot) throws IOException,
          CacheNotFoundException {
      byte[] data = cache.get(key);
      if (data == null)
          throw new CacheNotFoundException("Action output cannot be found with key: " + key);
      CacheEntry cacheEntry = CacheEntry.parseFrom(data);
      for (FileEntry file : cacheEntry.getFilesList()) {
          writeFile(file.getContentKey(), execRoot.getRelative(file.getPath()),
                    file.getExecutable());
      }
  }

  @Override
  public void putActionOutput(String key,
                              Collection<? extends ActionInput> outputs) throws IOException {
      CacheEntry.Builder actionOutput = CacheEntry.newBuilder();
      for (ActionInput output : outputs) {
          Path file = execRoot.getRelative(output.getExecPathString());
          addToActionOutput(file, output.getExecPathString(), actionOutput);
      }
      cache.put(key, actionOutput.build().toByteArray());
  }

  @Override
  public void putActionOutput(String key,
                              Path execRoot,
                              Collection<Path> files) throws IOException {
      CacheEntry.Builder actionOutput = CacheEntry.newBuilder();
      for (Path file : files) {
         addToActionOutput(file, file.relativeTo(execRoot).getPathString(), actionOutput);
      }
      cache.put(key, actionOutput.build().toByteArray());
  }

  /**
   * Add the file to action output cache entry. Put the file to cache if necessary.
   */
  private void addToActionOutput(Path file, String execPathString, CacheEntry.Builder actionOutput)
          throws IOException {
      if (file.isSymbolicLink() || file.isDirectory()) {
          // TODO(alpha): Need to handle these cases.
          return;
      }
      // First put the file content to cache.
      String contentKey = putFileIfNotExist(file);
      // Add to protobuf.
      actionOutput.addFilesBuilder()
              .setPath(execPathString)
              .setContentKey(contentKey)
              .setExecutable(file.isExecutable());
  }
}
