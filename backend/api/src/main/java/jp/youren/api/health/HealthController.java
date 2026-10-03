package jp.youren.api.health;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// 公开健康响应只含固定status，不暴露数据库、Redis或错误细节。
@RestController
@RequestMapping("/health")
@CrossOrigin(
    origins = {"http://localhost:5173", "http://127.0.0.1:5173"},
    methods = RequestMethod.GET)
public final class HealthController {
  private final ReadinessService service;

  public HealthController(ReadinessService service) {
    this.service = service;
  }

  @GetMapping("/live")
  public ResponseEntity<java.util.Map<String, String>> live() {
    return ResponseEntity.ok(java.util.Map.of("status", "ok"));
  }

  @GetMapping("/ready")
  public ResponseEntity<java.util.Map<String, String>> ready() {
    return service.ready()
        ? live()
        : ResponseEntity.status(503).body(java.util.Map.of("status", "unavailable"));
  }
}
