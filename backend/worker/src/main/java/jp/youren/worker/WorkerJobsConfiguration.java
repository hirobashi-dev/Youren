package jp.youren.worker;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 后台任务每分钟扫描一次；数据库事务负责可见状态和任务账本，文件删除保持可重试。
@Configuration
@EnableScheduling
public class WorkerJobsConfiguration {
  @Bean
  public WorkerMediaStorage workerMediaStorage(@Value("${youren.media.root:}") String root) {
    if (root == null || root.isBlank()) {
      throw new IllegalStateException("必须设置YOUREN_MEDIA_ROOT为本机私有绝对路径");
    }
    return new LocalWorkerMediaStorage(Path.of(root));
  }

  @Bean
  public WorkerCleanupService workerCleanupService(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      WorkerMediaStorage storage) {
    return new WorkerCleanupService(jdbc, new TransactionTemplate(transactionManager), storage);
  }

  @Bean
  public WorkerJobs workerJobs(WorkerCleanupService cleanup) {
    return new WorkerJobs(cleanup);
  }

  public static class WorkerJobs {
    private final WorkerCleanupService cleanup;

    WorkerJobs(WorkerCleanupService cleanup) {
      this.cleanup = cleanup;
    }

    // 任何一次任务失败都由删除账本保留重试状态，不影响其他扫描周期。
    @Scheduled(fixedDelayString = "${YOUREN_WORKER_CLEANUP_INTERVAL_MS:60000}")
    public void runCleanupCycle() {
      var now = java.time.Instant.now();
      cleanup.expireContent(now);
      cleanup.enqueueUnboundMedia(now);
      cleanup.enqueuePendingAlerts(now);
      cleanup.processDeletionJobs(now);
    }
  }
}
