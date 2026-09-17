package io.tickmeet.trade;

import io.tickmeet.common.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@Profile("live")
public class RedisInventory implements Inventory {
  private final StringRedisTemplate redis;
  private final Db db;
  private final DefaultRedisScript<String> hold;
  private final DefaultRedisScript<Long> release;

  public RedisInventory(StringRedisTemplate r, Db d) {
    redis = r;
    db = d;
    hold = new DefaultRedisScript<>();
    hold.setLocation(new ClassPathResource("lua/reserve.lua"));
    hold.setResultType(String.class);
    release = new DefaultRedisScript<>();
    release.setLocation(new ClassPathResource("lua/release.lua"));
    release.setResultType(Long.class);
  }

  private List<String> keys(String type) {
    String p = "tickmeet:ticket:{" + type + "}:";
    return Arrays.asList(p + "stock", p + "holders", p + "reservations");
  }

  public void initialize(String type, long stock) {
    if (db.count("SELECT COUNT(*) FROM tm_reservation WHERE ticket_type_id=?", type) > 0)
      throw new Problem(409, "STOCK_NOT_READY", "已有交易的票档必须通过恢复流程初始化");
    redis.opsForValue().set(keys(type).get(0), String.valueOf(stock));
  }

  public String hold(Map<String, Object> t, String user, String reservation) {
    String id = Db.s(t, "id");
    List<String> k = keys(id);
    if (!Boolean.TRUE.equals(redis.hasKey(k.get(0))))
      throw new Problem(503, "STOCK_NOT_READY", "库存尚未初始化或需要核对恢复");
    String result =
        redis.execute(
            hold,
            k,
            user,
            reservation,
            String.valueOf(System.currentTimeMillis()),
            String.valueOf(t.get("sale_start")),
            String.valueOf(t.get("sale_end")),
            String.valueOf(t.get("status")));
    if (result == null) throw new Problem(503, "DEPENDENCY_UNAVAILABLE", "库存服务未响应");
    return result;
  }

  public void release(String type, String user, String reservation) {
    Long r = redis.execute(release, keys(type), user, reservation);
    if (r != null && r < 0) throw new Problem(503, "STOCK_NOT_READY", "库存恢复未完成");
  }
}
