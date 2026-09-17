package io.tickmeet.catalog;

import io.tickmeet.auth.*;
import io.tickmeet.common.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private io.tickmeet.cache.RedisFeatures features;

  @org.springframework.beans.factory.annotation.Autowired
  private io.tickmeet.trade.Inventory inventory;

  @org.springframework.beans.factory.annotation.Autowired
  private io.tickmeet.files.FileService files;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private io.tickmeet.cache.RedisCatalogCache cache;

  public final Db db;

  public Db database() {
    return db;
  }

  public CatalogService(Db d) {
    db = d;
  }

  private Map<String, Object> raw(String type, String id) {
    Map<String, Object> v =
        cache == null
                || org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive()
            ? db.one("SELECT * FROM tm_" + type + " WHERE id=?", id)
            : cache.read(type, id, () -> db.one("SELECT * FROM tm_" + type + " WHERE id=?", id));
    if (v == null) throw new Problem(404, "NOT_FOUND", "内容不存在");
    return v;
  }

  private void invalid(String type, String id) {
    if (cache != null) cache.invalidate(type, id);
  }

  public Object categories() {
    return cache == null
        ? db.list("SELECT * FROM tm_category ORDER BY sort,id")
        : cache
            .read(
                "category",
                "all",
                () -> Api.map("items", db.list("SELECT * FROM tm_category ORDER BY sort,id")))
            .get("items");
  }

  public static long time(Map<String, Object> b, String k) {
    try {
      return OffsetDateTime.parse(Api.text(b, k)).toInstant().toEpochMilli();
    } catch (Exception e) {
      throw new Problem(400, "INVALID_ARGUMENT", k + "需要带时区的ISO日期");
    }
  }

  public static String iso(Object n) {
    return n == null
        ? null
        : Instant.ofEpochMilli(((Number) n).longValue()).atOffset(ZoneOffset.ofHours(8)).toString();
  }

  public Map<String, Object> venue(String id) {
    Map<String, Object> v = raw("venue", id);
    return Api.map(
        "id",
        id,
        "name",
        v.get("name"),
        "city",
        v.get("city"),
        "address",
        v.get("address"),
        "longitude",
        v.get("longitude"),
        "latitude",
        v.get("latitude"),
        "description",
        v.get("description"),
        "images",
        Json.strings(v.get("images")));
  }

  @Transactional
  public Map<String, Object> saveVenue(String id, Map<String, Object> b) {
    double lon = coordinate(b, "longitude", -180, 180), lat = coordinate(b, "latitude", -90, 90);
    String name = Api.text(b, "name"), city = Api.text(b, "city"), address = Api.text(b, "address");
    if (id == null) {
      id = Db.id();
      db.update(
          "INSERT INTO tm_venue(id,name,city,address,longitude,latitude,description,images) VALUES(?,?,?,?,?,?,?,?)",
          id,
          name,
          city,
          address,
          lon,
          lat,
          Api.optional(b, "description", ""),
          Json.write(Collections.emptyList()));
    } else {
      Map<String, Object> old = db.must("SELECT * FROM tm_venue WHERE id=? FOR UPDATE", id);
      if (db.count("SELECT COUNT(*) FROM tm_event WHERE venue_id=? AND status<>'DRAFT'", id) > 0)
        Api.require(
            address.equals(old.get("address"))
                && lon == ((Number) old.get("longitude")).doubleValue()
                && lat == ((Number) old.get("latitude")).doubleValue(),
            "ORDER_STATE_CONFLICT",
            "已发布活动的场馆地址不可修改");
      db.update(
          "UPDATE tm_venue SET name=?,city=?,address=?,longitude=?,latitude=?,description=? WHERE id=?",
          name,
          city,
          address,
          lon,
          lat,
          Api.optional(b, "description", ""),
          id);
    }
    if (b.containsKey("imageFileIds")) {
      List<String> images = Json.strings(b.get("imageFileIds"));
      files.bind(CurrentUser.id(), "venue:" + id, images);
      db.update("UPDATE tm_venue SET images=? WHERE id=?", Json.write(urls(images)), id);
    }
    invalid("venue", id);
    return venue(id);
  }

  private List<String> urls(List<String> ids) {
    return ids.stream().map(id -> "/api/files/" + id + "/content").collect(Collectors.toList());
  }

  private double coordinate(Map<String, Object> b, String k, double min, double max) {
    try {
      double n = Double.parseDouble(b.get(k).toString());
      if (!Double.isFinite(n) || n < min || n > max) throw new Exception();
      return n;
    } catch (Exception e) {
      throw new Problem(400, "INVALID_ARGUMENT", k + "超出范围");
    }
  }

  public Map<String, Object> event(String id) {
    Map<String, Object> e = raw("event", id);
    visible(e);
    return eventView(e);
  }

  private void visible(Map<String, Object> e) {
    if (!"PUBLISHED".equals(e.get("status")) && !CurrentUser.is("ADMIN"))
      throw new Problem(404, "NOT_FOUND", "活动尚未发布");
  }

  public Map<String, Object> eventView(Map<String, Object> e) {
    String id = Db.s(e, "id");
    Map<String, Object> v = venue(Db.s(e, "venue_id"));
    Map<String, Object> agg =
        db.one(
            "SELECT MIN(t.price_cent) AS price,MIN(s.start_at) AS next_time FROM tm_session s LEFT JOIN tm_ticket_type t ON s.id=t.session_id WHERE s.event_id=?",
            id);
    return Api.map(
        "id",
        id,
        "title",
        e.get("title"),
        "categoryId",
        e.get("category_id"),
        "venueId",
        e.get("venue_id"),
        "description",
        e.get("description"),
        "cover",
        e.get("cover"),
        "images",
        Json.strings(e.get("images")),
        "status",
        e.get("status"),
        "refundAllowed",
        e.get("refund_allowed"),
        "refundDeadlineAt",
        iso(e.get("refund_deadline")),
        "theme",
        e.get("theme"),
        "venue",
        v,
        "minPriceCent",
        agg.get("price"),
        "nextSessionAt",
        iso(agg.get("next_time")));
  }

  public Map<String, Object> events(
      String category,
      String city,
      String keyword,
      String status,
      String from,
      String to,
      int page,
      int size) {
    List<Map<String, Object>> rows = db.list("SELECT * FROM tm_event ORDER BY created_at DESC,id");
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> e : rows) {
      if (!CurrentUser.is("ADMIN") && !"PUBLISHED".equals(e.get("status"))) continue;
      if (category != null && !category.equals(e.get("category_id"))) continue;
      if (status != null && !status.equals(e.get("status"))) continue;
      if (keyword != null && !Db.s(e, "title").toLowerCase().contains(keyword.toLowerCase()))
        continue;
      Map<String, Object> v = venue(Db.s(e, "venue_id"));
      if (city != null && !city.equals(v.get("city"))) continue;
      if (from != null || to != null) {
        long low =
            from == null
                ? 0
                : LocalDate.parse(from)
                    .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                    .toInstant()
                    .toEpochMilli();
        long high =
            to == null
                ? Long.MAX_VALUE
                : LocalDate.parse(to)
                    .plusDays(1)
                    .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                    .toInstant()
                    .toEpochMilli();
        if (db.count(
                "SELECT COUNT(*) FROM tm_session WHERE event_id=? AND start_at>=? AND start_at<?",
                e.get("id"),
                low,
                high)
            == 0) continue;
      }
      out.add(eventView(e));
    }
    out.sort(Comparator.comparing(x -> String.valueOf(x.get("nextSessionAt"))));
    return Api.page(out, page, size);
  }

  @Transactional
  public Map<String, Object> saveEvent(String id, Map<String, Object> b) {
    String title = Api.text(b, "title"),
        cat = Api.text(b, "categoryId"),
        venue = Api.text(b, "venueId"),
        desc = Api.text(b, "description");
    db.must("SELECT * FROM tm_category WHERE id=?", cat);
    db.must("SELECT * FROM tm_venue WHERE id=?", venue);
    boolean refund = Api.bool(b, "refundAllowed");
    Long deadline = refund ? time(b, "refundDeadlineAt") : null;
    if (id == null) {
      id = Db.id();
      db.update(
          "INSERT INTO tm_event(id,category_id,venue_id,title,description,cover,images,status,refund_allowed,refund_deadline,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
          id,
          cat,
          venue,
          title,
          desc,
          "",
          "[]",
          "DRAFT",
          refund,
          deadline,
          System.currentTimeMillis());
    } else {
      Map<String, Object> old = db.must("SELECT * FROM tm_event WHERE id=? FOR UPDATE", id);
      if (!"DRAFT".equals(old.get("status")))
        Api.require(
            title.equals(old.get("title"))
                && cat.equals(old.get("category_id"))
                && venue.equals(old.get("venue_id"))
                && refund == Boolean.TRUE.equals(old.get("refund_allowed"))
                && Objects.equals(deadline, old.get("refund_deadline")),
            "ORDER_STATE_CONFLICT",
            "发布后的权益信息不能更改");
      db.update(
          "UPDATE tm_event SET title=?,category_id=?,venue_id=?,description=?,refund_allowed=?,refund_deadline=? WHERE id=?",
          title,
          cat,
          venue,
          desc,
          refund,
          deadline,
          id);
    }
    if (b.containsKey("imageFileIds") || b.containsKey("coverFileId")) {
      List<String> images = Json.strings(b.get("imageFileIds"));
      String cover = Api.optional(b, "coverFileId", "");
      List<String> all = new ArrayList<>(images);
      if (!cover.isEmpty()) all.add(cover);
      files.bind(CurrentUser.id(), "event:" + id, all);
      db.update(
          "UPDATE tm_event SET images=?,cover=? WHERE id=?",
          Json.write(urls(images)),
          cover.isEmpty() ? "" : "/api/files/" + cover + "/content",
          id);
    }
    invalid("event", id);
    return eventView(db.must("SELECT * FROM tm_event WHERE id=?", id));
  }

  private Map<String, Object> draft(String id) {
    Map<String, Object> e = db.must("SELECT * FROM tm_event WHERE id=? FOR UPDATE", id);
    Api.require("DRAFT".equals(e.get("status")), "ORDER_STATE_CONFLICT", "只能修改草稿活动");
    return e;
  }

  @Transactional
  public Map<String, Object> saveSession(String eventId, String id, Map<String, Object> b) {
    if (id != null) eventId = Db.s(db.must("SELECT * FROM tm_session WHERE id=?", id), "event_id");
    draft(eventId);
    long start = time(b, "startAt"),
        end = time(b, "endAt"),
        entry = time(b, "entryStartAt"),
        entryEnd = time(b, "entryEndAt"),
        cap = Api.number(b, "capacity");
    Api.require(
        start < end && entry <= entryEnd && entryEnd <= end && cap > 0 && cap <= 1000000,
        "INVALID_ARGUMENT",
        "场次时间或容量不合法");
    String name = Api.text(b, "name");
    if (id == null) {
      id = Db.id();
      db.update(
          "INSERT INTO tm_session(id,event_id,name,start_at,end_at,entry_start,entry_end,capacity) VALUES(?,?,?,?,?,?,?,?)",
          id,
          eventId,
          name,
          start,
          end,
          entry,
          entryEnd,
          cap);
    } else {
      db.must("SELECT * FROM tm_session WHERE id=? FOR UPDATE", id);
      long allocated =
          db.count(
              "SELECT COALESCE(SUM(stock_total),0) FROM tm_ticket_type WHERE session_id=?", id);
      Api.require(cap >= allocated, "CAPACITY_EXCEEDED", "容量不能小于已分配票数");
      db.update(
          "UPDATE tm_session SET name=?,start_at=?,end_at=?,entry_start=?,entry_end=?,capacity=? WHERE id=?",
          name,
          start,
          end,
          entry,
          entryEnd,
          cap,
          id);
    }
    return sessionView(db.must("SELECT * FROM tm_session WHERE id=?", id));
  }

  public Map<String, Object> sessionView(Map<String, Object> s) {
    return Api.map(
        "id",
        s.get("id"),
        "eventId",
        s.get("event_id"),
        "name",
        s.get("name"),
        "startAt",
        iso(s.get("start_at")),
        "endAt",
        iso(s.get("end_at")),
        "entryStartAt",
        iso(s.get("entry_start")),
        "entryEndAt",
        iso(s.get("entry_end")),
        "capacity",
        s.get("capacity"));
  }

  public List<Map<String, Object>> sessions(String event) {
    event(event);
    return db.list("SELECT * FROM tm_session WHERE event_id=? ORDER BY start_at,id", event).stream()
        .map(this::sessionView)
        .collect(Collectors.toList());
  }

  @Transactional
  public Map<String, Object> saveType(String session, String id, Map<String, Object> b) {
    if (id != null)
      session = Db.s(db.must("SELECT * FROM tm_ticket_type WHERE id=?", id), "session_id");
    Map<String, Object> raw = db.must("SELECT * FROM tm_session WHERE id=?", session);
    draft(Db.s(raw, "event_id"));
    Map<String, Object> s = db.must("SELECT * FROM tm_session WHERE id=? FOR UPDATE", session);
    long total = Api.number(b, "stockTotal"),
        price = Api.number(b, "priceCent"),
        start = time(b, "saleStartAt"),
        end = time(b, "saleEndAt");
    Api.require(
        total > 0 && price >= 0 && start < end && end <= Db.n(s, "start_at"),
        "INVALID_ARGUMENT",
        "库存、价格或开售时间不合法");
    long allocated =
        db.count(
            "SELECT COALESCE(SUM(stock_total),0) FROM tm_ticket_type WHERE session_id=? AND id<>?",
            session,
            id == null ? "" : id);
    Api.require(allocated + total <= Db.n(s, "capacity"), "CAPACITY_EXCEEDED", "票档分配超过场次容量");
    if (id == null) {
      id = Db.id();
      db.update(
          "INSERT INTO tm_ticket_type(id,session_id,name,price_cent,stock_total,sale_start,sale_end,status) VALUES(?,?,?,?,?,?,?,?)",
          id,
          session,
          Api.text(b, "name"),
          price,
          total,
          start,
          end,
          "PAUSED");
      db.update("INSERT INTO tm_stock VALUES(?,?)", id, total);
    } else {
      db.update(
          "UPDATE tm_ticket_type SET name=?,price_cent=?,stock_total=?,sale_start=?,sale_end=? WHERE id=?",
          Api.text(b, "name"),
          price,
          total,
          start,
          end,
          id);
      db.update("UPDATE tm_stock SET available=? WHERE ticket_type_id=?", total, id);
    }
    return typeView(db.must("SELECT * FROM tm_ticket_type WHERE id=?", id));
  }

  public Map<String, Object> typeView(Map<String, Object> t) {
    long now = System.currentTimeMillis();
    String state = "PAUSED";
    if ("ON_SALE".equals(t.get("status")))
      state =
          now < Db.n(t, "sale_start")
              ? "NOT_STARTED"
              : now >= Db.n(t, "sale_end") ? "ENDED" : "ON_SALE";
    return Api.map(
        "id",
        t.get("id"),
        "sessionId",
        t.get("session_id"),
        "name",
        t.get("name"),
        "priceCent",
        t.get("price_cent"),
        "stockTotal",
        t.get("stock_total"),
        "saleStartAt",
        iso(t.get("sale_start")),
        "saleEndAt",
        iso(t.get("sale_end")),
        "status",
        t.get("status"),
        "saleState",
        state,
        "availableStock",
        db.count("SELECT available FROM tm_stock WHERE ticket_type_id=?", t.get("id")));
  }

  public Map<String, Object> type(String id) {
    Map<String, Object> t = db.must("SELECT * FROM tm_ticket_type WHERE id=?", id);
    Map<String, Object> s = db.must("SELECT * FROM tm_session WHERE id=?", t.get("session_id"));
    event(Db.s(s, "event_id"));
    return typeView(t);
  }

  public List<Map<String, Object>> types(String session) {
    Map<String, Object> s = db.must("SELECT * FROM tm_session WHERE id=?", session);
    event(Db.s(s, "event_id"));
    return db
        .list("SELECT * FROM tm_ticket_type WHERE session_id=? ORDER BY price_cent,id", session)
        .stream()
        .map(this::typeView)
        .collect(Collectors.toList());
  }

  @Transactional
  public Map<String, Object> publish(String id) {
    Map<String, Object> e = db.must("SELECT * FROM tm_event WHERE id=? FOR UPDATE", id);
    if ("PUBLISHED".equals(e.get("status"))) return Api.map("eventId", id, "status", "PUBLISHED");
    List<Map<String, Object>> ss = db.list("SELECT * FROM tm_session WHERE event_id=?", id);
    Api.require(!ss.isEmpty(), "INVALID_ARGUMENT", "至少创建一个场次");
    for (Map<String, Object> s : ss)
      Api.require(
          db.count("SELECT COUNT(*) FROM tm_ticket_type WHERE session_id=?", s.get("id")) > 0,
          "INVALID_ARGUMENT",
          "每个场次必须有票档");
    for (Map<String, Object> t :
        db.list(
            "SELECT t.* FROM tm_ticket_type t JOIN tm_session s ON s.id=t.session_id WHERE s.event_id=?",
            id)) inventory.initialize(Db.s(t, "id"), Db.n(t, "stock_total"));
    db.update(
        "UPDATE tm_ticket_type SET status='ON_SALE' WHERE session_id IN (SELECT id FROM tm_session WHERE event_id=?)",
        id);
    db.update("UPDATE tm_event SET status='PUBLISHED' WHERE id=?", id);
    invalid("event", id);
    return Api.map("eventId", id, "status", "PUBLISHED");
  }

  @Transactional
  public Map<String, Object> sale(String id, String status) {
    Api.require(Arrays.asList("ON_SALE", "PAUSED").contains(status), "INVALID_ARGUMENT", "售卖状态无效");
    db.must("SELECT * FROM tm_ticket_type WHERE id=? FOR UPDATE", id);
    db.update("UPDATE tm_ticket_type SET status=? WHERE id=?", status, id);
    return type(id);
  }

  public Map<String, Object> nearby(
      String city, double lon, double lat, double radius, int page, int size) {
    Api.require(
        radius > 0 && radius <= 20000 && lon >= -180 && lon <= 180 && lat >= -90 && lat <= 90,
        "INVALID_ARGUMENT",
        "坐标或距离不合法");
    List<Map<String, Object>> out = new ArrayList<>();
    if (features != null) {
      for (Map<String, Object> g : features.nearby(city, lon, lat, radius)) {
        Map<String, Object> v = venue(Db.s(g, "id"));
        v.put("distanceMeters", g.get("distance"));
        out.add(v);
      }
      return Api.page(out, page, size);
    }
    for (Map<String, Object> r : db.list("SELECT * FROM tm_venue WHERE city=?", city)) {
      double dy = Math.toRadians(((Number) r.get("latitude")).doubleValue() - lat),
          dx = Math.toRadians(((Number) r.get("longitude")).doubleValue() - lon);
      double a =
          Math.pow(Math.sin(dy / 2), 2)
              + Math.cos(Math.toRadians(lat))
                  * Math.cos(Math.toRadians(((Number) r.get("latitude")).doubleValue()))
                  * Math.pow(Math.sin(dx / 2), 2);
      double d = 6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
      if (d <= radius) {
        Map<String, Object> v = venue(Db.s(r, "id"));
        v.put("distanceMeters", Math.round(d));
        out.add(v);
      }
    }
    out.sort(Comparator.comparingLong(v -> ((Number) v.get("distanceMeters")).longValue()));
    return Api.page(out, page, size);
  }
}
