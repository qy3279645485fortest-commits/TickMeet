package io.tickmeet.trade;

import io.tickmeet.auth.*;
import io.tickmeet.common.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class PaymentController {
  private final PaymentService s;
  private final boolean demo;
  private final String secret;

  public PaymentController(
      PaymentService p,
      @Value("${tickmeet.demo:false}") boolean d,
      @Value("${tickmeet.callback-secret:}") String sec) {
    s = p;
    demo = d;
    secret = sec;
  }

  @PostMapping("/orders/{id}/payments")
  public Object pay(@PathVariable String id, @RequestHeader("Idempotency-Key") String key) {
    return Api.ok(s.payment(CurrentUser.id(), id, key));
  }

  @PostMapping("/payments/mock/notify")
  public Object notifyPay(
      @RequestBody String body,
      @RequestHeader("X-Mock-Signature") String sig,
      @RequestHeader("X-Mock-Timestamp") String time) {
    signature(body, sig, time);
    s.notifyPayment(Json.object(body));
    return Api.ok(Api.map("received", true));
  }

  @PostMapping("/refunds/mock/notify")
  public Object notifyRefund(
      @RequestBody String body,
      @RequestHeader("X-Mock-Signature") String sig,
      @RequestHeader("X-Mock-Timestamp") String time) {
    signature(body, sig, time);
    s.notifyRefund(Json.object(body));
    return Api.ok(Api.map("received", true));
  }

  private void signature(String body, String sig, String time) {
    try {
      if (secret.length() < 32
          || Math.abs(System.currentTimeMillis() - Long.parseLong(time)) > 300000)
        throw new Exception();
      Mac m = Mac.getInstance("HmacSHA256");
      m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String expected =
          Base64.getEncoder()
              .encodeToString(m.doFinal((time + "." + body).getBytes(StandardCharsets.UTF_8)));
      if (!MessageDigest.isEqual(
          expected.getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8)))
        throw new Exception();
    } catch (Exception e) {
      throw new Problem(403, "FORBIDDEN", "回调签名无效");
    }
  }

  @PostMapping("/demo/payments/{id}/confirm")
  public Object simulate(@PathVariable String id) {
    if (!demo) throw new Problem(404, "NOT_FOUND", "演示入口未启用");
    return Api.ok(s.simulate(CurrentUser.id(), id));
  }

  @PostMapping("/demo/refunds/{id}/confirm")
  public Object simulateRefund(@PathVariable String id) {
    if (!demo) throw new Problem(404, "NOT_FOUND", "演示入口未启用");
    return Api.ok(s.simulateRefund(CurrentUser.id(), id));
  }

  @GetMapping("/tickets")
  public Object tickets(
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size) {
    return Api.ok(s.tickets(CurrentUser.id(), status, page, size));
  }

  @GetMapping("/tickets/{id}")
  public Object ticket(@PathVariable String id) {
    return Api.ok(s.ticket(CurrentUser.id(), id));
  }

  @PostMapping("/staff/tickets/verify")
  public Object verify(
      @RequestHeader("Idempotency-Key") String key,
      @javax.validation.Valid @RequestBody Inputs.Verification b) {
    CurrentUser.role("STAFF");
    return Api.ok(
        s.verify(
            CurrentUser.id(), Api.text(b.map(), "qrPayload"), Api.text(b.map(), "sessionId"), key));
  }

  @PostMapping("/orders/{id}/refunds")
  public Object refund(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @javax.validation.Valid @RequestBody Inputs.Refund b) {
    return Api.ok(s.refund(CurrentUser.id(), id, key, Api.text(b.map(), "reason")));
  }

  @GetMapping(value = "/tickets/{id}/qr", produces = "image/svg+xml")
  public String qr(@PathVariable String id) throws Exception {
    String payload = s.ticket(CurrentUser.id(), id).get("qrPayload").toString();
    com.google.zxing.common.BitMatrix bits =
        new com.google.zxing.qrcode.QRCodeWriter()
            .encode(payload, com.google.zxing.BarcodeFormat.QR_CODE, 240, 240);
    StringBuilder out =
        new StringBuilder(
            "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 240 240'><rect width='240' height='240' fill='white'/><path fill='#171321' d='");
    for (int y = 0; y < 240; y++)
      for (int x = 0; x < 240; x++)
        if (bits.get(x, y)) out.append("M").append(x).append(" ").append(y).append("h1v1h-1z");
    return out.append("'/></svg>").toString();
  }
}
