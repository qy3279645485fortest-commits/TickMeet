package io.tickmeet.auth;

import io.tickmeet.common.*;
import java.util.*;
import javax.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthController {
  private final AuthService auth;
  private final boolean demo;

  public AuthController(AuthService a, @Value("${tickmeet.demo:false}") boolean d) {
    auth = a;
    demo = d;
  }

  @PostMapping("/user/code")
  public Object code(@javax.validation.Valid @RequestBody Inputs.Email b) {
    String code = auth.sendCode(Api.text(b.map(), "email"));
    return Api.ok(demo ? Api.map("demoCode", code, "notice", "本地演示验证码，未发送真实邮件") : null);
  }

  @PostMapping("/user/login")
  public Object login(@javax.validation.Valid @RequestBody Inputs.Login b) {
    return Api.ok(auth.login(Api.text(b.map(), "email"), Api.text(b.map(), "code")));
  }

  @PostMapping("/user/logout")
  public Object logout(HttpServletRequest r) {
    auth.logout(r.getHeader("authorization"));
    return Api.ok(null);
  }

  @GetMapping("/user/me")
  public Object me() {
    return Api.ok(auth.view(CurrentUser.get()));
  }

  @GetMapping("/user/{id}")
  public Object user(@PathVariable String id) {
    return Api.ok(auth.user(id));
  }

  @GetMapping("/user/info/{id}")
  public Object info(@PathVariable String id) {
    return Api.ok(auth.profile(id));
  }

  @GetMapping("/experience")
  public Object mode() {
    return Api.ok(
        Api.map(
            "mode",
            demo ? "LOCAL_DEMO" : "LIVE",
            "authenticated", CurrentUser.authenticated(),
            "brand",
            "TickMeet",
            "role",
            CurrentUser.is("ADMIN") ? "ADMIN" : CurrentUser.is("STAFF") ? "STAFF" : "USER"));
  }
}
