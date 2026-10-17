package jp.youren.api.guest;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

// 游客创建无需客户端身份；追踪ID由服务端生成，响应采用合同data/requestId封装。
@RestController
public class GuestSessionController {
  private final GuestSessionService service;

  public record Envelope(GuestSessionService.GuestSession data, String requestId) {}

  public GuestSessionController(GuestSessionService service) {
    this.service = service;
  }

  @PostMapping("/v1/guest-sessions")
  public ResponseEntity<Envelope> create() {
    return ResponseEntity.status(201)
        .header("Cache-Control", "no-store")
        .body(new Envelope(service.create(), UUID.randomUUID().toString()));
  }
}
