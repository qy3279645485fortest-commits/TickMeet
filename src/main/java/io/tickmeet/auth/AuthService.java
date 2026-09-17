package io.tickmeet.auth;

import io.tickmeet.common.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
  private final Db db;
  private final EphemeralStore store;
  private final boolean demo;
  private final int ttl;

  @org.springframework.beans.factory.annotation.Autowired
  private org.springframework.beans.factory.ObjectProvider<
          org.springframework.mail.javamail.JavaMailSender>
      mail;

  @Value("${tickmeet.mail-from:}")
  private String mailFrom;

  @org.springframework.beans.factory.annotation.Autowired private UserMapper users;
  private final SecureRandom random = new SecureRandom();

  public AuthService(
      Db d,
      EphemeralStore s,
      @Value("${tickmeet.demo:false}") boolean demo,
      @Value("${tickmeet.token-seconds:1800}") int ttl) {
    db = d;
    store = s;
    this.demo = demo;
    this.ttl = ttl;
  }

  public String normalize(String email) {
    email = email.trim().toLowerCase(Locale.ROOT);
    if (email.length() > 254 || !Pattern.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+", email))
      throw new Problem(400, "INVALID_ARGUMENT", "邮箱格式不正确");
    return email;
  }

  public String sendCode(String email) {
    email = normalize(email);
    if (!store.allow("code-rate:" + email, 1, 60))
      throw new Problem(429, "RATE_LIMITED", "请60秒后再次发送");
    String code = String.format("%06d", random.nextInt(1000000));
    store.put("code:" + email, code, 120);
    if (!demo) {
      try {
        org.springframework.mail.javamail.JavaMailSender sender = mail.getIfAvailable();
        if (sender == null || mailFrom.isEmpty()) throw new IllegalStateException();
        org.springframework.mail.SimpleMailMessage message =
            new org.springframework.mail.SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(email);
        message.setSubject("TickMeet 登录验证码");
        message.setText("您的 TickMeet 登录验证码为 " + code + "，2分钟内有效。若非本人操作请忽略。");
        sender.send(message);
      } catch (Exception e) {
        store.delete("code:" + email);
        throw new Problem(503, "DEPENDENCY_UNAVAILABLE", "验证码发送失败，请稍后重试");
      }
    }
    return code;
  }

  public Map<String, Object> login(String email, String code) {
    email = normalize(email);
    if (!store.allow("login-rate:" + email, 10, 60))
      throw new Problem(429, "RATE_LIMITED", "尝试过于频繁");
    if (!store.consume("code:" + email, code)) throw new Problem(400, "INVALID_CODE", "验证码无效或已使用");
    Map<String, Object> u = db.one("SELECT * FROM tm_user WHERE email=?", email);
    if (u == null) {
      try {
        db.update(
            "INSERT INTO tm_user(id,email,nick_name,role,created_at) VALUES(?,?,?,?,?)",
            Db.id(),
            email,
            "观众_" + Db.id().substring(0, 5),
            "USER",
            System.currentTimeMillis());
      } catch (org.springframework.dao.DuplicateKeyException ignored) {
      }
      u = db.must("SELECT * FROM tm_user WHERE email=?", email);
    }
    String token = Db.id() + Db.id();
    store.put("token:" + token, Db.s(u, "id"), ttl);
    return Api.map("token", token, "expiresInSeconds", ttl, "user", view(u));
  }

  public Map<String, Object> authenticate(String token) {
    if (token == null) return null;
    String id = store.get("token:" + token);
    if (id == null) return null;
    Map<String, Object> u = db.one("SELECT * FROM tm_user WHERE id=?", id);
    if (u != null) store.put("token:" + token, id, ttl);
    return u;
  }

  public void logout(String token) {
    if (token != null) store.delete("token:" + token);
  }

  public Map<String, Object> view(Map<String, Object> u) {
    return Api.map("id", u.get("id"), "nickName", u.get("nick_name"), "icon", u.get("icon"));
  }

  public Map<String, Object> user(String id) {
    UserEntity u = users.selectById(id);
    if (u == null) throw new Problem(404, "NOT_FOUND", "用户不存在");
    return Api.map("id", u.getId(), "nickName", u.getNickName(), "icon", u.getIcon());
  }

  public Map<String, Object> profile(String id) {
    Map<String, Object> r = db.one("SELECT * FROM tm_user_info WHERE user_id=?", id);
    return r == null
        ? null
        : Api.map(
            "userId",
            id,
            "city",
            r.get("city"),
            "introduce",
            r.get("introduce"),
            "birthday",
            r.get("birthday"));
  }
}
