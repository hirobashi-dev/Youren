package jp.youren.api.email;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import jp.youren.api.auth.AuthFailure;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// 仅auth-test存在的本机调试入口；独立密钥只能放请求头，响应和错误禁止缓存。
@RestController
@Profile("auth-test")
public class TestInboxController {
  private final TestEmailInbox inbox;

  public record InboxCode(String code) {
    @Override
    public String toString() {
      return "InboxCode[REDACTED]";
    }
  }

  public TestInboxController(TestEmailInbox inbox) {
    this.inbox = inbox;
  }

  @GetMapping("/__test/mail/{challengeId}")
  public ResponseEntity<InboxCode> read(
      @PathVariable("challengeId") String challengeId,
      @RequestHeader(value = "X-Test-Inbox-Key", required = false) String key,
      HttpServletRequest request) {
    UUID id;
    try {
      id = UUID.fromString(challengeId);
      if (!id.toString().equalsIgnoreCase(challengeId)) throw AuthFailure.invalid();
    } catch (IllegalArgumentException ex) {
      throw AuthFailure.invalid();
    }
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(new InboxCode(inbox.read(id, key, request.getRemoteAddr())));
  }
}
