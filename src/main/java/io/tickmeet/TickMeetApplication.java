package io.tickmeet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TickMeetApplication {
  public static void main(String[] args) {
    SpringApplication.run(TickMeetApplication.class, args);
  }
}
