package ads.bench.core;

/**
 * Round-robin timing across ALL candidates of one bench (fairness rule 3b).
 *
 * <p>Why this exists: the earlier harness timed candidates one after another. Every bench
 * replays through one shared call site ({@code s.put(...)}, {@code g.hasNegativeCycle()}),
 * so the JIT profile, the inlining decisions and the machine's thermal/GC state all depend
 * on WHICH candidates ran before — the first candidate is measured while the call site is
 * still monomorphic, later ones after it has gone megamorphic. The clearest symptom was
 * Johnson (a pure delegate to Bellman-Ford, i.e. identical code) timing 2-3x faster than
 * Bellman-Ford simply because it ran later.
 *
 * <p>Here every warmup round and every timed round runs every candidate once, and the
 * starting candidate rotates each round, so no candidate is systematically first or last.
 * Construction ({@link Trial#setup()}) is excluded from timing, exactly as before.
 */
public final class Interleaved {
  private Interleaved() {
  }

  /** One candidate's timed unit: a fresh structure, then one full replay over it. */
  public interface Trial {
    Object setup();

    void run(Object state);
  }

  /** Returns {@code times[candidate][rep]} in nanoseconds. */
  public static long[][] time(Trial[] trials, int warmup, int reps) {
    int c = trials.length;
    for (int round = 0; round < warmup; round++) {
      for (int i = 0; i < c; i++) {
        Trial t = trials[(i + round) % c];
        t.run(t.setup()); // JIT warmup, discarded
      }
    }
    long[][] times = new long[c][reps];
    for (int round = 0; round < reps; round++) {
      for (int i = 0; i < c; i++) {
        int k = (i + round) % c;
        System.gc();
        Object s = trials[k].setup();
        long t0 = System.nanoTime();
        trials[k].run(s);
        times[k][round] = System.nanoTime() - t0;
      }
    }
    return times;
  }

  /** Bytes allocated by one setup + run (construction INCLUDED), or -1 if unavailable. */
  public static long allocBytes(Trial t, AllocCounter alloc) {
    if (!alloc.isEnabled()) return -1;
    System.gc();
    long a0 = alloc.bytes();
    t.run(t.setup());
    return alloc.bytes() - a0;
  }

  /**
   * Times every trial interleaved, then measures allocation per trial, and packs one
   * {@link BenchResult} per trial (same order as the input).
   *
   * @param perRun   each {@code run()} performs this many units of work; times are divided
   *                 by it so the reported median is per unit (1 for full-replay benches)
   * @param allocDen denominator for alloc-per-op (ops per unit)
   */
  public static BenchResult[] measure(String bench, String[] names, Trial[] trials,
      String[] notes, int n, long seed, int warmup, int reps, AllocCounter alloc, int perRun,
      double allocDen) {
    long[][] times = time(trials, warmup, reps);
    BenchResult[] out = new BenchResult[trials.length];
    for (int i = 0; i < trials.length; i++) {
      long a = allocBytes(trials[i], alloc);
      double scale = 1e6 * perRun;
      out[i] = new BenchResult(bench, names[i], n, seed, reps, true,
          Stats.min(times[i]) / scale, Stats.median(times[i]) / scale,
          Stats.max(times[i]) / scale,
          a < 0 ? -1.0 : (double) a / perRun / allocDen, notes[i]);
    }
    return out;
  }
}
