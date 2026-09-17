package io.tickmeet.cache;

import io.tickmeet.common.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Component;

@Component
@Profile("live")
public class RedisFeatures {
  private final Db db;
  private final StringRedisTemplate r;

  public RedisFeatures(Db d, StringRedisTemplate redis) {
    db = d;
    r = redis;
  }

  public void postPublished(String id) {
    Map<String, Object> p = db.must("SELECT * FROM tm_post WHERE id=?", id);
    for (Map<String, Object> f :
        db.list("SELECT user_id FROM tm_follow WHERE follow_user_id=?", p.get("user_id")))
      r.opsForZSet().add("tickmeet:feed:" + f.get("user_id"), id, Db.n(p, "created_at"));
  }

  public void follows(String user) {
    String key = "tickmeet:follows:" + user;
    List<Map<String, Object>> rows =
        db.list("SELECT follow_user_id FROM tm_follow WHERE user_id=?", user);
    r.delete(key);
    for (Map<String, Object> row : rows) r.opsForSet().add(key, Db.s(row, "follow_user_id"));
  }

  public Set<String> common(String a, String b) {
    follows(a);
    follows(b);
    return r.opsForSet().intersect("tickmeet:follows:" + a, "tickmeet:follows:" + b);
  }

  public void sign(String user) {
    LocalDate day = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    r.opsForValue()
        .setBit(
            "tickmeet:sign:" + user + ":" + day.format(DateTimeFormatter.ofPattern("yyyyMM")),
            day.getDayOfMonth() - 1,
            true);
  }

  public int signs(String user) {
    LocalDate day = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    String key = "tickmeet:sign:" + user + ":" + day.format(DateTimeFormatter.ofPattern("yyyyMM"));
    for (Map<String, Object> row :
        db.list(
            "SELECT sign_date FROM tm_sign WHERE user_id=? AND sign_date LIKE ?",
            user,
            day.toString().substring(0, 7) + "%"))
      r.opsForValue()
          .setBit(key, LocalDate.parse(Db.s(row, "sign_date")).getDayOfMonth() - 1, true);
    List<Long> values =
        r.opsForValue()
            .bitField(
                key,
                BitFieldSubCommands.create()
                    .get(BitFieldSubCommands.BitFieldType.unsigned(day.getDayOfMonth()))
                    .valueAt(0));
    long n = values == null || values.isEmpty() ? 0 : values.get(0);
    int count = 0;
    while ((n & 1) == 1) {
      count++;
      n >>>= 1;
    }
    return count;
  }

  public Set<String> likes(String post) {
    String key = "tickmeet:post:liked:" + post;
    r.delete(key);
    for (Map<String, Object> row :
        db.list("SELECT * FROM tm_like WHERE post_id=? ORDER BY liked_at,user_id", post))
      r.opsForZSet().add(key, Db.s(row, "user_id"), Db.n(row, "liked_at"));
    return r.opsForZSet().range(key, 0, 4);
  }

  public Set<ZSetOperations.TypedTuple<String>> feed(String user, long max, int offset) {
    return r.opsForZSet()
        .reverseRangeByScoreWithScores("tickmeet:feed:" + user, 0, max, offset, 10);
  }

  public List<Map<String, Object>> feedRows(String user, long max) {
    String key = "tickmeet:feed:" + user;
    List<Map<String, Object>> all =
        db.list(
            "SELECT p.* FROM tm_post p JOIN tm_follow f ON p.user_id=f.follow_user_id WHERE f.user_id=?",
            user);
    Set<String> allowed = new HashSet<>();
    for (Map<String, Object> p : all) {
      allowed.add(Db.s(p, "id"));
      r.opsForZSet().add(key, Db.s(p, "id"), Db.n(p, "created_at"));
    }
    Set<String> ids = r.opsForZSet().reverseRangeByScore(key, 0, max);
    List<Map<String, Object>> out = new ArrayList<>();
    if (ids != null)
      for (String id : ids)
        if (allowed.contains(id)) {
          Map<String, Object> p = db.one("SELECT * FROM tm_post WHERE id=?", id);
          if (p != null) out.add(p);
        }
    r.expire(key, 7, java.util.concurrent.TimeUnit.DAYS);
    return out;
  }

  public List<Map<String, Object>> nearby(
      String city, double longitude, double latitude, double radius) {
    String key = "tickmeet:geo:" + city;
    for (Map<String, Object> v : db.list("SELECT * FROM tm_venue WHERE city=?", city))
      r.opsForGeo()
          .add(
              key,
              new Point(
                  ((Number) v.get("longitude")).doubleValue(),
                  ((Number) v.get("latitude")).doubleValue()),
              Db.s(v, "id"));
    GeoResults<RedisGeoCommands.GeoLocation<String>> result =
        r.opsForGeo()
            .search(
                key,
                GeoReference.fromCoordinate(longitude, latitude),
                new Distance(radius / 1000, Metrics.KILOMETERS),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                    .includeDistance()
                    .sortAscending());
    List<Map<String, Object>> out = new ArrayList<>();
    if (result != null)
      for (GeoResult<RedisGeoCommands.GeoLocation<String>> v : result)
        out.add(
            Api.map(
                "id",
                v.getContent().getName(),
                "distance",
                Math.round(v.getDistance().getValue() * 1000)));
    return out;
  }
}
