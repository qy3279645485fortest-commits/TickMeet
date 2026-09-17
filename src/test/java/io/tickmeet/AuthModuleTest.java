package io.tickmeet;

import static org.junit.jupiter.api.Assertions.*;

import io.tickmeet.auth.*;
import io.tickmeet.common.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AuthModuleTest {
  @Autowired AuthService auth;

  @Test
  void codeIsOneTimeAndLogoutRevokesSession() {
    String email = Db.id() + "@example.com";
    String code = auth.sendCode(email);
    Map<String, Object> login = auth.login(email, code);
    String token = login.get("token").toString();
    assertNotNull(auth.authenticate(token));
    assertThrows(Problem.class, () -> auth.login(email, code));
    auth.logout(token);
    assertNull(auth.authenticate(token));
  }

  @Test
  void frequentCodeAndInvalidEmailAreRejected() {
    String email = Db.id() + "@example.com";
    auth.sendCode(email);
    assertEquals(429, assertThrows(Problem.class, () -> auth.sendCode(email)).status);
    assertEquals(400, assertThrows(Problem.class, () -> auth.sendCode("not-an-email")).status);
  }

  @Test
  void publicUserDoesNotLeakEmailOrRole() {
    String email = Db.id() + "@example.com";
    Map<String, Object> u =
        (Map<String, Object>) auth.login(email, auth.sendCode(email)).get("user");
    assertFalse(u.containsKey("email"));
    assertFalse(u.containsKey("role"));
    assertTrue(u.containsKey("id"));
  }
}
