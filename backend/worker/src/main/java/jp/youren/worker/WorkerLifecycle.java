package jp.youren.worker;

import java.util.concurrent.*;

// 启动仅探测依赖；有限等待，失败或关闭后禁止重新使用已释放生命周期。
public final class WorkerLifecycle implements AutoCloseable {
  @FunctionalInterface
  public interface Probe {
    boolean check() throws Exception;
  }

  private final Probe probe;
  private final int timeout;
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          Thread.ofPlatform().daemon(true).name("worker-startup").factory());
  private boolean running;
  private boolean closed;

  public WorkerLifecycle(Probe probe, int timeout) {
    if (probe == null || timeout < 1 || timeout > 10000)
      throw new IllegalArgumentException("worker配置不正确");
    this.probe = probe;
    this.timeout = timeout;
  }

  public synchronized void start() {
    if (closed) throw new IllegalStateException("worker已关闭");
    if (running) return;
    try {
      if (!executor.submit(probe::check).get(timeout, TimeUnit.MILLISECONDS))
        throw new IllegalStateException();
      running = true;
    } catch (Exception error) {
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      close();
      throw new IllegalStateException("worker依赖不可用");
    } finally {
      executor.shutdownNow();
    }
  }

  public synchronized boolean running() {
    return running;
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    running = false;
    executor.shutdownNow();
  }
}
