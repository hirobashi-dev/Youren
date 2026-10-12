package jp.youren.api.admin;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Clock;
import java.util.UUID;
import jp.youren.api.auth.AuthFailure;
import jp.youren.api.media.StoragePort;
import jp.youren.database.mapper.AdminMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 后台仅能读取仍待审内容的展示图，每次请求重新鉴权；不存在原图或磁盘路径下载接口。
@RestController
public class AdminMediaController {
  private final AdminMapper mapper;
  private final StoragePort storage;
  private final Clock clock;

  public AdminMediaController(AdminMapper mapper, StoragePort storage, Clock clock) {
    this.mapper = mapper;
    this.storage = storage;
    this.clock = clock;
  }

  // 短时URL仍要求有效管理员会话，并且对应媒体必须继续关联待审或驳回内容。
  @GetMapping("/v1/admin/media/{mediaId}/display")
  public ResponseEntity<byte[]> display(
      HttpServletRequest request,
      @PathVariable("mediaId") UUID mediaId,
      @RequestParam(name = "expiresAt") long expiresAt)
      throws IOException {
    AdminPrincipal principal = (AdminPrincipal) request.getAttribute(AdminPrincipal.ATTRIBUTE);
    if (principal == null
        || !java.util.Set.of("reviewer", "operator", "auditor").contains(principal.role())) {
      throw AuthFailure.unauthorized();
    }
    long now = clock.instant().getEpochSecond();
    if (expiresAt < now || expiresAt > now + 600) throw new AuthFailure(404, "CONTENT_UNAVAILABLE");
    String key = mapper.adminReviewImageKey(mediaId);
    if (key == null) throw new AuthFailure(404, "CONTENT_UNAVAILABLE");
    try (var input = storage.open(StoragePort.Area.DISPLAY, key)) {
      return ResponseEntity.ok()
          .header("Cache-Control", "no-store")
          .header("X-Content-Type-Options", "nosniff")
          .contentType(MediaType.IMAGE_JPEG)
          .body(input.readAllBytes());
    }
  }
}
