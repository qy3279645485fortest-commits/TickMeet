package io.tickmeet.files;

import io.minio.*;
import io.minio.errors.ErrorResponseException;
import java.io.*;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "tickmeet.storage-provider", havingValue = "s3")
public class S3Storage implements StorageService {
  private final MinioClient client;
  private final String bucket;

  public S3Storage(
      @Value("${tickmeet.s3.endpoint}") String endpoint,
      @Value("${tickmeet.s3.access-key}") String access,
      @Value("${tickmeet.s3.secret-key}") String secret,
      @Value("${tickmeet.s3.bucket}") String bucket,
      @Value("${tickmeet.s3.region:us-east-1}") String region) {
    client =
        MinioClient.builder().endpoint(endpoint).credentials(access, secret).region(region).build();
    this.bucket = bucket;
  }

  public String provider() {
    return "s3";
  }

  public String namespace() {
    return bucket;
  }

  private IOException failure(Exception e) {
    return new IOException("Object storage operation failed", e);
  }

  public void put(String key, byte[] bytes, String type) throws IOException {
    try {
      client.putObject(
          PutObjectArgs.builder().bucket(bucket).object(key).contentType(type).stream(
                  new ByteArrayInputStream(bytes), bytes.length, -1)
              .build());
    } catch (Exception e) {
      throw failure(e);
    }
  }

  public byte[] get(String key) throws IOException {
    try (InputStream in =
            client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      return out.toByteArray();
    } catch (Exception e) {
      throw failure(e);
    }
  }

  public void delete(String key) throws IOException {
    try {
      client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    } catch (Exception e) {
      throw failure(e);
    }
  }

  public boolean exists(String key) throws IOException {
    try {
      client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
      return true;
    } catch (ErrorResponseException e) {
      if ("NoSuchKey".equals(e.errorResponse().code())) return false;
      throw failure(e);
    } catch (Exception e) {
      throw failure(e);
    }
  }

  public Path localPath(String key) {
    throw new UnsupportedOperationException("S3 storage has no local filesystem path");
  }
}
