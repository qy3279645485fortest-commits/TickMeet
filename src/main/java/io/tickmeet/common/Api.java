package io.tickmeet.common;

import java.util.*;

public class Api {
  public static Map<String, Object> ok(Object data) {
    return map("success", true, "code", "OK", "errorMsg", null, "data", data, "total", null);
  }

  public static Map<String, Object> map(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put(kv[i].toString(), kv[i + 1]);
    return m;
  }

  public static void require(boolean condition, String code, String message) {
    if (!condition)
      throw new Problem(
          "INVALID_ARGUMENT".equals(code) ? 400 : "FORBIDDEN".equals(code) ? 403 : 409,
          code,
          message);
  }

  public static String text(Map<String, Object> b, String k) {
    Object v = b.get(k);
    if (!(v instanceof String) || ((String) v).trim().isEmpty())
      throw new Problem(400, "INVALID_ARGUMENT", k + "不能为空");
    return ((String) v).trim();
  }

  public static String optional(Map<String, Object> b, String k, String fallback) {
    return b.get(k) == null ? fallback : b.get(k).toString().trim();
  }

  public static long number(Map<String, Object> b, String k) {
    try {
      return Long.parseLong(textValue(b, k));
    } catch (Exception ex) {
      throw new Problem(400, "INVALID_ARGUMENT", k + "必须是整数");
    }
  }

  private static String textValue(Map<String, Object> b, String k) {
    if (b.get(k) == null) throw new IllegalArgumentException();
    return b.get(k).toString();
  }

  public static boolean bool(Map<String, Object> b, String k) {
    if (!(b.get(k) instanceof Boolean)) throw new Problem(400, "INVALID_ARGUMENT", k + "必须是布尔值");
    return (Boolean) b.get(k);
  }

  public static String idempotency(String key) {
    if (key == null || !key.matches("[A-Za-z0-9_-]{1,64}"))
      throw new Problem(400, "INVALID_ARGUMENT", "需要有效的Idempotency-Key");
    return key;
  }

  public static int page(int p) {
    if (p < 1) throw new Problem(400, "INVALID_ARGUMENT", "页码必须大于0");
    return p;
  }

  public static int size(int s) {
    if (s < 1 || s > 50) throw new Problem(400, "INVALID_ARGUMENT", "每页条数1—50");
    return s;
  }

  public static Map<String, Object> page(List<?> all, int page, int size) {
    page(page);
    size(size);
    int start = (int) Math.min(all.size(), ((long) page - 1) * size);
    return map(
        "items",
        all.subList(start, Math.min(all.size(), start + size)),
        "page",
        page,
        "size",
        size,
        "total",
        all.size());
  }
}
