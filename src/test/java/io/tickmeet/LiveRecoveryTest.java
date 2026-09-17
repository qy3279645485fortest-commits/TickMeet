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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties = {
      "tickmeet.demo=true",
      "tickmeet.seed=false",
      "tickmeet.scheduling=false",
      "tickmeet.maintenance=true",
      "spring.rabbitmq.listener.simple.auto-startup=false",
      "tickmeet.mq-prefix=tickmeet-recovery-test",
      "management.server.port=0"
    })
@ActiveProfiles("live")
@EnabledIfEnvironmentVariable(named = "TICKMEET_LIVE_TEST", matches = "true")
class LiveRecoveryTest {
  @Autowired CatalogService catalog;
  @Autowired TradeService trade;
  @Autowired InventoryRecovery recovery;
  @Autowired StringRedisTemplate redis;
  @Autowired Db db;

  @Test
  void rebuildPreservesPendingOrdersAndMakesOldReleaseHarmless() {
    String type = TradeFixture.type(catalog, 5), a = Db.id(), b = Db.id();
    String ra = Db.s(trade.reserve(a, type, Db.id()), "reservationId"),
        rb = Db.s(trade.reserve(b, type, Db.id()), "reservationId");
    trade.createOrder(ra);
    String prefix = "tickmeet:ticket:{" + type + "}:";
    redis.delete(Arrays.asList(prefix + "stock", prefix + "holders", prefix + "reservations"));
    assertThrows(Problem.class, () -> trade.reserve(Db.id(), type, Db.id()));
    Map<String, Object> r = recovery.rebuild(type);
    assertEquals(3L, r.get("redisAvailable"));
    assertEquals(1L, r.get("pendingReservations"));
    assertEquals(2, r.get("holders"));
    trade.createOrder(rb);
    String order = Db.s(trade.reservation(a, ra), "orderId");
    trade.cancel(a, order);
    recovery.rebuild(type);
    String before = redis.opsForValue().get(prefix + "stock");
    trade.release(ra);
    trade.release(ra);
    assertEquals(before, redis.opsForValue().get(prefix + "stock"));
    assertEquals(4, db.count("SELECT available FROM tm_stock WHERE ticket_type_id=?", type));
  }
}
