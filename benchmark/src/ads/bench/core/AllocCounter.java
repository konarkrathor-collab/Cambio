package ads.bench.core;

import java.lang.management.ManagementFactory;

/**
 * Measures bytes allocated by the CURRENT thread using the JVM's per-thread allocation
 * counters. This is the memory metric used across all structures: fair and independent of
 * GC timing (unlike RSS), and it charges each structure exactly for what it allocates.
 */
public final class AllocCounter {
  private final com.sun.management.ThreadMXBean bean;
  private final boolean enabled;

  public AllocCounter() {
    java.lang.management.ThreadMXBean base = ManagementFactory.getThreadMXBean();
    com.sun.management.ThreadMXBean b = null;
    boolean ok = false;
    if (base instanceof com.sun.management.ThreadMXBean) {
      b = (com.sun.management.ThreadMXBean) base;
      if (!b.isThreadAllocatedMemoryEnabled()) {
        try {
          b.setThreadAllocatedMemoryEnabled(true);
        } catch (UnsupportedOperationException ignored) {
          // stay disabled
        }
      }
      ok = b.isThreadAllocatedMemoryEnabled();
    }
    this.bean = b;
    this.enabled = ok;
  }

  public boolean isEnabled() {
    return enabled;
  }

  /** Bytes allocated by the current thread since JVM start, or -1 if unavailable. */
  public long bytes() {
    if (!enabled) return -1;
    return bean.getCurrentThreadAllocatedBytes();
  }
}
