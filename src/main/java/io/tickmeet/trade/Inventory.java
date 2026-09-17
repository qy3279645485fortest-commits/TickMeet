package io.tickmeet.trade;

import java.util.*;

public interface Inventory {
  default void initialize(String type, long stock) {}

  String hold(Map<String, Object> type, String user, String reservation);

  void release(String type, String user, String reservation);
}
