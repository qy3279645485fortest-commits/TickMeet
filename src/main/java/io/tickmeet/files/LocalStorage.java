package io.tickmeet.files;

import java.io.IOException;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "tickmeet.storage-provider",
    havingValue = "local",
    matchIfMissing = true)
public class LocalStorage implements StorageService {
  private final Path root;

  public LocalStorage(@Value("${tickmeet.storage-root:.data/uploads}") String directory)
      throws IOException {
    root = Paths.get(directory).toAbsolutePath().normalize();
    Files.createDirectories(root);
  }

  public Path localPath(String key) {
    Path p = root.resolve(key).normalize();
    if (!p.startsWith(root) || p.equals(root))
      throw new IllegalArgumentException("Invalid storage key");
    return p;
  }

  public void put(String key, byte[] bytes, String contentType) throws IOException {
    Path temp = Files.createTempFile(root, "upload-", ".tmp");
    try {
      Files.write(temp, bytes);
      try {
        Files.move(
            temp,
            localPath(key),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, localPath(key), StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  public byte[] get(String key) throws IOException {
    return Files.readAllBytes(localPath(key));
  }

  public void delete(String key) throws IOException {
    Files.deleteIfExists(localPath(key));
  }

  public boolean exists(String key) {
    return Files.exists(localPath(key));
  }
}
