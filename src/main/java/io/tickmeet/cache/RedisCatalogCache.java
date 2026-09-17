package io.tickmeet.cache;

import io.tickmeet.common.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javax.annotation.PreDestroy;
import org.redisson.Redisson;
import org.redisson.api.*;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;

/** Cache only display data. Admission, price and inventory always use authoritative records. */
@Component
@Profile("live")
public class RedisCatalogCache {
  private final StringRedisTemplate redis;
  private final RedissonClient locks;
  private final ExecutorService refresh = Executors.newFixedThreadPool(2);

  public RedisCatalogCache(StringRedisTemplate r, RedisProperties properties) {
    redis = r;
    Config config = new Config();
    if (properties.getSentinel() != null) {
      org.redisson.config.SentinelServersConfig c =
          config
              .useSentinelServers()
              .setMasterName(properties.getSentinel().getMaster())
              .setDatabase(properties.getDatabase());
      for (String node : properties.getSentinel().getNodes())
        c.addSentinelAddress("redis://" + node);
      if (properties.getPassword() != null) c.setPassword(properties.getPassword());
      if (properties.getSentinel().getPassword() != null)
        c.setSentinelPassword(properties.getSentinel().getPassword());
    } else {
      org.redisson.config.SingleServerConfig c =
          config
              .useSingleServer()
              .setAddress("redis://" + properties.getHost() + ":" + properties.getPort())
              .setDatabase(properties.getDatabase())
              .setConnectionPoolSize(16)
              .setConnectionMinimumIdleSize(2);
      if (properties.getPassword() != null) c.setPassword(properties.getPassword());
    }
    locks = Redisson.create(config);
  }

  public Map<String, Object> read(String type, String id, Supplier<Map<String, Object>> loader) {
    String key = "tickmeet:cache:" + type + ":" + id;
    try {
      String raw = redis.opsForValue().get(key);
      if (raw != null) {
        Map<String, Object> envelope = Json.object(raw);
        if (Db.n(envelope, "expires") < System.currentTimeMillis())
          refresh.submit(() -> rebuild(key, loader, false));
        return data(envelope);
      }
      return rebuild(key, loader, true);
    } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
      return loader.get(); // Read-only display degradation; no write/auth bypass.
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> data(Map<String, Object> envelope) {
    return (Map<String, Object>) envelope.get("data");
  }

  private Map<String, Object> rebuild(
      String key, Supplier<Map<String, Object>> loader, boolean wait) {
    RLock lock = locks.getLock(key + ":lock");
    boolean acquired = false;
    try {
      acquired = lock.tryLock(wait ? 2 : 0, 10, TimeUnit.SECONDS);
      if (!acquired) {
        String raw = redis.opsForValue().get(key);
        if (raw != null) return data(Json.object(raw));
        throw new Problem(503, "CACHE_BUSY", "活动信息正在加载，请稍后重试");
      }
      String raw = redis.opsForValue().get(key);
      if (raw != null) {
        Map<String, Object> envelope = Json.object(raw);
        if (Db.n(envelope, "expires") > System.currentTimeMillis()) return data(envelope);
      }
      Map<String, Object> value = loader.get();
      int ttl = value == null ? 30 : 300 + ThreadLocalRandom.current().nextInt(60);
      redis
          .opsForValue()
          .set(
              key,
              Json.write(Api.map("data", value, "expires", System.currentTimeMillis() + 30000)),
              ttl,
              TimeUnit.SECONDS);
      return value;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Problem(503, "CACHE_BUSY", "加载中断");
    } finally {
      if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
    }
  }

  public void invalidate(String type, String id) {
    Runnable delete = () -> redis.delete("tickmeet:cache:" + type + ":" + id);
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            public void afterCommit() {
              try {
                delete.run();
              } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn("Cache invalidation failed; bounded TTL will refresh");
              }
            }
          });
    else delete.run();
  }

  @PreDestroy
  public void close() {
    refresh.shutdownNow();
    locks.shutdown();
  }
}
