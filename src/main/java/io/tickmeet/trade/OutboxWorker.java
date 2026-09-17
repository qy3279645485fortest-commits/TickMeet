package io.tickmeet.trade;

import io.tickmeet.common.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxWorker {
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private io.tickmeet.cache.RedisFeatures features;

  private final TradeService trade;
  private final boolean enabled;
  private final org.springframework.beans.factory.ObjectProvider<EventPublisher> publisher;

  public OutboxWorker(
      TradeService t,
      @Value("${tickmeet.scheduling:true}") boolean e,
      org.springframework.beans.factory.ObjectProvider<EventPublisher> p) {
    trade = t;
    enabled = e;
    publisher = p;
  }

  @Scheduled(fixedDelay = 500)
  public void scheduled() {
    if (enabled) drain();
  }

  public void drain() {
    for (Map<String, Object> e :
        trade.db.list(
            "SELECT * FROM tm_outbox WHERE status='PENDING' AND next_at<=? ORDER BY created_at LIMIT 100",
            System.currentTimeMillis())) {
      String id = Db.s(e, "id");
      if (trade.db.update(
              "UPDATE tm_outbox SET status='SENDING' WHERE id=? AND status='PENDING'", id)
          != 1) continue;
      try {
        EventPublisher pub = publisher.getIfAvailable();
        if (pub != null) pub.publish(e);
        else dispatch(Db.s(e, "kind"), Db.s(e, "aggregate_id"));
        trade.db.update("UPDATE tm_outbox SET status='SENT' WHERE id=?", id);
      } catch (Exception ex) {
        trade.db.update(
            "UPDATE tm_outbox SET status='PENDING',attempts=attempts+1,next_at=? WHERE id=?",
            System.currentTimeMillis() + 2000,
            id);
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Outbox retry event={}", id, ex);
      }
    }
  }

  public void dispatch(String kind, String id) {
    if ("CREATE_ORDER".equals(kind)) trade.createOrder(id);
    else if ("ORDER_TIMEOUT".equals(kind)) trade.close(id, true);
    else if ("RELEASE".equals(kind)) trade.release(id);
    else if ("POST_PUBLISHED".equals(kind) && features != null) features.postPublished(id);
  }

  @Scheduled(fixedDelay = 5000)
  public void recover() {
    if (enabled) {
      trade.recover();
      trade.db.update(
          "UPDATE tm_outbox SET status='PENDING' WHERE status='SENDING' AND next_at<?",
          System.currentTimeMillis() - 30000);
    }
  }
}
