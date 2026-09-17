package io.tickmeet;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.tickmeet.auth.*;
import io.tickmeet.catalog.*;
import io.tickmeet.common.*;
import io.tickmeet.trade.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HttpModuleTest {
  @Autowired MockMvc http;
  @Autowired AuthService auth;
  @Autowired Db db;
  @Autowired CatalogService catalog;
  @Autowired TradeService trade;
  @Autowired OutboxWorker worker;

  private String token(String role) {
    String email = Db.id() + "@tickmeet.local";
    String code = auth.sendCode(email);
    Map<String, Object> login = auth.login(email, code);
    Map<String, Object> u = (Map<String, Object>) login.get("user");
    db.update("UPDATE tm_user SET role=? WHERE id=?", role, u.get("id"));
    return login.get("token").toString();
  }

  @Test
  void anonymousAndUserCannotOperateAdmin() throws Exception {
    http.perform(post("/api/admin/venues").contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized());
    http.perform(
            post("/api/admin/venues")
                .header("authorization", token("USER"))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    http.perform(get("/api/user/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void httpPurchasePayTicketAndOwnerBoundary() throws Exception {
    String type = TradeFixture.type(catalog, 3), token = token("USER");
    String user = Db.s(auth.authenticate(token), "id");
    MvcResult result =
        http.perform(
                post("/api/ticket-types/" + type + "/reservations")
                    .header("authorization", token)
                    .header("Idempotency-Key", Db.id()))
            .andExpect(status().isAccepted())
            .andReturn();
    Map<String, Object> data =
        (Map<String, Object>) Json.object(result.getResponse().getContentAsString()).get("data");
    worker.drain();
    String order = trade.reservation(user, Db.s(data, "reservationId")).get("orderId").toString();
    MvcResult pay =
        http.perform(
                post("/api/orders/" + order + "/payments")
                    .header("authorization", token)
                    .header("Idempotency-Key", Db.id()))
            .andExpect(status().isOk())
            .andReturn();
    String payment =
        Db.s(
            (Map<String, Object>) Json.object(pay.getResponse().getContentAsString()).get("data"),
            "paymentId");
    http.perform(post("/api/demo/payments/" + payment + "/confirm").header("authorization", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PAID"));
    String ticket = Db.s(trade.order(user, order), "ticketId");
    http.perform(get("/api/tickets/" + ticket + "/qr").header("authorization", token))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("image/svg+xml"));
    http.perform(get("/api/tickets/" + ticket).header("authorization", token("USER")))
        .andExpect(status().isNotFound());
  }

  @Test
  void forgedPaymentCallbackCannotPay() throws Exception {
    http.perform(
            post("/api/payments/mock/notify")
                .header("X-Mock-Signature", "forged")
                .header("X-Mock-Timestamp", System.currentTimeMillis())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void requiredIdempotencyHeaderIsEnforced() throws Exception {
    http.perform(
            post("/api/ticket-types/missing/reservations").header("authorization", token("USER")))
        .andExpect(status().isBadRequest());
  }
}
