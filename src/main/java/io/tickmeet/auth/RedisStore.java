package io.tickmeet.auth;

import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@Profile("live")
public class RedisStore implements EphemeralStore {
  private final StringRedisTemplate redis;
  private static final String PREFIX = "tickmeet:";

  public RedisStore(StringRedisTemplate r) {
    redis = r;
  }

  public void put(String key, String value, int seconds) {
    if (key.startsWith("token:")) {
      String session = PREFIX + "session:" + key.substring(6);
      redis.execute(
          new DefaultRedisScript<Long>(
              "redis.call('hset',KEYS[1],'userId',ARGV[1]);redis.call('expire',KEYS[1],ARGV[2]);return 1",
              Long.class),
          Collections.singletonList(session),
          value,
          String.valueOf(seconds));
    } else redis.opsForValue().set(PREFIX + key, value, seconds, TimeUnit.SECONDS);
  }

  public String get(String key) {
    if (key.startsWith("token:")) {
      Object id = redis.opsForHash().get(PREFIX + "session:" + key.substring(6), "userId");
      return id == null ? null : id.toString();
    }
    return redis.opsForValue().get(PREFIX + key);
  }

  public void delete(String key) {
    redis.delete(PREFIX + (key.startsWith("token:") ? "session:" + key.substring(6) : key));
  }

  public boolean consume(String key, String expected) {
    return Long.valueOf(1)
        .equals(
            redis.execute(
                new DefaultRedisScript<Long>(
                    "if redis.call('get',KEYS[1])==ARGV[1] then redis.call('del',KEYS[1]); return 1 else return 0 end",
                    Long.class),
                Collections.singletonList(PREFIX + key),
                expected));
  }

  public boolean allow(String key, int limit, int seconds) {
    Long n =
        redis.execute(
            new DefaultRedisScript<Long>(
                "local n=redis.call('incr',KEYS[1]); if n==1 then redis.call('expire',KEYS[1],ARGV[1]) end; return n",
                Long.class),
            Collections.singletonList(PREFIX + "limit:" + key),
            String.valueOf(seconds));
    return n != null && n <= limit;
  }
}
