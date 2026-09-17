package io.tickmeet.auth;

import java.util.concurrent.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"demo", "test"})
public class LocalStore implements EphemeralStore {
  private static class Entry {
    String value;
    long expires;

    Entry(String v, long e) {
      value = v;
      expires = e;
    }
  }

  private final ConcurrentHashMap<String, Entry> data = new ConcurrentHashMap<>();

  public synchronized void put(String k, String v, int seconds) {
    data.put(k, new Entry(v, System.currentTimeMillis() + seconds * 1000L));
  }

  public synchronized String get(String k) {
    Entry e = data.get(k);
    if (e == null) return null;
    if (e.expires <= System.currentTimeMillis()) {
      data.remove(k);
      return null;
    }
    return e.value;
  }

  public synchronized boolean consume(String k, String expected) {
    String v = get(k);
    if (v == null || !v.equals(expected)) return false;
    data.remove(k);
    return true;
  }

  public void delete(String k) {
    data.remove(k);
  }

  public synchronized boolean allow(String k, int limit, int seconds) {
    String v = get(k);
    if (v == null) {
      put(k, "1", seconds);
      return true;
    }
    Entry e = data.get(k);
    int n = Integer.parseInt(v) + 1;
    e.value = String.valueOf(n);
    return n <= limit;
  }
}
