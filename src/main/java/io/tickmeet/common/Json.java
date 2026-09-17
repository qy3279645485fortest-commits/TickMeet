package io.tickmeet.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

public class Json {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public static String write(Object v) {
    try {
      return MAPPER.writeValueAsString(v);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  public static List<String> strings(Object v) {
    if (v == null) return new ArrayList<>();
    if (v instanceof List) return (List<String>) v;
    try {
      return MAPPER.readValue(v.toString(), List.class);
    } catch (Exception e) {
      throw new Problem(400, "INVALID_ARGUMENT", "图片列表格式不正确");
    }
  }

  public static Map<String, Object> object(String s) {
    try {
      return MAPPER.readValue(s, Map.class);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }
}
