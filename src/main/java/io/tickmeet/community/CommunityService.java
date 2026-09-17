package io.tickmeet.community;

import io.tickmeet.auth.*;
import io.tickmeet.catalog.*;
import io.tickmeet.common.*;
import io.tickmeet.files.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommunityService {
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private io.tickmeet.cache.RedisFeatures features;

  public final Db db;
  private final AuthService auth;
  private final EphemeralStore rate;
  private final FileService files;

  public CommunityService(Db d, AuthService a, EphemeralStore s, FileService f) {
    db = d;
    auth = a;
    rate = s;
    files = f;
  }

  void limit(String user, String action, int n) {
    if (!rate.allow(action + ":" + user, n, 60)) throw new Problem(429, "RATE_LIMITED", "操作过于频繁");
  }

  @Transactional
  public Object post(String user, Map<String, Object> b) {
    limit(user, "post", 5);
    String event = Api.text(b, "eventId"),
        title = Api.text(b, "title"),
        content = Api.text(b, "content");
    if (title.length() > 180 || content.length() > 10000)
      throw new Problem(400, "INVALID_ARGUMENT", "笔记内容过长");
    db.must("SELECT * FROM tm_event WHERE id=? AND status='PUBLISHED'", event);
    String id = Db.id();
    List<String> ids = Json.strings(b.get("imageFileIds"));
    files.bind(user, id, ids);
    List<String> urls = new ArrayList<>();
    for (String image : ids) urls.add("/api/files/" + image + "/content");
    db.update(
        "INSERT INTO tm_post VALUES(?,?,?,?,?,?,0,?)",
        id,
        event,
        user,
        title,
        content,
        Json.write(urls),
        System.currentTimeMillis());
    db.update(
        "INSERT INTO tm_outbox(id,kind,aggregate_id,status,created_at,next_at,attempts) VALUES(?,?,?,'PENDING',?,?,0)",
        Db.id(),
        "POST_PUBLISHED",
        id,
        System.currentTimeMillis(),
        System.currentTimeMillis());
    return Api.map("id", id);
  }

  public Map<String, Object> view(Map<String, Object> p, String user) {
    return Api.map(
        "id",
        p.get("id"),
        "eventId",
        p.get("event_id"),
        "author",
        auth.user(Db.s(p, "user_id")),
        "title",
        p.get("title"),
        "content",
        p.get("content"),
        "images",
        Json.strings(p.get("images")),
        "likedCount",
        p.get("liked_count"),
        "isLiked",
        user != null
            && db.count(
                    "SELECT COUNT(*) FROM tm_like WHERE post_id=? AND user_id=?", p.get("id"), user)
                > 0,
        "createdAt",
        CatalogService.iso(p.get("created_at")));
  }

  public Object get(String id, String user) {
    return view(db.must("SELECT * FROM tm_post WHERE id=?", id), user);
  }

  public Object list(String user, String author, String event, int page, int size) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> p :
        db.list(
            author == null
                ? "SELECT * FROM tm_post ORDER BY liked_count DESC,id DESC"
                : "SELECT * FROM tm_post ORDER BY created_at DESC,id DESC")) {
      if (author != null && !author.equals(p.get("user_id"))) continue;
      if (event != null && !event.equals(p.get("event_id"))) continue;
      out.add(view(p, user));
    }
    return Api.page(out, page, size);
  }

  @Transactional
  public Object like(String user, String id, boolean liked) {
    limit(user, "like", 60);
    Map<String, Object> p = db.must("SELECT * FROM tm_post WHERE id=? FOR UPDATE", id);
    boolean exists =
        db.count("SELECT COUNT(*) FROM tm_like WHERE post_id=? AND user_id=?", id, user) > 0;
    if (liked && !exists) {
      db.update("INSERT INTO tm_like VALUES(?,?,?)", id, user, System.currentTimeMillis());
      db.update("UPDATE tm_post SET liked_count=liked_count+1 WHERE id=?", id);
    } else if (!liked && exists) {
      db.update("DELETE FROM tm_like WHERE post_id=? AND user_id=?", id, user);
      db.update("UPDATE tm_post SET liked_count=liked_count-1 WHERE id=?", id);
    }
    return Api.map(
        "liked", liked, "likedCount", db.count("SELECT liked_count FROM tm_post WHERE id=?", id));
  }

  public Object likes(String id) {
    List<Object> out = new ArrayList<>();
    if (features != null) {
      for (String uid : features.likes(id)) out.add(auth.user(uid));
      return out;
    }
    for (Map<String, Object> l :
        db.list(
            "SELECT user_id FROM tm_like WHERE post_id=? ORDER BY liked_at,user_id LIMIT 5", id))
      out.add(auth.user(Db.s(l, "user_id")));
    return out;
  }

  public boolean follows(String user, String target) {
    return db.count(
            "SELECT COUNT(*) FROM tm_follow WHERE user_id=? AND follow_user_id=?", user, target)
        > 0;
  }

  @Transactional
  public Object follow(String user, String target, boolean followed) {
    limit(user, "follow", 60);
    Api.require(!user.equals(target), "INVALID_ARGUMENT", "不能关注自己");
    db.must("SELECT * FROM tm_user WHERE id=? FOR UPDATE", user);
    db.must("SELECT * FROM tm_user WHERE id=?", target);
    boolean exists = follows(user, target);
    if (followed && !exists)
      db.update("INSERT INTO tm_follow VALUES(?,?,?)", user, target, System.currentTimeMillis());
    else if (!followed && exists)
      db.update("DELETE FROM tm_follow WHERE user_id=? AND follow_user_id=?", user, target);
    return Api.map("followed", followed);
  }

  public Object common(String user, String target) {
    List<Object> out = new ArrayList<>();
    if (features != null) {
      for (String uid : features.common(user, target)) out.add(auth.user(uid));
      return out;
    }
    for (Map<String, Object> r :
        db.list(
            "SELECT a.follow_user_id FROM tm_follow a JOIN tm_follow b ON a.follow_user_id=b.follow_user_id WHERE a.user_id=? AND b.user_id=?",
            user,
            target)) out.add(auth.user(Db.s(r, "follow_user_id")));
    return out;
  }

  public Object feed(String user, long last, int offset) {
    if (offset < 0) throw new Problem(400, "INVALID_ARGUMENT", "offset不能为负");
    List<Map<String, Object>> rows =
        features == null
            ? db.list(
                "SELECT p.* FROM tm_post p JOIN tm_follow f ON p.user_id=f.follow_user_id WHERE f.user_id=? AND p.created_at<=? ORDER BY p.created_at DESC,p.id DESC",
                user,
                last)
            : features.feedRows(user, last);
    List<Object> out = new ArrayList<>();
    long min = last;
    int seen = 0, skip = offset;
    for (Map<String, Object> p : rows) {
      long time = Db.n(p, "created_at");
      if (time == last && skip-- > 0) continue;
      if (out.size() == 10) break;
      out.add(view(p, user));
      if (time == min) seen++;
      else {
        min = time;
        seen = 1;
      }
    }
    return Api.map(
        "list",
        out,
        "minTime",
        out.isEmpty() ? null : min,
        "offset",
        min == last ? offset + seen : seen);
  }

  public void sign(String user) {
    try {
      db.update(
          "INSERT INTO tm_sign VALUES(?,?)",
          user,
          LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());
    } catch (org.springframework.dao.DuplicateKeyException ignored) {
    }
  }

  public Object countSign(String user) {
    if (features != null) return Api.map("consecutiveDays", features.signs(user));
    LocalDate day = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    int month = day.getMonthValue(), n = 0;
    while (day.getMonthValue() == month
        && db.count(
                "SELECT COUNT(*) FROM tm_sign WHERE user_id=? AND sign_date=?",
                user,
                day.toString())
            > 0) {
      n++;
      day = day.minusDays(1);
    }
    return Api.map("consecutiveDays", n);
  }
}
