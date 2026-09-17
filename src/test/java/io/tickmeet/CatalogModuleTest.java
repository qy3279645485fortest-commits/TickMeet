package io.tickmeet;

import static org.junit.jupiter.api.Assertions.*;

import io.tickmeet.auth.*;
import io.tickmeet.catalog.*;
import io.tickmeet.common.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class CatalogModuleTest {
  @Autowired CatalogService c;
  @Autowired Db db;

  @BeforeEach
  void admin() {
    CurrentUser.set(Api.map("id", "test-admin", "role", "ADMIN"));
    if (db.count("SELECT COUNT(*) FROM tm_category WHERE id='test'") == 0)
      db.update("INSERT INTO tm_category VALUES('test','测试',1)");
  }

  @AfterEach
  void clear() {
    CurrentUser.clear();
  }

  public static Map<String, Object> sessionInput(int capacity) {
    return Api.map(
        "name",
        "周末场",
        "startAt",
        OffsetDateTime.now().plusDays(1).toString(),
        "endAt",
        OffsetDateTime.now().plusDays(1).plusHours(8).toString(),
        "entryStartAt",
        OffsetDateTime.now().minusHours(1).toString(),
        "entryEndAt",
        OffsetDateTime.now().plusDays(1).plusHours(7).toString(),
        "capacity",
        capacity);
  }

  public static Map<String, Object> typeInput(int stock) {
    return Api.map(
        "name",
        "早鸟票",
        "priceCent",
        6800,
        "stockTotal",
        stock,
        "saleStartAt",
        OffsetDateTime.now().minusHours(1).toString(),
        "saleEndAt",
        OffsetDateTime.now().plusHours(12).toString());
  }

  String event() {
    String v =
        (String)
            c.saveVenue(
                    null,
                    Api.map(
                        "name",
                        "创意中心",
                        "city",
                        "上海",
                        "address",
                        "展览路1号",
                        "longitude",
                        121.47,
                        "latitude",
                        31.23))
                .get("id");
    return (String)
        c.saveEvent(
                null,
                Api.map(
                    "title",
                    "测试活动",
                    "categoryId",
                    "test",
                    "venueId",
                    v,
                    "description",
                    "说明",
                    "refundAllowed",
                    false))
            .get("id");
  }

  @Test
  void draftInvisibleAndPublishNeedsTicket() {
    String e = event();
    CurrentUser.clear();
    assertEquals(404, assertThrows(Problem.class, () -> c.event(e)).status);
    CurrentUser.set(Api.map("id", "admin", "role", "ADMIN"));
    assertThrows(Problem.class, () -> c.publish(e));
    String s = (String) c.saveSession(e, null, sessionInput(10)).get("id");
    c.saveType(s, null, typeInput(10));
    c.publish(e);
    CurrentUser.clear();
    assertEquals("PUBLISHED", c.event(e).get("status"));
  }

  @Test
  void allocationCannotExceedCapacity() {
    String e = event();
    String s = (String) c.saveSession(e, null, sessionInput(10)).get("id");
    c.saveType(s, null, typeInput(7));
    assertEquals(
        "CAPACITY_EXCEEDED",
        assertThrows(Problem.class, () -> c.saveType(s, null, typeInput(4))).code);
  }

  @Test
  void publishedTicketPriceIsFrozen() {
    String e = event();
    String s = (String) c.saveSession(e, null, sessionInput(10)).get("id");
    String t = (String) c.saveType(s, null, typeInput(10)).get("id");
    c.publish(e);
    assertEquals(
        "ORDER_STATE_CONFLICT",
        assertThrows(Problem.class, () -> c.saveType(null, t, typeInput(9))).code);
  }
}
