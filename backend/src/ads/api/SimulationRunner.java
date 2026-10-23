package ads.api;

import ads.sim.Simulator;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs simulations on a worker thread of their own, never on the engine thread, one at a
 * time: a request that arrives while one runs is refused with 409 SIMULATION_RUNNING
 * instead of queueing. The caller blocks until its simulation finishes. The most recent
 * result is kept for GET /api/simulate/last.
 */
public final class SimulationRunner {
  /** One simulation: the request parameters in, the response body out. */
  public interface Task {
    Map<String, Object> run(Simulator.Params params) throws Exception;
  }

  private final Task task;
  private final AtomicBoolean running = new AtomicBoolean();
  private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "simulator");
    t.setDaemon(true);
    return t;
  });
  private volatile Map<String, Object> last;

  public SimulationRunner(Task task) {
    this.task = task;
  }

  /** Runs one simulation and returns its result; 409 if one is already running. */
  public Map<String, Object> run(Simulator.Params params) throws Exception {
    if (!running.compareAndSet(false, true)) {
      throw new ApiException(409, "SIMULATION_RUNNING",
          "a simulation is already running; try again when it finishes");
    }
    Future<Map<String, Object>> f;
    try {
      // the flag is cleared by the worker itself, so it tracks the work, not the caller
      f = worker.submit(() -> {
        try {
          Map<String, Object> result = task.run(params);
          last = result;
          return result;
        } finally {
          running.set(false);
        }
      });
    } catch (RuntimeException rejected) {
      running.set(false);
      throw rejected;
    }
    try {
      return f.get();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception) throw (Exception) cause;
      throw e;
    }
  }

  /** The most recent result; 404 if no simulation has finished yet. */
  public Map<String, Object> last() throws ApiException {
    Map<String, Object> r = last;
    if (r == null) {
      throw new ApiException(404, "NO_SIMULATION", "no simulation has finished since the server started");
    }
    return r;
  }

  /** True once a simulation has finished in this server session (GET /api/health). */
  public boolean hasResult() {
    return last != null;
  }

  public boolean running() {
    return running.get();
  }

  public void shutdown() {
    worker.shutdownNow();
  }
}
