package io.tickmeet.trade;

import io.tickmeet.auth.*;
import io.tickmeet.common.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class OrderController {
  private final TradeService s;

  public OrderController(TradeService t) {
    s = t;
  }

  @PostMapping("/ticket-types/{id}/reservations")
  public Object reserve(@PathVariable String id, @RequestHeader("Idempotency-Key") String key) {
    return ResponseEntity.status(202).body(Api.ok(s.reserve(CurrentUser.id(), id, key)));
  }

  @GetMapping("/reservations/{id}")
  public Object result(@PathVariable String id) {
    return Api.ok(s.reservation(CurrentUser.id(), id));
  }

  @GetMapping("/orders")
  public Object list(
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size) {
    return Api.ok(s.orders(CurrentUser.id(), status, page, size));
  }

  @GetMapping("/orders/{id}")
  public Object order(@PathVariable String id) {
    return Api.ok(s.order(CurrentUser.id(), id));
  }

  @PostMapping("/orders/{id}/cancel")
  public Object cancel(@PathVariable String id) {
    return Api.ok(s.cancel(CurrentUser.id(), id));
  }
}
