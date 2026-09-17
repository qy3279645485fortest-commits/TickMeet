package io.tickmeet.files;

import io.tickmeet.auth.*;
import io.tickmeet.common.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.*;
import javax.imageio.stream.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileService {
  private final StorageService storage;
  private final Db db;
  private final EphemeralStore store;
  private final TransactionTemplate tx;

  public FileService(
      Db d, EphemeralStore st, PlatformTransactionManager tm, StorageService storage) {
    db = d;
    store = st;
    tx = new TransactionTemplate(tm);
    this.storage = storage;
  }

  public Map<String, Object> upload(
      String user, boolean admin, String purpose, MultipartFile file) {
    if (!Arrays.asList("POST", "EVENT", "VENUE").contains(purpose))
      throw new Problem(400, "INVALID_ARGUMENT", "图片用途无效");
    if (!"POST".equals(purpose) && !admin) throw new Problem(403, "FORBIDDEN", "仅运营可上传活动图片");
    if (!store.allow("upload:" + user, 10, 60)) throw new Problem(429, "RATE_LIMITED", "上传过于频繁");
    if (file.isEmpty() || file.getSize() > 5 * 1024 * 1024)
      throw new Problem(413, "INVALID_ARGUMENT", "图片不能为空或超过5MiB");
    String id = Db.id(), key = null;
    try {
      byte[] bytes = file.getBytes();
      String format =
          bytes.length > 8 && (bytes[0] & 255) == 137 && (bytes[1] & 255) == 80
              ? "png"
              : bytes.length > 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216
                  ? "jpg"
                  : null;
      if (format == null) throw new Problem(400, "INVALID_ARGUMENT", "仅支持真实JPEG/PNG图片");
      String mime = "png".equals(format) ? "image/png" : "image/jpeg";
      if (file.getContentType() != null && !mime.equals(file.getContentType()))
        throw new Problem(400, "INVALID_ARGUMENT", "声明类型与图片不一致");
      try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
        if (!readers.hasNext()) throw new Problem(400, "INVALID_ARGUMENT", "图片无法解码");
        ImageReader reader = readers.next();
        try {
          reader.setInput(in, true, true);
          long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
          if (pixels > 20000000L) throw new Problem(413, "INVALID_ARGUMENT", "图片像素过大");
          key = id + "." + format;
          db.update(
              "INSERT INTO tm_file(id,owner_id,purpose,object_key,mime,size_bytes,status,created_at,storage_provider,storage_namespace) VALUES(?,?,?,?,?,?,?,?,?,?)",
              id,
              user,
              purpose,
              key,
              mime,
              file.getSize(),
              "UPLOADING",
              System.currentTimeMillis(),
              storage.provider(),
              storage.namespace());
          java.awt.image.BufferedImage image = reader.read(0);
          ByteArrayOutputStream encoded = new ByteArrayOutputStream();
          if (!ImageIO.write(image, format, encoded)) throw new IOException("No encoder");
          storage.put(key, encoded.toByteArray(), mime);
          db.update("UPDATE tm_file SET size_bytes=? WHERE id=?", encoded.size(), id);
        } finally {
          reader.dispose();
        }
      }
      db.update("UPDATE tm_file SET status='READY' WHERE id=?", id);
      return Api.map(
          "fileId",
          id,
          "url",
          "/api/files/" + id + "/content",
          "sizeBytes",
          db.count("SELECT size_bytes FROM tm_file WHERE id=?", id),
          "contentType",
          mime);
    } catch (Problem p) {
      throw p;
    } catch (Exception e) {
      if (key != null) throw new Problem(503, "DEPENDENCY_UNAVAILABLE", "图片存储失败，后台将清理未完成上传");
      throw new Problem(400, "INVALID_ARGUMENT", "图片处理失败");
    }
  }

  public void bind(String user, String resource, List<String> ids) {
    if (ids.size() > (resource.startsWith("event:") ? 10 : 9))
      throw new Problem(400, "INVALID_ARGUMENT", "最多9张图片");
    List<String> sorted = new ArrayList<>(new TreeSet<>(ids));
    for (String id : sorted) {
      Map<String, Object> f = db.must("SELECT * FROM tm_file WHERE id=? FOR UPDATE", id);
      Api.require(
          "READY".equals(f.get("status"))
              && user.equals(f.get("owner_id"))
              && (resource.startsWith("event:")
                      ? "EVENT"
                      : resource.startsWith("venue:") ? "VENUE" : "POST")
                  .equals(f.get("purpose")),
          "FORBIDDEN",
          "图片不存在、未就绪或不属于当前用户");
    }
    db.update("DELETE FROM tm_file_ref WHERE resource_id=?", resource);
    for (String id : sorted) db.update("INSERT INTO tm_file_ref VALUES(?,?)", id, resource);
  }

  public boolean delete(String user, boolean admin, String id) {
    String key =
        tx.execute(
            st -> {
              Map<String, Object> f = db.one("SELECT * FROM tm_file WHERE id=? FOR UPDATE", id);
              if (f == null || "DELETED".equals(f.get("status"))) return null;
              checkStorage(f);
              if (!admin && !user.equals(f.get("owner_id")))
                throw new Problem(403, "FORBIDDEN", "不能删除别人的文件");
              Api.require(
                  db.count("SELECT COUNT(*) FROM tm_file_ref WHERE file_id=?", id) == 0,
                  "FILE_REFERENCED",
                  "图片已被使用");
              db.update("UPDATE tm_file SET status='DELETING' WHERE id=?", id);
              return Db.s(f, "object_key");
            });
    if (key == null) return true;
    try {
      storage.delete(key);
      db.update("UPDATE tm_file SET status='DELETED' WHERE id=?", id);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  public Map<String, Object> record(String id) {
    Map<String, Object> f = db.must("SELECT * FROM tm_file WHERE id=? AND status='READY'", id);
    checkStorage(f);
    return f;
  }

  private void checkStorage(Map<String, Object> f) {
    if (!Objects.equals(storage.provider(), f.get("storage_provider"))
        || !Objects.equals(storage.namespace(), f.get("storage_namespace")))
      throw new Problem(503, "STORAGE_CONFIGURATION_CHANGED", "文件所属存储未连接，需恢复配置或迁移数据");
  }

  public Path path(String id) {
    return storage.localPath(Db.s(record(id), "object_key"));
  }

  public byte[] content(String id) {
    try {
      return storage.get(Db.s(record(id), "object_key"));
    } catch (IOException e) {
      throw new Problem(503, "DEPENDENCY_UNAVAILABLE", "图片暂不可用");
    }
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000)
  public void cleanup() {
    for (Map<String, Object> f :
        db.list(
            "SELECT * FROM tm_file WHERE status='DELETING' OR (status='UPLOADING' AND created_at<?)",
            System.currentTimeMillis() - 600000)) {
      try {
        delete(Db.s(f, "owner_id"), true, Db.s(f, "id"));
      } catch (RuntimeException ignored) {
      }
    }
  }
}
