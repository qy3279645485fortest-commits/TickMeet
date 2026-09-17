package io.tickmeet;

import static org.junit.jupiter.api.Assertions.*;

import io.tickmeet.catalog.*;
import io.tickmeet.common.*;
import io.tickmeet.community.*;
import io.tickmeet.files.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class FileCommunityModuleTest {
  @Autowired FileService files;
  @Autowired CommunityService community;
  @Autowired CatalogService catalog;
  @Autowired Db db;

  String user() {
    String id = Db.id();
    db.update(
        "INSERT INTO tm_user(id,email,nick_name,role,created_at) VALUES(?,?,?,'USER',?)",
        id,
        id + "@test.local",
        "观众",
        System.currentTimeMillis());
    return id;
  }

  @Test
  void disguisedImagesAreRejected() {
    assertEquals(
        400,
        assertThrows(
                Problem.class,
                () ->
                    files.upload(
                        user(),
                        false,
                        "POST",
                        new MockMultipartFile(
                            "file", "a.jpg", "image/jpeg", "<script>x</script>".getBytes())))
            .status);
  }

  @Test
  void imageCanBeUploadedButNotDeletedByOthers() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", out);
    String u = user(),
        id =
            (String)
                files
                    .upload(
                        u,
                        false,
                        "POST",
                        new MockMultipartFile("file", "a.png", "image/png", out.toByteArray()))
                    .get("fileId");
    assertTrue(java.nio.file.Files.exists(files.path(id)));
    assertEquals(403, assertThrows(Problem.class, () -> files.delete(user(), false, id)).status);
    files.delete(u, false, id);
    files.delete(u, false, id);
  }

  @Test
  void repeatedLikeAndFollowAreIdempotent() {
    String u = user(), v = user(), type = TradeFixture.type(catalog, 2);
    Map<String, Object> e =
        db.must(
            "SELECT s.event_id FROM tm_session s JOIN tm_ticket_type t ON t.session_id=s.id WHERE t.id=?",
            type);
    String post =
        (String)
            ((Map<?, ?>)
                    community.post(
                        u,
                        Api.map(
                            "eventId",
                            e.get("event_id"),
                            "title",
                            "现场",
                            "content",
                            "内容",
                            "imageFileIds",
                            Collections.emptyList())))
                .get("id");
    community.like(v, post, true);
    community.like(v, post, true);
    assertEquals(1, db.count("SELECT liked_count FROM tm_post WHERE id=?", post));
    community.follow(v, u, true);
    community.follow(v, u, true);
    assertEquals(
        1, db.count("SELECT COUNT(*) FROM tm_follow WHERE user_id=? AND follow_user_id=?", v, u));
    community.like(v, post, false);
    assertEquals(0, db.count("SELECT liked_count FROM tm_post WHERE id=?", post));
  }

  @Test
  void signDoesNotDoubleCount() {
    String u = user();
    community.sign(u);
    community.sign(u);
    assertEquals(1, ((Map<?, ?>) community.countSign(u)).get("consecutiveDays"));
  }
}
