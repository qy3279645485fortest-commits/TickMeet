package io.tickmeet;

import static org.junit.jupiter.api.Assertions.*;

import io.tickmeet.auth.*;
import io.tickmeet.catalog.*;
import io.tickmeet.common.*;
import io.tickmeet.trade.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties = {
      "tickmeet.demo=true",
      "tickmeet.mq-prefix=tickmeet-test",
      "tickmeet.seed=false",
      "tickmeet.scheduling=true",
      "management.server.port=0"
    })
@ActiveProfiles("live")
@EnabledIfEnvironmentVariable(named = "TICKMEET_LIVE_TEST", matches = "true")
class LiveModuleTest {
  @Autowired TradeService trade;
  @Autowired CatalogService catalog;
  @Autowired Db db;
  @Autowired EphemeralStore store;

  @Test
  void realRedisOneTimeCodeAndRateLimit() {
    String k = "live-test:" + Db.id();
    store.put(k, "123456", 60);
    assertTrue(store.consume(k, "123456"));
    assertFalse(store.consume(k, "123456"));
    assertTrue(store.allow(k, 1, 60));
    assertFalse(store.allow(k, 1, 60));
    store.delete(k);
  }

  @Test
  void mysqlRedisRabbitBuildOrderAndRelease() throws Exception {
    String type = TradeFixture.type(catalog, 2), u = Db.id();
    String r = (String) trade.reserve(u, type, Db.id()).get("reservationId");
    Map<String, Object> result = null;
    long until = System.currentTimeMillis() + 20000;
    while (System.currentTimeMillis() < until) {
      result = trade.reservation(u, r);
      if ("ORDER_CREATED".equals(result.get("status"))) break;
      Thread.sleep(200);
    }
    assertNotNull(result);
    assertEquals("ORDER_CREATED", result.get("status"));
    assertEquals(1, db.count("SELECT available FROM tm_stock WHERE ticket_type_id=?", type));
    trade.cancel(u, result.get("orderId").toString());
    assertEquals(2, db.count("SELECT available FROM tm_stock WHERE ticket_type_id=?", type));
  }

  @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
  @Autowired io.tickmeet.cache.RedisFeatures features;
  @Autowired io.tickmeet.cache.RedisCatalogCache cache;

  @Test
  void redisCacheNullAndSingleRebuild() {
    java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    String id = Db.id();
    assertNull(
        cache.read(
            "test",
            id,
            () -> {
              calls.incrementAndGet();
              return null;
            }));
    assertNull(
        cache.read(
            "test",
            id,
            () -> {
              calls.incrementAndGet();
              return null;
            }));
    assertEquals(1, calls.get());
    cache.invalidate("test", id);
    assertEquals("value", cache.read("test", id, () -> Api.map("name", "value")).get("name"));
    cache.invalidate("test", id);
  }

  @Test
  void bitmapGeoSetsAndZset() {
    String a = Db.id(), b = Db.id(), c = Db.id();
    db.update("INSERT INTO tm_follow VALUES(?,?,?)", a, c, System.currentTimeMillis());
    db.update("INSERT INTO tm_follow VALUES(?,?,?)", b, c, System.currentTimeMillis());
    assertTrue(features.common(a, b).contains(c));
    String day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
    db.update("INSERT INTO tm_sign VALUES(?,?)", a, day);
    assertEquals(1, features.signs(a));
    String type = TradeFixture.type(catalog, 2);
    String event =
        Db.s(
            db.must(
                "SELECT s.event_id FROM tm_session s JOIN tm_ticket_type t ON t.session_id=s.id WHERE t.id=?",
                type),
            "event_id");
    String post = Db.id();
    db.update(
        "INSERT INTO tm_post VALUES(?,?,?,?,?,?,0,?)",
        post,
        event,
        c,
        "test",
        "test",
        "[]",
        System.currentTimeMillis());
    features.postPublished(post);
    assertEquals(post, features.feedRows(a, Long.MAX_VALUE).get(0).get("id"));
    db.update("INSERT INTO tm_like VALUES(?,?,?)", post, a, System.currentTimeMillis());
    assertTrue(features.likes(post).contains(a));
    assertFalse(features.nearby("上海", 121.47, 31.23, 5000).isEmpty());
  }

  @Test
  void realLuaConcurrencyNeverOversells() throws Exception {
    String type = TradeFixture.type(catalog, 5);
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(12);
    List<java.util.concurrent.Future<Map<String, Object>>> jobs = new ArrayList<>();
    for (int n = 0; n < 30; n++) jobs.add(pool.submit(() -> trade.reserve(Db.id(), type, Db.id())));
    int accepted = 0;
    for (java.util.concurrent.Future<Map<String, Object>> j : jobs) {
      Map<String, Object> r = j.get();
      if (!"REJECTED".equals(r.get("status"))) accepted++;
    }
    pool.shutdown();
    assertEquals(5, accepted);
    long end = System.currentTimeMillis() + 20000;
    while (db.count("SELECT COUNT(*) FROM tm_order WHERE ticket_type_id=?", type) < 5
        && System.currentTimeMillis() < end) Thread.sleep(100);
    assertEquals(5, db.count("SELECT COUNT(*) FROM tm_order WHERE ticket_type_id=?", type));
    assertEquals(0, db.count("SELECT available FROM tm_stock WHERE ticket_type_id=?", type));
    assertEquals("0", redis.opsForValue().get("tickmeet:ticket:{" + type + "}:stock"));
  }
}
