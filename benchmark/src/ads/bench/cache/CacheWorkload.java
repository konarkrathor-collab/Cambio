package ads.bench.cache;

import ads.bench.core.Rng;

import java.util.Arrays;

/**
 * DATA (Phase 1): one deterministic Zipfian access stream for the cache bench.
 *
 * <p><b>Why Zipfian (and not uniform):</b> doc 05 §4's entire justification for both the
 * LRU pick and the splay-tree alternative is that a small minority of accounts (market
 * makers, active traders) generate most traffic. A uniform-random stream would exercise a
 * different workload than the one the rubric actually claims — a methodology error here,
 * not a simplification. Classic Zipf with exponent s = 1.0 is the canonical heavy-tail
 * model for order flow: with the default 100,000-trader universe the top 10% of accounts
 * receive roughly 81% of accesses (harmonic estimate; the generator reports the measured
 * share in {@link #describe()}). The exponent is a CLI parameter for sensitivity runs.
 *
 * <p>Exact inverse-CDF sampling: weights {@code w_i = 1/i^s} are accumulated once into a
 * cumulative array and each draw is a seeded binary search — deterministic and
 * distribution-exact, unlike rejection or integer-approximation generators.
 */
public final class CacheWorkload {
  public final int n;
  public final long seed;
  public final int universe;  // trader ids are 1..universe
  public final int capacity;  // the LRU candidate's bound (unused by no-eviction candidates)
  public final double zipfS;
  public final long[] traderId;
  public final boolean[] touched; // ids 1..universe that appear anywhere in the stream
  public final int distinctTouched;
  public final int topDecileSharePerMille; // measured share of accesses to the top 10% of ids

  private CacheWorkload(int n, long seed, int universe, int capacity, double zipfS,
      long[] traderId, boolean[] touched, int distinctTouched, int topDecileSharePerMille) {
    this.n = n;
    this.seed = seed;
    this.universe = universe;
    this.capacity = capacity;
    this.zipfS = zipfS;
    this.traderId = traderId;
    this.touched = touched;
    this.distinctTouched = distinctTouched;
    this.topDecileSharePerMille = topDecileSharePerMille;
  }

  public static CacheWorkload generate(int n, long seed, int universe, double zipfS,
      int capacity) {
    if (universe < 1) throw new IllegalArgumentException("universe must be >= 1");
    if (zipfS <= 0) throw new IllegalArgumentException("zipf exponent must be > 0");
    if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
    Rng rng = new Rng(seed);
    double[] cum = new double[universe];
    double total = 0.0;
    for (int i = 1; i <= universe; i++) {
      total += 1.0 / Math.pow(i, zipfS);
      cum[i - 1] = total;
    }
    long[] ids = new long[n];
    boolean[] touched = new boolean[universe + 1];
    int distinct = 0;
    int topHits = 0;
    int topDecile = Math.max(1, universe / 10);
    for (int i = 0; i < n; i++) {
      double u = rng.nextDouble() * total;
      int idx = Arrays.binarySearch(cum, u);
      if (idx < 0) idx = -idx - 1;
      if (idx >= universe) idx = universe - 1;
      long id = idx + 1L;
      ids[i] = id;
      if (!touched[(int) id]) {
        touched[(int) id] = true;
        distinct++;
      }
      if (id <= topDecile) topHits++;
    }
    int sharePerMille = (int) Math.round(1000.0 * topHits / n);
    return new CacheWorkload(n, seed, universe, capacity, zipfS, ids, touched, distinct,
        sharePerMille);
  }

  /** Human-readable summary of the stream (for logs). */
  public String describe() {
    return String.format(
        "n=%d seed=%d | universe=%d zipfS=%.2f capacity=%d | distinctTouched=%d"
            + " top10%%share=%.1f%%",
        n, seed, universe, zipfS, capacity, distinctTouched, topDecileSharePerMille / 10.0);
  }
}
