package ads.api;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The one thread that owns the engine and the scanner (doc 08 §3 rule 9). HTTP handler
 * threads never touch that state directly: they hand work here with {@link #call} and wait
 * for the answer, so every mutation is serialised in arrival order. Timed work (the
 * scanner's trailing throttled scan) is scheduled onto the same thread.
 */
public final class EngineThread implements Scheduler {
  private static final long CALL_TIMEOUT_MS = 10_000;

  private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "engine");
    t.setDaemon(true);
    return t;
  });

  /** Runs {@code task} on the engine thread and returns its result. */
  public <T> T call(Callable<T> task) throws Exception {
    try {
      return exec.submit(task).get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception) throw (Exception) cause;
      throw e;
    } catch (TimeoutException e) {
      throw new IllegalStateException("engine thread did not answer within " + CALL_TIMEOUT_MS
          + " ms", e);
    }
  }

  @Override
  public void schedule(Runnable task, long delayMs) {
    exec.schedule(task, delayMs, TimeUnit.MILLISECONDS);
  }

  public void shutdown() {
    exec.shutdownNow();
  }
}
