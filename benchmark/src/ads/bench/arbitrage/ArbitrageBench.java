package ads.bench.arbitrage;

import ads.bench.core.BenchResult;
import ads.bench.core.Interleaved;

/**
 * Phase 2+3 harness: for each candidate —
 * <ol>
 *   <li>verify against either the independent brute-force oracle (small graphs) or a
 *       cross-candidate reference verdict (large graphs, where the oracle is combinatorially
 *       infeasible — see {@link BruteForceOracle});</li>
 *   <li>warmup rounds, discarded, then timed reps — interleaved round-robin across all
 *       candidates ({@link Interleaved});</li>
 *   <li>thread-allocation measurement.</li>
 * </ol>
 *
 * <p>The timed unit is one SCAN = build + {@code hasNegativeCycle()}. Cycle
 * reconstruction is NOT timed: it runs only on the verification path, as the
 * {@link GraphOps} contract states (the earlier harness timed it anyway on graphs with a
 * cycle). A scan on these graphs takes microseconds, so each timed rep runs a batch of
 * {@link #scansPerRep} scans and reports the per-scan median, and warmup is extended to
 * at least {@link #MIN_WARMUP_SCANS} scans per candidate — the old 3-scan warmup left the
 * first candidate measured before the JIT had compiled it.
 */
public final class ArbitrageBench {
  public interface Factory {
    GraphOps create();
  }

  private ArbitrageBench() {
  }

  /** Each candidate executes at least this many scans before any timed rep. */
  public static final int MIN_WARMUP_SCANS = 2_000;

  /** Scans per timed rep, sized to ~2M edge relaxations (roughly a couple of ms). */
  public static int scansPerRep(ArbitrageWorkload w) {
    long work = (long) w.v * w.edgeFrom.length;
    return (int) Math.max(1, 2_000_000L / Math.max(1, work));
  }

  /** Warmup rounds so every candidate reaches {@link #MIN_WARMUP_SCANS}. */
  public static int warmupRounds(ArbitrageWorkload w, int warmup) {
    int per = scansPerRep(w);
    return Math.max(warmup, (MIN_WARMUP_SCANS + per - 1) / per);
  }

  /** The timed unit: build + detect only (no reconstruction — see class doc). */
  public static boolean scanOnce(GraphOps g, ArbitrageWorkload w) {
    g.build(w.v, w.edgeFrom, w.edgeTo, w.edgeWeight);
    return g.hasNegativeCycle();
  }

  /** Verification path: detect AND reconstruct. Never timed. */
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

  /** Prevents dead-code elimination of scan verdicts. */
  private static volatile int blackhole;

  /** Timed unit for {@link Interleaved}: a batch of {@code perRep} fresh build+detect scans. */
  public static Interleaved.Trial trial(Factory factory, ArbitrageWorkload w, int perRep) {
    return new Interleaved.Trial() {
      @Override
      public Object setup() {
        return null; // instances are created per scan inside run(); creation is trivial
      }

      @Override
      public void run(Object ignored) {
        int found = 0;
        for (int i = 0; i < perRep; i++) {
          if (scanOnce(factory.create(), w)) found++;
        }
        blackhole ^= found;
      }
    };
  }

  public static BenchResult failed(String bench, String name, ArbitrageWorkload w, int reps,
      String why) {
    return new BenchResult(bench, name, w.edgeFrom.length, w.seed, reps, false, 0, 0, 0, -1, why);
  }
}
