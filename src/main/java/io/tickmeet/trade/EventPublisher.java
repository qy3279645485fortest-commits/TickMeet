package io.tickmeet.trade;

import java.util.Map;

public interface EventPublisher {
  void publish(Map<String, Object> event) throws Exception;
}
