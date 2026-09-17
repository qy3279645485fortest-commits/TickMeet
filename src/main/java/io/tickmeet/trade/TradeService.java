package io.tickmeet.trade;

import io.micrometer.core.instrument.MeterRegistry;
import io.tickmeet.auth.*;
import io.tickmeet.catalog.*;
import io.tickmeet.common.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TradeService {
  public final Db db;
  private final Inventory inventory;
  private final TransactionTemplate tx;
  private final int reserveSeconds, payMinutes;
  private final EphemeralStore store;
  private final MeterRegistry metrics;

  public TradeService(
      Db d,
      Inventory i,
      PlatformTransactionManager tm,
      EphemeralStore st,
      MeterRegistry m,
      @Value("${tickmeet.reservation-seconds:60}") int rs,
      @Value("${tickmeet.payment-minutes:15}") int pm) {
    db = d;
    inventory = i;
    tx = new TransactionTemplate(tm);
    store = st;
    metrics = m;
    reserveSeconds = rs;
    payMinutes = pm;
  }

  public Map<String, Object> reserve(String user, String type, String key) {
    Api.idempotency(key);
    Map<String, Object> old =
        db.one("SELECT * FROM tm_reservation WHERE user_id=? AND idem_key=?", user, key);
    if (old != null) {
      Api.require(type.equals(old.get("ticket_type_id")), "IDEMPOTENCY_CONFLICT", "同一请求标识不能购买不同票档");
      return reservationView(old);
    }
    if (!store.allow("buy:" + user, 10, 1)) throw new Problem(429, "RATE_LIMITED", "请求过于频繁");
    Map<String, Object> t =
        db.must(
            "SELECT t.* FROM tm_ticket_type t JOIN tm_session s ON s.id=t.session_id JOIN tm_event e ON e.id=s.event_id WHERE t.id=? AND e.status='PUBLISHED'",
            type);
    String id = Db.id();
    long now = System.currentTimeMillis();
    try {
      db.update(
          "INSERT INTO tm_reservation(id,user_id,ticket_type_id,idem_key,state,created_at,deadline) VALUES(?,?,?,?,?,?,?)",
          id,
          user,
          type,
          key,
          "INIT",
          now,
          now + reserveSeconds * 1000L);
    } catch (org.springframework.dao.DuplicateKeyException e) {
      return reserve(user, type, key);
    }
    prepare(id, t);
    return reservation(user, id);
  }

  private void prepare(String id, Map<String, Object> t) {
    tx.execute(
        status -> {
          Map<String, Object> lock =
              db.must("SELECT * FROM tm_reservation WHERE id=? FOR UPDATE", id);
          if (!"INIT".equals(lock.get("state"))) return null;
          if (System.currentTimeMillis() >= Db.n(lock, "deadline")) {
            reject(lock, "EXPIRED", "BUILD_TIMEOUT");
            return null;
          }
          Map<String, Object> current =
              db.must(
                  "SELECT * FROM tm_ticket_type WHERE id=? FOR UPDATE", lock.get("ticket_type_id"));
          String outcome = inventory.hold(current, Db.s(lock, "user_id"), id);
          if ("OK".equals(outcome)) {
            db.update("UPDATE tm_reservation SET state='RESERVED' WHERE id=?", id);
            outbox("CREATE_ORDER", id, System.currentTimeMillis());
          } else {
            db.update(
                "UPDATE tm_reservation SET state='REJECTED',reason=? WHERE id=?", outcome, id);
          }
          metrics.counter("ticket.reservation.requests", "outcome", outcome).increment();
          return null;
        });
  }

  public void createOrder(String id) {
    tx.execute(
        status -> {
          Map<String, Object> r = db.must("SELECT * FROM tm_reservation WHERE id=? FOR UPDATE", id);
          if (!"RESERVED".equals(r.get("state"))) return null;
          long now = System.currentTimeMillis();
          if (now >= Db.n(r, "deadline")) {
            reject(r, "EXPIRED", "BUILD_TIMEOUT");
            return null;
          }
          String user = Db.s(r, "user_id"), type = Db.s(r, "ticket_type_id");
          Map<String, Object> stock =
              db.must("SELECT * FROM tm_stock WHERE ticket_type_id=? FOR UPDATE", type);
          Map<String, Object> slot =
              db.one("SELECT * FROM tm_slot WHERE user_id=? AND ticket_type_id=?", user, type);
          if (slot != null && slot.get("reservation_id") != null) {
            reject(r, "REJECTED", "PURCHASE_LIMIT");
            return null;
          }
          if (Db.n(stock, "available") <= 0) {
            reject(r, "REJECTED", "SOLD_OUT");
            return null;
          }
          if (slot == null)
            db.update(
                "INSERT INTO tm_slot(user_id,ticket_type_id,reservation_id) VALUES(?,?,?)",
                user,
                type,
                id);
          else
            db.update(
                "UPDATE tm_slot SET reservation_id=? WHERE user_id=? AND ticket_type_id=?",
                id,
                user,
                type);
          Map<String, Object> t = db.must("SELECT * FROM tm_ticket_type WHERE id=?", type),
              s = db.must("SELECT * FROM tm_session WHERE id=?", t.get("session_id")),
              e = db.must("SELECT * FROM tm_event WHERE id=?", s.get("event_id"));
          String order = Db.id();
          int changed =
              db.update(
                  "UPDATE tm_stock SET available=available-1 WHERE ticket_type_id=? AND available>0",
                  type);
          Api.require(changed == 1, "SOLD_OUT", "库存不足");
          db.update(
              "INSERT INTO tm_order(id,reservation_id,user_id,ticket_type_id,session_id,event_id,event_title,session_name,type_name,amount_cent,status,created_at,expire_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
              order,
              id,
              user,
              type,
              s.get("id"),
              e.get("id"),
              e.get("title"),
              s.get("name"),
              t.get("name"),
              t.get("price_cent"),
              "UNPAID",
              now,
              now + payMinutes * 60000L);
          db.update(
              "UPDATE tm_reservation SET state='ORDER_CREATED',order_id=? WHERE id=?", order, id);
          outbox("ORDER_TIMEOUT", order, now);
          metrics.counter("ticket.order.created").increment();
          metrics
              .timer("ticket.order.creation")
              .record(now - Db.n(r, "created_at"), java.util.concurrent.TimeUnit.MILLISECONDS);
          return null;
        });
  }

  private void reject(Map<String, Object> r, String state, String reason) {
    db.update("UPDATE tm_reservation SET state=?,reason=? WHERE id=?", state, reason, r.get("id"));
    outbox("RELEASE", Db.s(r, "id"), System.currentTimeMillis());
  }

  public void outbox(String kind, String id, long when) {
    db.update(
        "INSERT INTO tm_outbox(id,kind,aggregate_id,status,created_at,next_at,attempts) VALUES(?,?,?,?,?,?,0)",
        Db.id(),
        kind,
        id,
        "PENDING",
        System.currentTimeMillis(),
        when);
  }

  public void release(String r) {
    Map<String, Object> row = db.must("SELECT * FROM tm_reservation WHERE id=?", r);
    inventory.release(Db.s(row, "ticket_type_id"), Db.s(row, "user_id"), r);
  }

  public Map<String, Object> reservation(String user, String id) {
    Map<String, Object> r =
        db.must("SELECT * FROM tm_reservation WHERE id=? AND user_id=?", id, user);
    return reservationView(r);
  }

  private Map<String, Object> reservationView(Map<String, Object> r) {
    String state = Db.s(r, "state");
    if ("INIT".equals(state) || "RESERVED".equals(state)) state = "PROCESSING";
    return Api.map(
        "reservationId",
        r.get("id"),
        "status",
        state,
        "orderId",
        r.get("order_id"),
        "reasonCode",
        r.get("reason"),
        "createdAt",
        CatalogService.iso(r.get("created_at")),
        "buildDeadlineAt",
        CatalogService.iso(r.get("deadline")));
  }

  public Map<String, Object> order(String user, String id) {
    return orderView(db.must("SELECT * FROM tm_order WHERE id=? AND user_id=?", id, user));
  }

  public Map<String, Object> orders(String user, String state, int page, int size) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> o :
        db.list("SELECT * FROM tm_order WHERE user_id=? ORDER BY created_at DESC,id DESC", user))
      if (state == null || state.equals(o.get("status"))) out.add(orderView(o));
    return Api.page(out, page, size);
  }

  public Map<String, Object> orderView(Map<String, Object> o) {
    Map<String, Object> ticket = db.one("SELECT id FROM tm_ticket WHERE order_id=?", o.get("id"));
    return Api.map(
        "id",
        o.get("id"),
        "reservationId",
        o.get("reservation_id"),
        "eventId",
        o.get("event_id"),
        "sessionId",
        o.get("session_id"),
        "ticketTypeId",
        o.get("ticket_type_id"),
        "eventTitle",
        o.get("event_title"),
        "sessionName",
        o.get("session_name"),
        "ticketTypeName",
        o.get("type_name"),
        "amountCent",
        o.get("amount_cent"),
        "status",
        o.get("status"),
        "createdAt",
        CatalogService.iso(o.get("created_at")),
        "expireAt",
        CatalogService.iso(o.get("expire_at")),
        "paidAt",
        CatalogService.iso(o.get("paid_at")),
        "closedAt",
        CatalogService.iso(o.get("closed_at")),
        "closeReason",
        o.get("close_reason"),
        "ticketId",
        ticket == null ? null : ticket.get("id"),
        "refund",
        refundView(db.one("SELECT * FROM tm_refund WHERE order_id=?", o.get("id"))));
  }

  public Map<String, Object> cancel(String user, String id) {
    order(user, id);
    close(id, false);
    return order(user, id);
  }

  public void close(String id, boolean timeout) {
    tx.execute(
        status -> {
          Map<String, Object> o = db.must("SELECT * FROM tm_order WHERE id=? FOR UPDATE", id);
          if (!"UNPAID".equals(o.get("status"))) {
            if (!timeout && !"CLOSED".equals(o.get("status")))
              throw new Problem(409, "ORDER_STATE_CONFLICT", "已支付订单不能直接取消");
            return null;
          }
          if (timeout && Db.n(o, "expire_at") > System.currentTimeMillis()) return null;
          db.update(
              "UPDATE tm_order SET status='CLOSED',closed_at=?,close_reason=? WHERE id=?",
              System.currentTimeMillis(),
              timeout ? "TIMEOUT" : "CANCELED",
              id);
          returnStock(o);
          return null;
        });
  }

  void returnStock(Map<String, Object> o) {
    db.update(
        "UPDATE tm_stock SET available=available+1 WHERE ticket_type_id=?",
        o.get("ticket_type_id"));
    db.update(
        "UPDATE tm_slot SET reservation_id=NULL WHERE user_id=? AND ticket_type_id=? AND reservation_id=?",
        o.get("user_id"),
        o.get("ticket_type_id"),
        o.get("reservation_id"));
    outbox("RELEASE", Db.s(o, "reservation_id"), System.currentTimeMillis());
  }

  public void recover() {
    long now = System.currentTimeMillis();
    for (Map<String, Object> r :
        db.list(
            "SELECT * FROM tm_reservation WHERE state IN ('INIT','RESERVED') AND deadline<?", now))
      tx.execute(
          s -> {
            Map<String, Object> l =
                db.must("SELECT * FROM tm_reservation WHERE id=? FOR UPDATE", r.get("id"));
            if (Arrays.asList("INIT", "RESERVED").contains(l.get("state")))
              reject(l, "EXPIRED", "BUILD_TIMEOUT");
            return null;
          });
    for (Map<String, Object> o :
        db.list("SELECT id FROM tm_order WHERE status='UNPAID' AND expire_at<?", now))
      close(Db.s(o, "id"), true);
    for (Map<String, Object> r :
        db.list(
            "SELECT * FROM tm_reservation WHERE state='INIT' AND deadline>? AND created_at<?",
            now,
            now - 2000))
      prepare(
          Db.s(r, "id"),
          db.must("SELECT * FROM tm_ticket_type WHERE id=?", r.get("ticket_type_id")));
  }

  public static Map<String, Object> refundView(Map<String, Object> r) {
    return r == null
        ? null
        : Api.map(
            "refundId",
            r.get("id"),
            "orderId",
            r.get("order_id"),
            "amountCent",
            r.get("amount_cent"),
            "status",
            r.get("status"),
            "reason",
            r.get("reason"),
            "createdAt",
            CatalogService.iso(r.get("created_at")));
  }
}
