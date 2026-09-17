package io.tickmeet.trade;

import io.micrometer.core.instrument.*;
import io.tickmeet.common.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TradeMetrics {
  private final Db db;
  private final AtomicLong pending = new AtomicLong(),
      oldest = new AtomicLong(),
      outbox = new AtomicLong(),
      outboxAge = new AtomicLong();

  public TradeMetrics(Db d, MeterRegistry r) {
    db = d;
    r.gauge("ticket.reservation.pending", pending);
    r.gauge("ticket.reservation.oldest.pending.age.seconds", oldest);
    r.gauge("ticket.outbox.pending", outbox);
    r.gauge("ticket.outbox.oldest.age.seconds", outboxAge);
    Timer.builder("ticket.order.creation").publishPercentileHistogram().register(r);
  }

  @Scheduled(fixedDelay = 5000)
  public void sample() {
    try {
      pending.set(
          db.count("SELECT COUNT(*) FROM tm_reservation WHERE state IN ('INIT','RESERVED')"));
      long first =
          db.count(
              "SELECT COALESCE(MIN(created_at),0) FROM tm_reservation WHERE state IN ('INIT','RESERVED')");
      oldest.set(first == 0 ? 0 : (System.currentTimeMillis() - first) / 1000);
      outbox.set(
          db.count(
              "SELECT COUNT(*) FROM tm_outbox WHERE status<>'SENT' AND next_at<=?",
              System.currentTimeMillis()));
      long f =
          db.count(
              "SELECT COALESCE(MIN(created_at),0) FROM tm_outbox WHERE status<>'SENT' AND next_at<=?",
              System.currentTimeMillis());
      outboxAge.set(f == 0 ? 0 : (System.currentTimeMillis() - f) / 1000);
    } catch (RuntimeException ignored) {
    }
  }
}
