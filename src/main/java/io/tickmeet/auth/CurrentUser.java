package io.tickmeet.auth;

import io.tickmeet.common.*;
import java.util.*;

public class CurrentUser {
  private static final ThreadLocal<Map<String, Object>> LOCAL = new ThreadLocal<>();

  public static void set(Map<String, Object> u) {
    LOCAL.set(u);
  }

  public static void clear() {
    LOCAL.remove();
  }

  public static boolean authenticated() { return LOCAL.get() != null; }
  public static String id() {
    if (LOCAL.get() == null) throw new Problem(401, "UNAUTHORIZED", "请先登录");
    return Db.s(LOCAL.get(), "id");
  }

  public static boolean is(String role) {
    return LOCAL.get() != null && role.equals(LOCAL.get().get("role"));
  }

  public static void role(String role) {
    id();
    if (!is(role)) throw new Problem(403, "FORBIDDEN", "当前账号没有此操作权限");
  }

  public static Map<String, Object> get() {
    id();
    return LOCAL.get();
  }
}
