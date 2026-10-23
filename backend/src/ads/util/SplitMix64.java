package ads.util;

/**
 * SplitMix64, the seeded generator the benchmark lab uses for its workloads: a fixed seed
 * always yields the same sequence, which is what makes the demo and the simulator
 * reproducible. Not for anything security-related.
 */
public final class SplitMix64 {
  private long state;

  public SplitMix64(long seed) {
    this.state = seed;
  }

  public long nextLong() {
    long z = (state += 0x9E3779B97F4A7C15L);
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  /** Uniform in {@code [0, bound)}, bound > 0. */
  public int nextInt(int bound) {
    if (bound <= 0) throw new IllegalArgumentException("bound must be > 0: " + bound);
    return (int) Long.remainderUnsigned(nextLong(), bound);
  }
}
