package io.tickmeet.trade;

import io.tickmeet.catalog.*;
import io.tickmeet.common.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {
  private final Db db;
  private final TradeService trade;
  private final TransactionTemplate tx;

  public PaymentService(Db d, TradeService t, PlatformTransactionManager tm) {
    db = d;
    trade = t;
    tx = new TransactionTemplate(tm);
  }

  private String digest(Map<String, Object> b) {
    try {
      byte[] bytes =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(
                  Json.write(new TreeMap<>(b)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder();
      for (byte v : bytes) out.append(String.format("%02x", v & 255));
      return out.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public Map<String, Object> payment(String user, String order, String key) {
    Api.idempotency(key);
    return tx.execute(
        s -> {
          Map<String, Object> o =
              db.must("SELECT * FROM tm_order WHERE id=? AND user_id=? FOR UPDATE", order, user);
          Map<String, Object> p =
              db.one("SELECT * FROM tm_payment WHERE user_id=? AND idem_key=?", user, key);
          if (p != null) {
            Api.require(order.equals(p.get("order_id")), "IDEMPOTENCY_CONFLICT", "请求标识已用于其他订单");
            return paymentView(p, o);
          }
          Api.require("UNPAID".equals(o.get("status")), "ORDER_STATE_CONFLICT", "订单不是待支付状态");
          Api.require(System.currentTimeMillis() < Db.n(o, "expire_at"), "ORDER_EXPIRED", "订单已过期");
          String id = Db.id();
          db.update(
              "INSERT INTO tm_payment(id,order_id,user_id,idem_key,amount_cent,status,created_at) VALUES(?,?,?,?,?,'PENDING',?)",
              id,
              order,
              user,
              key,
              o.get("amount_cent"),
              System.currentTimeMillis());
          return paymentView(db.must("SELECT * FROM tm_payment WHERE id=?", id), o);
        });
  }

  private Map<String, Object> paymentView(Map<String, Object> p, Map<String, Object> o) {
    return Api.map(
        "paymentId",
        p.get("id"),
        "orderId",
        o.get("id"),
        "amountCent",
        p.get("amount_cent"),
        "status",
        p.get("status"),
        "mode",
        "MOCK",
        "expireAt",
        CatalogService.iso(o.get("expire_at")));
  }

  public void notifyPayment(Map<String, Object> b) {
    String event = Api.text(b, "eventId"),
        payment = Api.text(b, "paymentId"),
        transaction = Api.text(b, "transactionId"),
        state = Api.text(b, "status");
    Api.require(Arrays.asList("SUCCESS", "FAILED").contains(state), "INVALID_ARGUMENT", "支付状态无效");
    tx.execute(
        s -> {
          Map<String, Object> raw = db.must("SELECT * FROM tm_payment WHERE id=?", payment);
          Map<String, Object> o =
              db.must("SELECT * FROM tm_order WHERE id=? FOR UPDATE", raw.get("order_id"));
          Map<String, Object> p =
              db.must("SELECT * FROM tm_payment WHERE id=? FOR UPDATE", payment);
          Api.require(
              Api.number(b, "amountCent") == Db.n(p, "amount_cent"), "INVALID_ARGUMENT", "支付金额不匹配");
          String hash = digest(b);
          Map<String, Object> receipt = db.one("SELECT * FROM tm_callback WHERE event_id=?", event);
          if (receipt != null) {
            Api.require(
                hash.equals(receipt.get("payload_hash")), "IDEMPOTENCY_CONFLICT", "通知标识内容不一致");
            return null;
          }
          db.update(
              "INSERT INTO tm_callback VALUES(?,?,?)", event, hash, System.currentTimeMillis());
          if (!"PENDING".equals(p.get("status"))) return null;
          if ("FAILED".equals(state)) {
            db.update(
                "UPDATE tm_payment SET status='FAILED',transaction_id=? WHERE id=?",
                transaction,
                payment);
            return null;
          }
          if (!"UNPAID".equals(o.get("status"))
              || System.currentTimeMillis() >= Db.n(o, "expire_at")) {
            db.update(
                "UPDATE tm_payment SET status='COMPENSATION_REQUIRED',transaction_id=? WHERE id=?",
                transaction,
                payment);
            return null;
          }
          db.update(
              "UPDATE tm_payment SET status='SUCCEEDED',transaction_id=? WHERE id=?",
              transaction,
              payment);
          db.update(
              "UPDATE tm_order SET status='PAID',paid_at=? WHERE id=?",
              System.currentTimeMillis(),
              o.get("id"));
          db.update(
              "INSERT INTO tm_ticket(id,order_id,user_id,session_id,qr_payload,status) VALUES(?,?,?,?,?,'VALID')",
              Db.id(),
              o.get("id"),
              o.get("user_id"),
              o.get("session_id"),
              Db.id() + Db.id());
          return null;
        });
  }

  public Object simulate(String user, String id) {
    Map<String, Object> p = db.must("SELECT * FROM tm_payment WHERE id=? AND user_id=?", id, user);
    notifyPayment(
        Api.map(
            "eventId",
            "mock_" + id,
            "paymentId",
            id,
            "transactionId",
            "mocktx_" + id,
            "status",
            "SUCCESS",
            "amountCent",
            p.get("amount_cent")));
    return trade.order(user, Db.s(p, "order_id"));
  }

  public Map<String, Object> tickets(String user, String state, int page, int size) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> t : db.list("SELECT * FROM tm_ticket WHERE user_id=?", user))
      if (state == null || state.equals(t.get("status"))) out.add(ticketView(t, false));
    return Api.page(out, page, size);
  }

  public Map<String, Object> ticket(String user, String id) {
    return ticketView(db.must("SELECT * FROM tm_ticket WHERE id=? AND user_id=?", id, user), true);
  }

  private Map<String, Object> ticketView(Map<String, Object> t, boolean detail) {
    Map<String, Object> o = db.must("SELECT * FROM tm_order WHERE id=?", t.get("order_id")),
        s = db.must("SELECT * FROM tm_session WHERE id=?", t.get("session_id"));
    Map<String, Object> v =
        db.must(
            "SELECT v.* FROM tm_venue v JOIN tm_event e ON e.venue_id=v.id WHERE e.id=?",
            o.get("event_id"));
    Map<String, Object> r =
        Api.map(
            "id",
            t.get("id"),
            "orderId",
            t.get("order_id"),
            "sessionId",
            t.get("session_id"),
            "eventTitle",
            o.get("event_title"),
            "sessionName",
            o.get("session_name"),
            "ticketTypeName",
            o.get("type_name"),
            "status",
            t.get("status"),
            "entryStartAt",
            CatalogService.iso(s.get("entry_start")),
            "entryEndAt",
            CatalogService.iso(s.get("entry_end")),
            "usedAt",
            CatalogService.iso(t.get("used_at")),
            "venueName",
            v.get("name"),
            "address",
            v.get("address"));
    if (detail) r.put("qrPayload", t.get("qr_payload"));
    return r;
  }

  public Map<String, Object> verify(String staff, String qr, String session, String key) {
    Api.idempotency(key);
    if (db.count(
            "SELECT COUNT(*) FROM tm_staff_session WHERE user_id=? AND session_id=?",
            staff,
            session)
        == 0) throw new Problem(403, "FORBIDDEN", "无权核销此场次");
    return tx.execute(
        s -> {
          Map<String, Object> raw =
              db.must("SELECT * FROM tm_ticket WHERE qr_payload=? AND session_id=?", qr, session);
          Map<String, Object> o =
              db.must("SELECT * FROM tm_order WHERE id=? FOR UPDATE", raw.get("order_id"));
          Map<String, Object> t =
              db.must("SELECT * FROM tm_ticket WHERE id=? FOR UPDATE", raw.get("id"));
          Map<String, Object> previous =
              db.one(
                  "SELECT * FROM tm_verification WHERE staff_id=? AND request_key=?", staff, key);
          if (previous != null) {
            Api.require(
                t.get("id").equals(previous.get("ticket_id")),
                "IDEMPOTENCY_CONFLICT",
                "核销标识已用于其他票");
            return verification(t, o, "VERIFIED");
          }
          if ("USED".equals(t.get("status"))) return verification(t, o, "ALREADY_USED");
          Api.require(
              "PAID".equals(o.get("status")) && "VALID".equals(t.get("status")),
              "TICKET_NOT_VALID",
              "票据不可核销");
          Map<String, Object> slot = db.must("SELECT * FROM tm_session WHERE id=?", session);
          long now = System.currentTimeMillis();
          Api.require(
              now >= Db.n(slot, "entry_start") && now < Db.n(slot, "entry_end"),
              "ENTRY_WINDOW_CLOSED",
              "不在入场时间内");
          db.update("UPDATE tm_ticket SET status='USED',used_at=? WHERE id=?", now, t.get("id"));
          db.update("UPDATE tm_order SET status='USED' WHERE id=?", o.get("id"));
          db.update(
              "INSERT INTO tm_verification VALUES(?,?,?,?,?)",
              Db.id(),
              t.get("id"),
              staff,
              key,
              now);
          t.put("used_at", now);
          return verification(t, o, "VERIFIED");
        });
  }

  private Map<String, Object> verification(
      Map<String, Object> t, Map<String, Object> o, String result) {
    return Api.map(
        "ticketId",
        t.get("id"),
        "result",
        result,
        "verifiedAt",
        CatalogService.iso(t.get("used_at")),
        "eventTitle",
        o.get("event_title"),
        "sessionName",
        o.get("session_name"));
  }

  public Map<String, Object> refund(String user, String order, String key, String reason) {
    Api.idempotency(key);
    return tx.execute(
        s -> {
          Map<String, Object> o =
              db.must("SELECT * FROM tm_order WHERE id=? AND user_id=? FOR UPDATE", order, user);
          Map<String, Object> old =
              db.one("SELECT * FROM tm_refund WHERE user_id=? AND idem_key=?", user, key);
          if (old != null) {
            Api.require(order.equals(old.get("order_id")), "IDEMPOTENCY_CONFLICT", "退款标识已使用");
            return TradeService.refundView(old);
          }
          Map<String, Object> e = db.must("SELECT * FROM tm_event WHERE id=?", o.get("event_id"));
          Api.require(
              Boolean.TRUE.equals(e.get("refund_allowed"))
                  && e.get("refund_deadline") != null
                  && System.currentTimeMillis() < Db.n(e, "refund_deadline"),
              "REFUND_NOT_ALLOWED",
              "此活动不在可退票范围");
          Api.require("PAID".equals(o.get("status")), "ORDER_STATE_CONFLICT", "仅未核销已支付票可退");
          Map<String, Object> t =
              db.must("SELECT * FROM tm_ticket WHERE order_id=? FOR UPDATE", order);
          Api.require("VALID".equals(t.get("status")), "TICKET_NOT_VALID", "票据不可退款");
          String id = Db.id();
          db.update(
              "INSERT INTO tm_refund(id,order_id,user_id,idem_key,amount_cent,status,reason,created_at) VALUES(?,?,?,?,?,'PROCESSING',?,?)",
              id,
              order,
              user,
              key,
              o.get("amount_cent"),
              reason,
              System.currentTimeMillis());
          db.update("UPDATE tm_order SET status='REFUNDING' WHERE id=?", order);
          db.update("UPDATE tm_ticket SET status='REFUNDING' WHERE order_id=?", order);
          return TradeService.refundView(db.must("SELECT * FROM tm_refund WHERE id=?", id));
        });
  }

  public void notifyRefund(Map<String, Object> b) {
    String id = Api.text(b, "refundId");
    tx.execute(
        s -> {
          Map<String, Object> r = db.must("SELECT * FROM tm_refund WHERE id=?", id);
          Map<String, Object> o =
              db.must("SELECT * FROM tm_order WHERE id=? FOR UPDATE", r.get("order_id"));
          r = db.must("SELECT * FROM tm_refund WHERE id=? FOR UPDATE", id);
          Api.require(
              Api.number(b, "amountCent") == Db.n(r, "amount_cent"), "INVALID_ARGUMENT", "退款金额不一致");
          if (!"PROCESSING".equals(r.get("status"))) return null;
          String state = Api.text(b, "status");
          Api.require(
              Arrays.asList("SUCCESS", "FAILED").contains(state), "INVALID_ARGUMENT", "退款状态无效");
          boolean success = "SUCCESS".equals(state);
          db.update(
              "UPDATE tm_refund SET status=?,transaction_id=? WHERE id=?",
              success ? "SUCCEEDED" : "FAILED",
              Api.text(b, "transactionId"),
              id);
          db.update(
              "UPDATE tm_order SET status=? WHERE id=?",
              success ? "REFUNDED" : "PAID",
              o.get("id"));
          db.update(
              "UPDATE tm_ticket SET status=? WHERE order_id=?",
              success ? "VOID" : "VALID",
              o.get("id"));
          if (success) trade.returnStock(o);
          return null;
        });
  }

  public Object simulateRefund(String user, String id) {
    Map<String, Object> r = db.must("SELECT * FROM tm_refund WHERE id=? AND user_id=?", id, user);
    notifyRefund(
        Api.map(
            "refundId",
            id,
            "transactionId",
            "mock_refund_" + id,
            "amountCent",
            r.get("amount_cent"),
            "status",
            "SUCCESS"));
    return trade.order(user, Db.s(r, "order_id"));
  }
}
