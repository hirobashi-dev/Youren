package jp.youren.worker;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// 验证到期立即清空内容、物理对象可重试删除，以及待审告警去重但不自动驳回。
@Testcontainers(disabledWithoutDocker = false)
class WorkerRetentionIT {
  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.6");
  private JdbcTemplate jdbc;
  private WorkerCleanupService cleanup;
  private Path root;

  @BeforeEach
  void prepare() throws Exception {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).cleanDisabled(false).load().clean();
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
    root = Files.createTempDirectory("youren-worker-retention-");
    cleanup =
        new WorkerCleanupService(
            jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(source)),
            new LocalWorkerMediaStorage(root));
  }

  // now 等于 expires_at 时也先清正文并撤销图片关系；物理失败可重试且不会重新公开。
  @Test
  void expiresContentBeforeRetryableMediaDeletion() throws Exception {
    UUID owner = UUID.randomUUID();
    Instant expiry = Instant.parse("2026-01-01T00:00:00Z");
    UUID post = post(owner, "visible", expiry);
    UUID comment = comment(post, owner, expiry);
    UUID media = media(owner, "approved", null);
    String original = UUID.randomUUID().toString();
    String display = UUID.randomUUID().toString();
    jdbc.update("UPDATE media_assets SET object_key=? WHERE id=?", original, media);
    jdbc.update("INSERT INTO post_media(post_id,media_id,position) VALUES(?,?,0)", post, media);
    jdbc.update(
        "INSERT INTO media_variants(media_id,kind,object_key,size_bytes,width,height) VALUES(?,'display',?,10,10,10)",
        media,
        display);
    Files.createDirectories(root.resolve("originals"));
    Files.createDirectories(root.resolve("display"));
    Files.writeString(root.resolve("originals").resolve(original), "original");
    Files.writeString(root.resolve("display").resolve(display), "display");

    assertEquals(0, cleanup.expireContent(expiry.minusNanos(1000)));
    assertEquals(
        "visible", jdbc.queryForObject("SELECT status FROM posts WHERE id=?", String.class, post));
    assertEquals(2, cleanup.expireContent(expiry));
    assertEquals(
        "expired", jdbc.queryForObject("SELECT status FROM posts WHERE id=?", String.class, post));
    assertEquals(
        "expired",
        jdbc.queryForObject("SELECT status FROM comments WHERE id=?", String.class, comment));
    assertEquals("", jdbc.queryForObject("SELECT body FROM posts WHERE id=?", String.class, post));
    assertEquals(
        "", jdbc.queryForObject("SELECT body FROM comments WHERE id=?", String.class, comment));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM post_media", Integer.class));
    assertTrue(Files.exists(root.resolve("originals").resolve(original)));

    assertEquals(1, cleanup.processDeletionJobs(Instant.parse("2026-01-01T00:01:00Z")));
    assertFalse(Files.exists(root.resolve("originals").resolve(original)));
    assertFalse(Files.exists(root.resolve("display").resolve(display)));
    assertEquals(
        "deleted",
        jdbc.queryForObject("SELECT status FROM media_assets WHERE id=?", String.class, media));
    assertEquals(
        "completed",
        jdbc.queryForObject(
            "SELECT status FROM deletion_jobs WHERE target_id=?", String.class, media));
    assertEquals(0, cleanup.processDeletionJobs(Instant.parse("2026-01-01T00:02:00Z")));
  }

  // 未绑定满24小时进入删除队列；待审超72小时只产生唯一告警事件并保持 pending。
  @Test
  void cleansUnboundMediaAndAlertsOldPendingWithoutRejecting() {
    UUID owner = UUID.randomUUID();
    UUID orphan = media(owner, "uploaded", Instant.parse("2026-01-01T00:00:00Z"));
    UUID pending = post(owner, "pending", Instant.parse("2025-12-01T00:00:00Z"));
    jdbc.update(
        "UPDATE posts SET created_at=? WHERE id=?",
        Timestamp.from(Instant.parse("2025-12-01T00:00:00Z")),
        pending);
    Instant now = Instant.parse("2026-01-04T00:00:00Z");

    assertEquals(1, cleanup.enqueueUnboundMedia(now));
    assertEquals(1, cleanup.enqueuePendingAlerts(now));
    assertEquals(0, cleanup.enqueuePendingAlerts(now));
    assertEquals(
        "pending",
        jdbc.queryForObject("SELECT status FROM posts WHERE id=?", String.class, pending));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox WHERE aggregate_id=?", Integer.class, pending));
    assertEquals(
        "media",
        jdbc.queryForObject(
            "SELECT target_type FROM deletion_jobs WHERE target_id=?", String.class, orphan));
  }

  // 部分对象删除后遇到坏键会保留失败任务；修复对象元数据后重试可安全完成。
  @Test
  void retriesAfterPartialObjectDeletion() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID media = media(owner, "approved", null);
    String original = UUID.randomUUID().toString();
    String invalidDisplay = "../invalid";
    jdbc.update("UPDATE media_assets SET object_key=? WHERE id=?", original, media);
    jdbc.update(
        "INSERT INTO media_variants(media_id,kind,object_key,size_bytes,width,height) VALUES(?,'display',?,10,10,10)",
        media,
        invalidDisplay);
    jdbc.update(
        "INSERT INTO deletion_jobs(target_type,target_id,reason,dedupe_key) VALUES('media',?,'test_retry','media:'||?::text)",
        media,
        media);
    Files.createDirectories(root.resolve("originals"));
    Files.writeString(root.resolve("originals").resolve(original), "original");
    Instant now = Instant.parse("2026-01-04T00:00:00Z");

    assertEquals(0, cleanup.processDeletionJobs(now));
    assertFalse(Files.exists(root.resolve("originals").resolve(original)));
    assertEquals(
        "failed",
        jdbc.queryForObject(
            "SELECT status FROM deletion_jobs WHERE target_id=?", String.class, media));

    String repairedDisplay = UUID.randomUUID().toString();
    jdbc.update("UPDATE media_variants SET object_key=? WHERE media_id=?", repairedDisplay, media);
    Files.createDirectories(root.resolve("display"));
    Files.writeString(root.resolve("display").resolve(repairedDisplay), "display");
    assertEquals(1, cleanup.processDeletionJobs(now.plusSeconds(61)));
    assertEquals(
        "completed",
        jdbc.queryForObject(
            "SELECT status FROM deletion_jobs WHERE target_id=?", String.class, media));
    assertEquals(
        "deleted",
        jdbc.queryForObject("SELECT status FROM media_assets WHERE id=?", String.class, media));
  }

  private UUID post(UUID owner, String status, Instant expiresAt) {
    jdbc.update(
        "INSERT INTO principals(id,kind) VALUES(?,'guest') ON CONFLICT(id) DO NOTHING", owner);
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO posts(id,author_principal_id,display_mode,scope,category,title,body,status,published_at,expires_at) VALUES(?,?,'guest','public','general','标题','正文',?,?,?)",
        id,
        owner,
        status,
        status.equals("pending")
            ? null
            : Timestamp.from(expiresAt.minusSeconds(365L * 24 * 60 * 60)),
        status.equals("pending") ? null : Timestamp.from(expiresAt));
    return id;
  }

  private UUID media(UUID owner, String status, Instant cleanupAfter) {
    jdbc.update(
        "INSERT INTO principals(id,kind) VALUES(?,'guest') ON CONFLICT(id) DO NOTHING", owner);
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO media_assets(id,owner_principal_id,purpose,object_key,status,mime,size_bytes,upload_expires_at,cleanup_after) VALUES(?,?,'board',? ,?,'image/jpeg',10,?,?)",
        id,
        owner,
        UUID.randomUUID().toString(),
        status,
        Timestamp.from(Instant.parse("2026-01-05T00:00:00Z")),
        cleanupAfter == null ? null : Timestamp.from(cleanupAfter));
    return id;
  }

  private UUID comment(UUID post, UUID owner, Instant expiresAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO comments(id,post_id,author_principal_id,display_mode,body,status,published_at,expires_at) VALUES(?,?,?,'guest','回复正文','visible',?,?)",
        id,
        post,
        owner,
        Timestamp.from(expiresAt.minusSeconds(365L * 24 * 60 * 60)),
        Timestamp.from(expiresAt));
    return id;
  }
}
