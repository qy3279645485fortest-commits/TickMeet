package io.tickmeet.trade;

import io.tickmeet.common.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Offline maintenance only: all app writers and consumers must be stopped. */
@Component
@Profile("live")
public class InventoryRecovery {
  private final Db db;
  private final StringRedisTemplate redis;
  private final TransactionTemplate tx;
  private final boolean maintenance;

  public InventoryRecovery(
      Db d,
      StringRedisTemplate r,
      PlatformTransactionManager tm,
      @Value("${tickmeet.maintenance:false}") boolean m) {
    db = d;
    redis = r;
    tx = new TransactionTemplate(tm);
    maintenance = m;
  }

  public Map<String, Object> rebuild(String type) {
    if (!maintenance) throw new Problem(409, "MAINTENANCE_REQUIRED", "必须在停止所有写入者的维护进程执行");
    return tx.execute(
        status -> {
          Map<String, Object> stock =
              db.must("SELECT * FROM tm_stock WHERE ticket_type_id=? FOR UPDATE", type);
          long available = Db.n(stock, "available"), pending = 0;
          Map<String, Object> holders = new TreeMap<>(), states = new TreeMap<>();
          for (Map<String, Object> r :
              db.list(
                  "SELECT * FROM tm_reservation WHERE ticket_type_id=? ORDER BY created_at FOR UPDATE",
                  type)) {
            String id = Db.s(r, "id"), state = Db.s(r, "state");
            boolean held = false;
            if ("RESERVED".equals(state) && Db.n(r, "deadline") > System.currentTimeMillis()) {
              held = true;
              pending++;
            } else if ("ORDER_CREATED".equals(state)) {
              Map<String, Object> o =
                  db.one("SELECT status FROM tm_order WHERE reservation_id=?", id);
              held =
                  o != null
                      && Arrays.asList("UNPAID", "PAID", "USED", "REFUNDING")
                          .contains(o.get("status"));
            }
            if (held) {
              if (holders.put(Db.s(r, "user_id"), id) != null)
                throw new Problem(409, "RECONCILIATION_FAILED", "重复有效占用，需人工核对");
              states.put(id, "HELD");
            } else if (!"INIT".equals(state)) states.put(id, "RELEASED");
          }
          long remaining = available - pending;
          Api.require(remaining >= 0, "RECONCILIATION_FAILED", "待落库预占超过数据库库存，拒绝重建");
          String prefix = "tickmeet:ticket:{" + type + "}:";
          String lua =
              "redis.call('del',KEYS[1],KEYS[2],KEYS[3]);redis.call('set',KEYS[1],ARGV[1]);local h=cjson.decode(ARGV[2]);for k,v in pairs(h) do redis.call('hset',KEYS[2],k,v) end;local s=cjson.decode(ARGV[3]);for k,v in pairs(s) do redis.call('hset',KEYS[3],k,v) end;return tonumber(ARGV[1])";
          Long result =
              redis.execute(
                  new DefaultRedisScript<Long>(lua, Long.class),
                  Arrays.asList(prefix + "stock", prefix + "holders", prefix + "reservations"),
                  String.valueOf(remaining),
                  Json.write(holders),
                  Json.write(states));
          Api.require(result != null && result == remaining, "RECONCILIATION_FAILED", "重建结果未确认");
          return Api.map(
              "ticketTypeId",
              type,
              "databaseAvailable",
              available,
              "pendingReservations",
              pending,
              "redisAvailable",
              remaining,
              "holders",
              holders.size());
        });
  }
}
