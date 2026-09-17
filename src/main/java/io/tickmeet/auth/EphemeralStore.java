package io.tickmeet.auth;

public interface EphemeralStore {
  void put(String key, String value, int seconds);

  String get(String key);

  boolean consume(String key, String expected);

  void delete(String key);

  boolean allow(String key, int limit, int seconds);
}
