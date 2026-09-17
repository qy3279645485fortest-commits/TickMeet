package io.tickmeet.auth;

import javax.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class AuthConfig implements WebMvcConfigurer {
  private final AuthService auth;
  private final EphemeralStore limits;

  public AuthConfig(AuthService a, EphemeralStore l) {
    auth = a;
    limits = l;
  }

  public void addInterceptors(InterceptorRegistry r) {
    r.addInterceptor(
            new HandlerInterceptor() {
              public boolean preHandle(
                  HttpServletRequest req, HttpServletResponse res, Object handler) {
                CurrentUser.clear();
                try {
                  CurrentUser.set(auth.authenticate(req.getHeader("authorization")));
                  String path = req.getRequestURI();
                  if (path.equals("/api/user/code") || path.equals("/api/user/login")) {
                    if (!limits.allow("ip:" + path + ":" + req.getRemoteAddr(), 30, 60))
                      throw new io.tickmeet.common.Problem(429, "RATE_LIMITED", "操作频率过高");
                  }
                  if (path.startsWith("/api/reservations/")
                      && !limits.allow("poll:" + CurrentUser.id(), 3, 1))
                    throw new io.tickmeet.common.Problem(429, "RATE_LIMITED", "请稍后查询");
                  if (path.equals("/api/staff/tickets/verify")
                      && !limits.allow("verify:" + CurrentUser.id(), 120, 60))
                    throw new io.tickmeet.common.Problem(429, "RATE_LIMITED", "核销频率过高");
                  if (path.startsWith("/api/admin/")) CurrentUser.role("ADMIN");
                  if (path.startsWith("/api/staff/")) CurrentUser.role("STAFF");
                  return true;
                } catch (RuntimeException e) {
                  CurrentUser.clear();
                  throw e;
                }
              }

              public void afterCompletion(
                  HttpServletRequest req, HttpServletResponse res, Object handler, Exception e) {
                CurrentUser.clear();
              }
            })
        .addPathPatterns("/api/**");
  }
}
