package ads.bench.core;

/**
 * Deterministic, seedable PRNG (SplitMix64).
 * Every benchmark run with the same seed must produce bit-identical workloads and
 * structure-internal random decisions (e.g. skip-list levels).
 */
public final class Rng {
  private long state;

  public Rng(long seed) {
    this.state = seed;
  }

  public long nextLong() {
    long z = (state += 0x9E3779B97F4A7C15L);
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  /** Uniform in [0, bound). */
  public int nextInt(int bound) {
    if (bound <= 0) throw new IllegalArgumentException("bound must be > 0: " + bound);
    return (int) Math.floorMod(nextLong(), (long) bound);
  }

  /** Uniform in [min, max] inclusive. */
  public int nextInt(int min, int max) {
    return min + nextInt(max - min + 1);
  }

  /** Uniform in [0, 1). */
  public double nextDouble() {
    return (nextLong() >>> 11) * 0x1.0p-53;
  }
}
