package io.tickmeet.trade;

import io.tickmeet.common.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.Component;

@Configuration
@Profile("live")
public class RabbitEvents {
  @org.springframework.beans.factory.annotation.Value("${tickmeet.mq-prefix:tickmeet}")
  private String prefix;

  @Bean
  org.springframework.amqp.core.Queue eventQueue() {
    return QueueBuilder.durable(prefix + ".events")
        .deadLetterExchange(prefix + ".dead")
        .deadLetterRoutingKey("failed")
        .build();
  }

  @Bean
  org.springframework.amqp.core.Queue timeoutQueue() {
    return QueueBuilder.durable(prefix + ".timeout")
        .deadLetterExchange(prefix + ".exchange")
        .deadLetterRoutingKey("event")
        .build();
  }

  @Bean
  Binding timeoutBinding() {
    return BindingBuilder.bind(timeoutQueue()).to(events()).with("timeout");
  }

  @Bean
  org.springframework.amqp.core.Queue deadQueue() {
    return QueueBuilder.durable(prefix + ".failed").build();
  }

  @Bean
  DirectExchange events() {
    return new DirectExchange(prefix + ".exchange");
  }

  @Bean
  DirectExchange dead() {
    return new DirectExchange(prefix + ".dead");
  }

  @Bean
  Binding binding() {
    return BindingBuilder.bind(eventQueue()).to(events()).with("event");
  }

  @Bean
  Binding deadBinding() {
    return BindingBuilder.bind(deadQueue()).to(dead()).with("failed");
  }

  @Bean
  EventPublisher publisher(RabbitTemplate template, Db db) {
    template.setMandatory(true);
    return e -> {
      CorrelationData correlation = new CorrelationData(Db.s(e, "id"));
      Message m =
          MessageBuilder.withBody(Json.write(e).getBytes(java.nio.charset.StandardCharsets.UTF_8))
              .setContentType("application/json")
              .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
              .build();
      String route = "event";
      if ("ORDER_TIMEOUT".equals(e.get("kind"))) {
        long deadline =
            db.count("SELECT expire_at FROM tm_order WHERE id=?", e.get("aggregate_id"));
        m.getMessageProperties()
            .setExpiration(String.valueOf(Math.max(1, deadline - System.currentTimeMillis())));
        route = "timeout";
      }
      template.send(prefix + ".exchange", route, m, correlation);
      CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
      if (!confirm.isAck() || correlation.getReturned() != null)
        throw new IllegalStateException("Broker did not accept routed event");
    };
  }

  @Component
  @Profile("live")
  public static class Consumer {
    private final OutboxWorker worker;

    public Consumer(OutboxWorker w) {
      worker = w;
    }

    @RabbitListener(queues = "${tickmeet.mq-prefix:tickmeet}.events")
    public void receive(Message message) {
      Map<String, Object> e =
          Json.object(new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8));
      worker.dispatch(Db.s(e, "kind"), Db.s(e, "aggregate_id"));
    }
  }
}
