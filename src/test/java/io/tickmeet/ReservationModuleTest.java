package io.tickmeet;

import static org.junit.jupiter.api.Assertions.*;

import io.tickmeet.catalog.*;
import io.tickmeet.common.*;
import io.tickmeet.trade.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ReservationModuleTest {
  @Autowired TradeService t;
  @Autowired CatalogService c;
  @Autowired Db db;
  @Autowired OutboxWorker worker;

  @Test
  void concurrentRequestsDoNotOversell() throws Exception {
    String type = TradeFixture.type(c, 8);
    ExecutorService pool = Executors.newFixedThreadPool(12);
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < 40; i++)
      futures.add(
          pool.submit(
              () -> {
                Map<String, Object> r = t.reserve(Db.id(), type, Db.id());
                t.createOrder((String) r.get("reservationId"));
              }));
    for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
    pool.shutdown();
    assertEquals(8, db.count("SELECT COUNT(*) FROM tm_order WHERE ticket_type_id=?", type));
    assertEquals(0, db.count("SELECT available FROM tm_stock WHERE ticket_type_id=?", type));
  }

  @Test
  void duplicateRequestAndMessageCreateOneOrder() {
    String type = TradeFixture.type(c, 5), user = Db.id(), key = Db.id();
    Map<String, Object> a = t.reserve(user, type, key), b = t.reserve(user, type, key);
    assertEquals(a.get("reservationId"), b.get("reservationId"));
    String r = (String) a.get("reservationId");
    t.createOrder(r);
    t.createOrder(r);
    assertEquals(1, db.count("SELECT COUNT(*) FROM tm_order WHERE reservation_id=?", r));
    assertEquals(4, db.count("SELECT available FROM tm_stock WHERE ticket_type_id=?", type));
  }

  @Test
  void cancelReleaseAndLateDuplicateDoNotReleaseNewPurchase() {
    String type = TradeFixture.type(c, 2), user = Db.id();
    String r = (String) t.reserve(user, type, Db.id()).get("reservationId");
    t.createOrder(r);
    String order = Db.s(db.must("SELECT * FROM tm_order WHERE reservation_id=?", r), "id");
    t.cancel(user, order);
    t.release(r);
    t.release(r);
    assertEquals(2, db.count("SELECT available FROM tm_stock WHERE ticket_type_id=?", type));
    String r2 = (String) t.reserve(user, type, Db.id()).get("reservationId");
    t.createOrder(r2);
    t.release(r);
    assertEquals(1, db.count("SELECT available FROM tm_stock WHERE ticket_type_id=?", type));
    Map<String, Object> again = t.reserve(user, type, Db.id());
    assertEquals("REJECTED", again.get("status"));
  }

  @Test
  void expiredReservationCannotBecomeOrder() {
    String type = TradeFixture.type(c, 1), user = Db.id();
    String r = (String) t.reserve(user, type, Db.id()).get("reservationId");
    db.update("UPDATE tm_reservation SET deadline=? WHERE id=?", System.currentTimeMillis() - 1, r);
    t.createOrder(r);
    t.release(r);
    assertEquals("EXPIRED", t.reservation(user, r).get("status"));
    assertEquals(0, db.count("SELECT COUNT(*) FROM tm_order WHERE reservation_id=?", r));
  }

  @Test
  void oneKeyCannotBuyTwoTypes() {
    String one = TradeFixture.type(c, 1), two = TradeFixture.type(c, 1), u = Db.id(), key = Db.id();
    t.reserve(u, one, key);
    assertEquals(
        "IDEMPOTENCY_CONFLICT", assertThrows(Problem.class, () -> t.reserve(u, two, key)).code);
  }
}
