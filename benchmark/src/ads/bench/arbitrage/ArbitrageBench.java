package ads.bench.arbitrage;

import ads.bench.core.AllocCounter;
import ads.bench.core.BenchResult;
import ads.bench.core.Stats;

/**
 * Phase 2+3 harness: for each candidate —
 * <ol>
 *   <li>verify against either the independent brute-force oracle (small graphs) or a
 *       cross-candidate reference verdict (large graphs, where the oracle is combinatorially
 *       infeasible — see {@link BruteForceOracle});</li>
 *   <li>warmup replays, discarded;</li>
 *   <li>timed reps (build + detect, a full replay) -&gt; median + min/max spread;</li>
 *   <li>thread-allocation measurement.</li>
 * </ol>
 *
 * <p>Unlike the ladder bench's long operation stream, one "replay" here is a single
 * build+detect(+reconstruct) pass — appropriate given the graphs involved are tiny (8-50
 * vertices); {@code reps} exists purely for timing stability, same role as in the ladder
 * bench.
 */
public final class ArbitrageBench {
  public interface Factory {
    GraphOps create();
  }

  private ArbitrageBench() {
  }

  public static DetectResult runOnce(GraphOps g, ArbitrageWorkload w) {
    g.build(w.v, w.edgeFrom, w.edgeTo, w.edgeWeight);
    boolean has = g.hasNegativeCycle();
    int[] cycle = has ? g.reconstructCycle() : new int[0];
    return new DetectResult(has, cycle);
  }

  public static final class DetectResult {
    public final boolean hasCycle;
    public final int[] cycle;

    DetectResult(boolean hasCycle, int[] cycle) {
      this.hasCycle = hasCycle;
      this.cycle = cycle;
    }
  }

  /** Structural validity of a reconstructed cycle: every consecutive pair is a real edge, the
   * path closes, and the summed weight is strictly negative. Does NOT require matching any
   * specific oracle-found cycle — "any valid negative cycle" is acceptable. */
  public static String verifyCycleShape(ArbitrageWorkload w, int[] cycle) {
    if (cycle.length < 2) {
      return "cycle too short (" + cycle.length + " vertices)";
    }
    if (cycle[0] != cycle[cycle.length - 1]) {
      return "cycle does not close (first != last)";
    }
    double sum = 0.0;
    for (int i = 0; i + 1 < cycle.length; i++) {
      double weight = findEdgeWeight(w, cycle[i], cycle[i + 1]);
      if (Double.isNaN(weight)) {
        return "edge " + cycle[i] + "->" + cycle[i + 1] + " does not exist in graph";
      }
      sum += weight;
    }
    if (sum >= -1e-9) {
      return "reconstructed cycle weight not negative (sum=" + sum + ")";
    }
    return null;
  }

  private static double findEdgeWeight(ArbitrageWorkload w, int u, int v) {
    for (int e = 0; e < w.edgeFrom.length; e++) {
      if (w.edgeFrom[e] == u && w.edgeTo[e] == v) {
        return w.edgeWeight[e];
      }
    }
    return Double.NaN;
  }

  /** Correctness gate against a known-correct boolean verdict (either the independent
   * brute-force oracle on small graphs, or a trusted reference candidate's verdict on large
   * ones — the caller decides which, and the distinction is surfaced in the console/CSV note
   * so it's never silently presented as independent verification when it isn't). */
  public static String verify(GraphOps candidate, ArbitrageWorkload w, boolean referenceHasCycle) {
    DetectResult r = runOnce(candidate, w);
    if (r.hasCycle != referenceHasCycle) {
      return "disagreed with reference verdict: candidate=" + r.hasCycle
          + " reference=" + referenceHasCycle;
    }
    if (r.hasCycle) {
      String shapeProblem = verifyCycleShape(w, r.cycle);
      if (shapeProblem != null) {
        return shapeProblem;
      }
    }
    return null;
  }

  public static BenchResult timed(String bench, String name, Factory factory, ArbitrageWorkload w,
      int warmup, int reps, AllocCounter alloc) {
    for (int i = 0; i < warmup; i++) {
      runOnce(factory.create(), w);
    }
    long[] times = new long[reps];
    for (int i = 0; i < reps; i++) {
      System.gc();
      GraphOps g = factory.create();
      long t0 = System.nanoTime();
      runOnce(g, w);
      times[i] = System.nanoTime() - t0;
    }
    long allocDelta = -1;
    if (alloc.isEnabled()) {
      System.gc();
      long a0 = alloc.bytes();
      GraphOps g = factory.create();
      runOnce(g, w);
      allocDelta = alloc.bytes() - a0;
    }
    double median = Stats.median(times);
    int edgeCount = w.edgeFrom.length; // reused as BenchResult's "n" — see note in Main.java
    return new BenchResult(bench, name, edgeCount, w.seed, reps, true,
        Stats.min(times) / 1e6, median / 1e6, Stats.max(times) / 1e6,
        allocDelta < 0 ? -1.0 : (double) allocDelta / Math.max(1, edgeCount), w.label);
  }

  public static BenchResult failed(String bench, String name, ArbitrageWorkload w, int reps,
      String why) {
    return new BenchResult(bench, name, w.edgeFrom.length, w.seed, reps, false, 0, 0, 0, -1, why);
  }
}
