package jp.youren.api.health;

import java.util.concurrent.*;

// 有限等待与单一在途任务，依赖挂起时不累积无限后台探测。
public final class ReadinessService implements AutoCloseable {
  @FunctionalInterface
  public interface Probe {
    boolean check() throws Exception;
  }

  private final Probe probe;
  private final int timeout;
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          Thread.ofPlatform().daemon(true).name("readiness").factory());
  private CompletableFuture<Boolean> inFlight;
  private boolean closed;

  public ReadinessService(Probe probe, int timeout) {
    if (probe == null || timeout < 1 || timeout > 10000)
      throw new IllegalArgumentException("健康探测配置不正确");
    this.probe = probe;
    this.timeout = timeout;
  }

  public boolean ready() {
    CompletableFuture<Boolean> work;
    synchronized (this) {
      if (closed) return false;
      if (inFlight == null || inFlight.isDone())
        inFlight =
            CompletableFuture.supplyAsync(
                () -> {
                  try {
                    return probe.check();
                  } catch (Exception error) {
                    return false;
                  }
                },
                executor);
      work = inFlight;
    }
    try {
      return work.get(timeout, TimeUnit.MILLISECONDS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return false;
    } catch (ExecutionException | TimeoutException error) {
      return false;
    }
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    executor.shutdownNow();
  }
}
