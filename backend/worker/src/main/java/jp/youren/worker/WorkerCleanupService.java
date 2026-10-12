package jp.youren.worker;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

// 先在事务内撤销到期内容可见性并保存删除元信息，再在事务外删除文件以支持故障重试。
public final class WorkerCleanupService {
  private static final int BATCH_SIZE = 50;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final WorkerMediaStorage storage;

  public WorkerCleanupService(
      JdbcTemplate jdbc, TransactionTemplate transaction, WorkerMediaStorage storage) {
    this.jdbc = jdbc;
    this.transaction = transaction;
    this.storage = storage;
  }

  // 到期边界使用<=；内容正文和附件关系先原子清除，待审/驳回/隐藏内容不进入此分支。
  public int expireContent(Instant now) {
    Integer count =
        transaction.execute(
            ignored -> {
              int posts =
                  jdbc.update(
                      "WITH picked AS (SELECT id FROM posts WHERE status='visible' AND expires_at<=? ORDER BY expires_at,id FOR UPDATE SKIP LOCKED LIMIT "
                          + BATCH_SIZE
                          + ") UPDATE posts p SET status='expired',title='',body='',updated_at=?,version=version+1 FROM picked WHERE p.id=picked.id",
                      dbTime(now),
                      dbTime(now));
              int comments =
                  jdbc.update(
                      "WITH picked AS (SELECT id FROM comments WHERE status='visible' AND expires_at<=? ORDER BY expires_at,id FOR UPDATE SKIP LOCKED LIMIT "
                          + BATCH_SIZE
                          + ") UPDATE comments c SET status='expired',body='',updated_at=?,version=version+1 FROM picked WHERE c.id=picked.id",
                      dbTime(now),
                      dbTime(now));
              jdbc.update(
                  "INSERT INTO deletion_jobs(target_type,target_id,reason,dedupe_key) SELECT 'media',pm.media_id,'content_expired','media:'||pm.media_id::text FROM post_media pm JOIN posts p ON p.id=pm.post_id WHERE p.status='expired' AND p.updated_at=? ON CONFLICT(dedupe_key) DO NOTHING",
                  dbTime(now));
              jdbc.update(
                  "INSERT INTO deletion_jobs(target_type,target_id,reason,dedupe_key) SELECT 'media',cm.media_id,'content_expired','media:'||cm.media_id::text FROM comment_media cm JOIN comments c ON c.id=cm.comment_id WHERE c.status='expired' AND c.updated_at=? ON CONFLICT(dedupe_key) DO NOTHING",
                  dbTime(now));
              jdbc.update(
                  "UPDATE media_assets SET status='deleting',updated_at=? WHERE id IN (SELECT pm.media_id FROM post_media pm JOIN posts p ON p.id=pm.post_id WHERE p.status='expired' AND p.updated_at=? UNION SELECT cm.media_id FROM comment_media cm JOIN comments c ON c.id=cm.comment_id WHERE c.status='expired' AND c.updated_at=?) AND status<>'deleted'",
                  dbTime(now),
                  dbTime(now),
                  dbTime(now));
              jdbc.update(
                  "DELETE FROM post_media WHERE post_id IN (SELECT id FROM posts WHERE status='expired' AND updated_at=?)",
                  dbTime(now));
              jdbc.update(
                  "DELETE FROM comment_media WHERE comment_id IN (SELECT id FROM comments WHERE status='expired' AND updated_at=?)",
                  dbTime(now));
              return posts + comments;
            });
    return count == null ? 0 : count;
  }

  // 未绑定资源达到cleanup_after后进入统一删除账本，唯一键使多worker扫描仍然幂等。
  public int enqueueUnboundMedia(Instant now) {
    Integer count =
        transaction.execute(
            ignored -> {
              int enqueued =
                  jdbc.update(
                      "INSERT INTO deletion_jobs(target_type,target_id,reason,dedupe_key) SELECT 'media',m.id,'unbound_upload_expired','media:'||m.id::text FROM media_assets m WHERE m.cleanup_after<=? AND m.status NOT IN ('deleting','deleted') AND NOT EXISTS(SELECT 1 FROM post_media p WHERE p.media_id=m.id) AND NOT EXISTS(SELECT 1 FROM comment_media c WHERE c.media_id=m.id) ORDER BY m.cleanup_after,m.id LIMIT "
                          + BATCH_SIZE
                          + " ON CONFLICT(dedupe_key) DO NOTHING",
                      dbTime(now));
              jdbc.update(
                  "UPDATE media_assets SET status='deleting',updated_at=? WHERE id IN (SELECT target_id FROM deletion_jobs WHERE reason='unbound_upload_expired' AND status<>'completed') AND status<>'deleted'",
                  dbTime(now));
              return enqueued;
            });
    return count == null ? 0 : count;
  }

  // 72小时仅写入唯一待处理告警事件；不改内容状态，不把待审内容当作过期公开内容。
  public int enqueuePendingAlerts(Instant now) {
    Integer count =
        transaction.execute(
            ignored ->
                jdbc.update(
                        "INSERT INTO outbox(event_type,aggregate_type,aggregate_id,payload,dedupe_key,available_at) SELECT 'moderation.pending_alert','post',p.id,jsonb_build_object('targetType','post','targetId',p.id),'moderation-pending:post:'||p.id::text,? FROM posts p WHERE p.status='pending' AND p.created_at<=? AND NOT EXISTS(SELECT 1 FROM outbox o WHERE o.dedupe_key='moderation-pending:post:'||p.id::text) ORDER BY p.created_at,p.id LIMIT 100 ON CONFLICT(dedupe_key) DO NOTHING",
                        dbTime(now),
                        dbTime(now.minusSeconds(72 * 60 * 60)))
                    + jdbc.update(
                        "INSERT INTO outbox(event_type,aggregate_type,aggregate_id,payload,dedupe_key,available_at) SELECT 'moderation.pending_alert','comment',c.id,jsonb_build_object('targetType','comment','targetId',c.id),'moderation-pending:comment:'||c.id::text,? FROM comments c WHERE c.status='pending' AND c.created_at<=? AND NOT EXISTS(SELECT 1 FROM outbox o WHERE o.dedupe_key='moderation-pending:comment:'||c.id::text) ORDER BY c.created_at,c.id LIMIT 100 ON CONFLICT(dedupe_key) DO NOTHING",
                        dbTime(now),
                        dbTime(now.minusSeconds(72 * 60 * 60))));
    return count == null ? 0 : count;
  }

  // 领取采用SKIP LOCKED；过期processing租约可重领，外部删除成功但数据库失败时删除保持幂等。
  public int processDeletionJobs(Instant now) {
    List<Deletion> jobs =
        transaction.execute(
            ignored ->
                jdbc.query(
                    "WITH picked AS (SELECT id FROM deletion_jobs WHERE (status='pending' OR (status='failed' AND (retry_after IS NULL OR retry_after<=?)) OR (status='processing' AND updated_at<=?)) ORDER BY requested_at,id FOR UPDATE SKIP LOCKED LIMIT "
                        + BATCH_SIZE
                        + ") UPDATE deletion_jobs j SET status='processing',retry_after=NULL,updated_at=? FROM picked WHERE j.id=picked.id RETURNING j.id,j.target_type,j.target_id",
                    (row, index) ->
                        new Deletion(
                            row.getObject("id", UUID.class),
                            row.getString("target_type"),
                            row.getObject("target_id", UUID.class)),
                    dbTime(now),
                    dbTime(now.minusSeconds(300)),
                    dbTime(now)));
    int completed = 0;
    for (Deletion job : jobs == null ? List.<Deletion>of() : jobs) {
      try {
        if ("media".equals(job.type())) deleteMedia(job.targetId());
        else throw new IllegalArgumentException("清理目标类型不受支持");
        transaction.execute(
            ignored -> {
              jdbc.update(
                  "UPDATE deletion_jobs SET status='completed',completed_at=?,updated_at=? WHERE id=?",
                  dbTime(now),
                  dbTime(now),
                  job.id());
              return null;
            });
        completed++;
      } catch (IOException | RuntimeException failure) {
        transaction.execute(
            ignored -> {
              jdbc.update(
                  "UPDATE deletion_jobs SET status='failed',retry_after=?,updated_at=? WHERE id=?",
                  dbTime(now.plusSeconds(60)),
                  dbTime(now),
                  job.id());
              return null;
            });
      }
    }
    return completed;
  }

  private void deleteMedia(UUID mediaId) throws IOException {
    List<ObjectRef> objects =
        jdbc.query(
            "SELECT 'original' AS area,object_key FROM media_assets WHERE id=? UNION ALL SELECT kind AS area,object_key FROM media_variants WHERE media_id=?",
            (row, index) -> new ObjectRef(row.getString("area"), row.getString("object_key")),
            mediaId,
            mediaId);
    for (ObjectRef object : objects) storage.delete(object.area(), object.key());
    transaction.execute(
        ignored -> {
          jdbc.update("DELETE FROM media_variants WHERE media_id=?", mediaId);
          jdbc.update(
              "UPDATE media_assets SET status='deleted',deleted_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?",
              mediaId);
          return null;
        });
  }

  private record Deletion(UUID id, String type, UUID targetId) {}

  private record ObjectRef(String area, String key) {}

  private Timestamp dbTime(Instant instant) {
    // PostgreSQL timestamptz精度为微秒；统一截断确保比较参数与持久化更新时间一致。
    return Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS));
  }
}
