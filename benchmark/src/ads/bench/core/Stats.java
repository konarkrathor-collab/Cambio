package ads.bench.core;

import java.util.Arrays;

/** Percentile / median helpers over small repeat counts (nearest-rank method). */
public final class Stats {
  private Stats() {
  }

  public static double median(long[] values) {
    return percentile(values, 50);
  }

  public static double percentile(long[] values, int p) {
    if (values.length == 0) return Double.NaN;
    long[] sorted = values.clone();
    Arrays.sort(sorted);
    int rank = (int) Math.ceil(p / 100.0 * sorted.length);
    if (rank < 1) rank = 1;
    return sorted[Math.min(rank - 1, sorted.length - 1)];
  }

  public static long min(long[] v) {
    long m = Long.MAX_VALUE;
    for (long x : v) m = Math.min(m, x);
    return m;
  }

  public static long max(long[] v) {
    long m = Long.MIN_VALUE;
    for (long x : v) m = Math.max(m, x);
    return m;
  }

  public static double mean(long[] v) {
    long s = 0;
    for (long x : v) s += x;
    return (double) s / v.length;
  }
}
