package io.tickmeet.catalog;

import io.tickmeet.common.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CatalogController {
  private final CatalogService c;

  public CatalogController(CatalogService s) {
    c = s;
  }

  @GetMapping("/event-categories")
  public Object categories() {
    return Api.ok(c.categories());
  }

  @GetMapping("/events")
  public Object events(
      @RequestParam(required = false) String categoryId,
      @RequestParam(required = false) String city,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String fromDate,
      @RequestParam(required = false) String toDate,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size) {
    return Api.ok(c.events(categoryId, city, keyword, status, fromDate, toDate, page, size));
  }

  @GetMapping("/events/{id}")
  public Object event(@PathVariable String id) {
    return Api.ok(c.event(id));
  }

  @GetMapping("/events/{id}/sessions")
  public Object sessions(@PathVariable String id) {
    return Api.ok(c.sessions(id));
  }

  @GetMapping("/sessions/{id}/ticket-types")
  public Object types(@PathVariable String id) {
    return Api.ok(c.types(id));
  }

  @GetMapping("/ticket-types/{id}")
  public Object type(@PathVariable String id) {
    return Api.ok(c.type(id));
  }

  @GetMapping("/venues/nearby")
  public Object nearby(
      @RequestParam String city,
      @RequestParam double longitude,
      @RequestParam double latitude,
      @RequestParam(defaultValue = "5000") double radiusMeters,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size) {
    return Api.ok(c.nearby(city, longitude, latitude, radiusMeters, page, size));
  }

  @GetMapping("/venues/{id}")
  public Object venue(@PathVariable String id) {
    return Api.ok(c.venue(id));
  }

  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  @PostMapping("/admin/venues")
  public Object newVenue(@javax.validation.Valid @RequestBody Inputs.Venue b) {
    return Api.ok(c.saveVenue(null, b.map()));
  }

  @PutMapping("/admin/venues/{id}")
  public Object editVenue(
      @PathVariable String id, @javax.validation.Valid @RequestBody Inputs.Venue b) {
    return Api.ok(c.saveVenue(id, b.map()));
  }

  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  @PostMapping("/admin/events")
  public Object newEvent(@javax.validation.Valid @RequestBody Inputs.Event b) {
    return Api.ok(c.saveEvent(null, b.map()));
  }

  @PutMapping("/admin/events/{id}")
  public Object editEvent(
      @PathVariable String id, @javax.validation.Valid @RequestBody Inputs.Event b) {
    return Api.ok(c.saveEvent(id, b.map()));
  }

  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  @PostMapping("/admin/events/{id}/sessions")
  public Object newSession(
      @PathVariable String id, @javax.validation.Valid @RequestBody Inputs.Session b) {
    return Api.ok(c.saveSession(id, null, b.map()));
  }

  @PutMapping("/admin/sessions/{id}")
  public Object editSession(
      @PathVariable String id, @javax.validation.Valid @RequestBody Inputs.Session b) {
    return Api.ok(c.saveSession(null, id, b.map()));
  }

  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  @PostMapping("/admin/sessions/{id}/ticket-types")
  public Object newType(
      @PathVariable String id, @javax.validation.Valid @RequestBody Inputs.TicketType b) {
    return Api.ok(c.saveType(id, null, b.map()));
  }

  @PutMapping("/admin/ticket-types/{id}")
  public Object editType(
      @PathVariable String id, @javax.validation.Valid @RequestBody Inputs.TicketType b) {
    return Api.ok(c.saveType(null, id, b.map()));
  }

  @PostMapping("/admin/events/{id}/publish")
  public Object publish(@PathVariable String id, @RequestHeader("Idempotency-Key") String key) {
    Api.idempotency(key);
    return Api.ok(c.publish(id));
  }

  @PutMapping("/admin/ticket-types/{id}/sale-status")
  public Object sale(@PathVariable String id, @javax.validation.Valid @RequestBody Inputs.Sale b) {
    return Api.ok(c.sale(id, Api.text(b.map(), "status")));
  }
}
