package io.tickmeet.common;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Db {
  public final JdbcTemplate jdbc;

  public Db(JdbcTemplate j) {
    jdbc = j;
  }

  public int update(String sql, Object... a) {
    return jdbc.update(sql, a);
  }

  public List<Map<String, Object>> list(String sql, Object... a) {
    return jdbc.queryForList(sql, a);
  }

  public Map<String, Object> one(String sql, Object... a) {
    List<Map<String, Object>> r = list(sql, a);
    return r.isEmpty() ? null : r.get(0);
  }

  public Map<String, Object> must(String sql, Object... a) {
    Map<String, Object> r = one(sql, a);
    if (r == null) throw new Problem(404, "NOT_FOUND", "资源不存在");
    return r;
  }

  public long count(String sql, Object... a) {
    Long n = jdbc.queryForObject(sql, Long.class, a);
    return n == null ? 0 : n;
  }

  public static String id() {
    return java.util.UUID.randomUUID().toString().replace("-", "");
  }

  public static String s(Map<String, Object> r, String k) {
    return r.get(k) == null ? null : r.get(k).toString();
  }

  public static long n(Map<String, Object> r, String k) {
    return ((Number) r.get(k)).longValue();
  }
}
