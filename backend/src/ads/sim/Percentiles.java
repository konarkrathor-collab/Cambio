package ads.sim;

import java.math.BigDecimal;
import java.util.Arrays;

/**
 * Latency summaries by the nearest-rank method: the p-th percentile of n sorted samples is
 * the sample at rank {@code ceil(p / 100 * n)} (1-based), so it is always a measured value.
 * Samples are integer nanoseconds; summaries are microseconds with three decimals, exact.
 */
public final class Percentiles {
  private Percentiles() {
  }

  /** The nearest-rank percentile of {@code sorted[0..n)}, p in (0, 100]; 0 if n == 0. */
  public static long nearestRank(long[] sorted, int n, int p) {
    if (p <= 0 || p > 100) throw new IllegalArgumentException("percentile must be in (0, 100]: " + p);
    if (n == 0) return 0;
    long rank = ((long) p * n + 99) / 100; // ceil(p * n / 100), integer arithmetic
    return sorted[(int) rank - 1];
  }

  /** Nanoseconds as microseconds with three decimals (1234 ns -> 1.234). */
  public static BigDecimal micros(long nanos) {
    return BigDecimal.valueOf(nanos, 3);
  }

  /** Sorts {@code samples[0..n)} in place and returns {count, p50, p90, p99, max} in ns. */
  public static long[] summarize(long[] samples, int n) {
    Arrays.sort(samples, 0, n);
    return new long[] { n, nearestRank(samples, n, 50), nearestRank(samples, n, 90),
        nearestRank(samples, n, 99), n == 0 ? 0 : samples[n - 1] };
  }
}
