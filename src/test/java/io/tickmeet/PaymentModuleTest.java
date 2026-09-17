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
class PaymentModuleTest {
  @Autowired TradeService t;
  @Autowired PaymentService p;
  @Autowired CatalogService c;
  @Autowired Db db;

  String order(String u) {
    String type = TradeFixture.type(c, 4);
    String r = (String) t.reserve(u, type, Db.id()).get("reservationId");
    t.createOrder(r);
    return Db.s(db.must("SELECT * FROM tm_order WHERE reservation_id=?", r), "id");
  }

  void pay(String u, String o) {
    String id = (String) p.payment(u, o, Db.id()).get("paymentId");
    p.simulate(u, id);
  }

  @Test
  void duplicatePaymentIssuesOneTicket() {
    String u = Db.id(), o = order(u), id = (String) p.payment(u, o, Db.id()).get("paymentId");
    p.simulate(u, id);
    p.simulate(u, id);
    assertEquals(1, db.count("SELECT COUNT(*) FROM tm_ticket WHERE order_id=?", o));
    assertEquals("PAID", t.order(u, o).get("status"));
  }

  @Test
  void paymentAfterClosureRequiresCompensation() {
    String u = Db.id(), o = order(u), id = (String) p.payment(u, o, Db.id()).get("paymentId");
    t.cancel(u, o);
    p.simulate(u, id);
    assertEquals("CLOSED", t.order(u, o).get("status"));
    assertEquals(0, db.count("SELECT COUNT(*) FROM tm_ticket WHERE order_id=?", o));
    assertEquals(
        "COMPENSATION_REQUIRED", db.must("SELECT * FROM tm_payment WHERE id=?", id).get("status"));
  }

  @Test
  void concurrentScansOnlyOneVerification() throws Exception {
    String u = Db.id(), o = order(u);
    pay(u, o);
    Map<String, Object> ticket = db.must("SELECT * FROM tm_ticket WHERE order_id=?", o);
    String staff = Db.id(), session = Db.s(ticket, "session_id"), qr = Db.s(ticket, "qr_payload");
    db.update("INSERT INTO tm_staff_session VALUES(?,?)", staff, session);
    ExecutorService pool = Executors.newFixedThreadPool(6);
    List<Future<Map<String, Object>>> fs = new ArrayList<>();
    for (int i = 0; i < 6; i++) fs.add(pool.submit(() -> p.verify(staff, qr, session, Db.id())));
    int verified = 0;
    for (Future<Map<String, Object>> f : fs)
      if ("VERIFIED".equals(f.get().get("result"))) verified++;
    pool.shutdown();
    assertEquals(1, verified);
    assertEquals(
        1, db.count("SELECT COUNT(*) FROM tm_verification WHERE ticket_id=?", ticket.get("id")));
    assertThrows(Problem.class, () -> p.refund(u, o, Db.id(), "不去了"));
  }

  @Test
  void refundInvalidatesTicketAndReturnsStockOnce() {
    String u = Db.id(), o = order(u);
    pay(u, o);
    String id = (String) p.refund(u, o, Db.id(), "计划变更").get("refundId");
    p.simulateRefund(u, id);
    p.simulateRefund(u, id);
    assertEquals("REFUNDED", t.order(u, o).get("status"));
    assertEquals("VOID", db.must("SELECT * FROM tm_ticket WHERE order_id=?", o).get("status"));
    assertEquals(
        4,
        db.count(
            "SELECT available FROM tm_stock WHERE ticket_type_id=?",
            t.order(u, o).get("ticketTypeId")));
  }

  @Test
  void anotherUserCannotReadOrder() {
    String u = Db.id(), o = order(u);
    assertEquals(404, assertThrows(Problem.class, () -> t.order(Db.id(), o)).status);
  }
}
