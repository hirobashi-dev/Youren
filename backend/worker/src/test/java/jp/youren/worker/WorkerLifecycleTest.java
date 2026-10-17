package jp.youren.worker;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkerLifecycleTest {
  // 正常依赖只检查一次，重复启动/关闭不会重开或重复释放。
  @Test
  void startsOnceAndClosesIdempotently() {
    var attempts = new AtomicInteger();
    try (var lifecycle =
        new WorkerLifecycle(
            () -> {
              attempts.incrementAndGet();
              return true;
            },
            1500)) {
      lifecycle.start();
      lifecycle.start();
      assertTrue(lifecycle.running());
      assertEquals(1, attempts.get());
      lifecycle.close();
      lifecycle.close();
      assertFalse(lifecycle.running());
      assertThrows(IllegalStateException.class, lifecycle::start);
    }
  }

  // 失败不留下运行状态或泄漏原异常，再次启动不能绕过失败状态。
  @Test
  void startupFailureIsSafeAndCannotRestartClosedLifecycle() {
    try (var lifecycle =
        new WorkerLifecycle(
            () -> {
              throw new IllegalStateException("secret-value");
            },
            1500)) {
      var error = assertThrows(IllegalStateException.class, lifecycle::start);
      assertFalse(error.toString().contains("secret-value"));
      assertNull(error.getCause());
      assertFalse(lifecycle.running());
      assertThrows(IllegalStateException.class, lifecycle::start);
    }
  }

  // 启动超时会中断探测线程，不能一直占用连接或阻塞退出。
  @Test
  void startupTimeoutInterruptsDependencyProbe() throws Exception {
    var started = new CountDownLatch(1);
    var released = new CountDownLatch(1);
    try (var lifecycle =
        new WorkerLifecycle(
            () -> {
              started.countDown();
              try {
                new CountDownLatch(1).await();
              } finally {
                released.countDown();
              }
              return true;
            },
            100)) {
      assertThrows(IllegalStateException.class, lifecycle::start);
      assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
      assertTrue(released.await(2, java.util.concurrent.TimeUnit.SECONDS));
      assertFalse(lifecycle.running());
    }
  }
}
