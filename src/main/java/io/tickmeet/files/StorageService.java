package io.tickmeet.files;

import java.io.IOException;
import java.nio.file.Path;

/** Storage coordinates stay server-side. Public clients receive file IDs only. */
public interface StorageService {
  default String provider() {
    return "local";
  }

  default String namespace() {
    return "uploads";
  }

  void put(String key, byte[] bytes, String contentType) throws IOException;

  byte[] get(String key) throws IOException;

  void delete(String key) throws IOException;

  boolean exists(String key) throws IOException;

  Path localPath(String key);
}
