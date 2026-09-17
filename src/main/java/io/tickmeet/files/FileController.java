package io.tickmeet.files;

import io.tickmeet.auth.*;
import io.tickmeet.common.*;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/files")
public class FileController {
  private final FileService s;

  public FileController(FileService f) {
    s = f;
  }

  @PostMapping("/images")
  public Object upload(@RequestParam MultipartFile file, @RequestParam String purpose) {
    return ResponseEntity.status(201)
        .body(Api.ok(s.upload(CurrentUser.id(), CurrentUser.is("ADMIN"), purpose, file)));
  }

  @DeleteMapping("/{id}")
  public Object delete(@PathVariable String id) {
    boolean done = s.delete(CurrentUser.id(), CurrentUser.is("ADMIN"), id);
    return ResponseEntity.status(done ? 200 : 202).body(Api.ok(null));
  }

  @GetMapping("/{id}/content")
  public ResponseEntity<Resource> content(@PathVariable String id) {
    String type = Db.s(s.record(id), "mime");
    return ResponseEntity.ok()
        .header("X-Content-Type-Options", "nosniff")
        .contentType(MediaType.parseMediaType(type))
        .body(new ByteArrayResource(s.content(id)));
  }
}
