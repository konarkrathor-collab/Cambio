package ads.api;

/** Runs a task later on the engine thread (tests substitute a manual scheduler). */
public interface Scheduler {
  void schedule(Runnable task, long delayMs);
}
