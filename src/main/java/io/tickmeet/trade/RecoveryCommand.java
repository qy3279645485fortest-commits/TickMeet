package io.tickmeet.trade;

import io.tickmeet.common.Json;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("live")
public class RecoveryCommand implements ApplicationRunner {
  private final InventoryRecovery recovery;
  private final String type;
  private final ConfigurableApplicationContext context;

  public RecoveryCommand(
      InventoryRecovery r,
      @Value("${tickmeet.recover-type:}") String t,
      ConfigurableApplicationContext c) {
    recovery = r;
    type = t;
    context = c;
  }

  public void run(ApplicationArguments args) {
    if (type.isEmpty()) return;
    try {
      System.out.println("INVENTORY_RECOVERY " + Json.write(recovery.rebuild(type)));
    } finally {
      SpringApplication.exit(context);
    }
  }
}
