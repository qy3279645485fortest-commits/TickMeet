package io.tickmeet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.tickmeet.common.*;
import io.tickmeet.files.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class StorageFailureTest {
  @MockBean StorageService storage;
  @Autowired FileService files;
  @Autowired Db db;

  @BeforeEach
  void configure() {
    when(storage.provider()).thenReturn("local");
    when(storage.namespace()).thenReturn("uploads");
  }

  MockMultipartFile image() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", out);
    return new MockMultipartFile("file", "a.png", "image/png", out.toByteArray());
  }

  @Test
  void failedUploadIsTrackedAndCleaned() throws Exception {
    String user = Db.id();
    doThrow(new IOException("simulated timeout"))
        .when(storage)
        .put(anyString(), any(byte[].class), anyString());
    assertEquals(
        503, assertThrows(Problem.class, () -> files.upload(user, false, "POST", image())).status);
    String id = Db.s(db.must("SELECT * FROM tm_file WHERE owner_id=?", user), "id");
    db.update(
        "UPDATE tm_file SET created_at=? WHERE id=?", System.currentTimeMillis() - 700000, id);
    files.cleanup();
    assertEquals("DELETED", db.must("SELECT status FROM tm_file WHERE id=?", id).get("status"));
  }

  @Test
  void deleteTimeoutRemainsPendingThenRetries() throws Exception {
    String user = Db.id(), id = Db.s(files.upload(user, false, "POST", image()), "fileId");
    doThrow(new IOException("timeout")).doNothing().when(storage).delete(anyString());
    assertFalse(files.delete(user, false, id));
    assertEquals("DELETING", db.must("SELECT status FROM tm_file WHERE id=?", id).get("status"));
    files.cleanup();
    assertTrue(files.delete(user, false, id));
    assertEquals("DELETED", db.must("SELECT status FROM tm_file WHERE id=?", id).get("status"));
  }

  @Test
  void referencedFileCannotBeDeleted() throws Exception {
    String user = Db.id(), id = Db.s(files.upload(user, false, "POST", image()), "fileId");
    db.update("INSERT INTO tm_file_ref VALUES(?,?)", id, Db.id());
    assertEquals(
        "FILE_REFERENCED", assertThrows(Problem.class, () -> files.delete(user, false, id)).code);
    verify(storage, never()).delete(anyString());
  }
}
