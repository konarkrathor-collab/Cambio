package ads.bench.arbitrage;

import ads.bench.core.Rng;

/**
 * DATA (Phase 1): currency-arbitrage graphs for the Bellman-Ford / SPFA / Floyd-Warshall /
 * Johnson / Tarjan comparison (doc 05 §3).
 *
 * <p>Two sources, mirroring the ladder bench's real-vs-synthetic split:
 * <ul>
 *   <li>{@link #realSnapshot()} — an actual captured rate snapshot (citation below). Real
 *       EOD rates are expected to be arbitrage-free (doc 06's own point: genuine triangular
 *       arbitrage gets closed in microseconds, long before a daily snapshot could ever
 *       observe it), so this graph exercises the "nothing found, full relaxation required"
 *       code path — the more expensive and more common case in practice.</li>
 *   <li>{@link #synthetic(int, long, boolean)} — a complete directed graph over V synthetic
 *       currencies, consistent by construction (every edge is a ratio of one underlying
 *       "fair value" table, same as how real cross-rates derive from one base currency), so
 *       it is arbitrage-free unless a cycle is deliberately injected.</li>
 * </ul>
 */
public final class ArbitrageWorkload {
  public final int v;
  public final String[] names;
  public final int[] edgeFrom;
  public final int[] edgeTo;
  public final double[] edgeWeight; // w = -ln(rate)
  public final long seed;
  public final String label;

  private ArbitrageWorkload(int v, String[] names, int[] ef, int[] et, double[] ew, long seed,
      String label) {
    this.v = v;
    this.names = names;
    this.edgeFrom = ef;
    this.edgeTo = et;
    this.edgeWeight = ew;
    this.seed = seed;
    this.label = label;
  }

  /**
   * Captured 2026-10-09 from the free, keyless Frankfurter API (ECB reference rates) — the
   * same API documentation/06_market_data_ingestion_service.md now keeps as its *fallback*
   * source (doc 06's primary moved to a live Finnhub WebSocket after this capture):
   * {@code GET https://api.frankfurter.dev/v1/latest?base=USD}. Raw response archived at
   * {@code benchmark/data/frankfurter_usd_2026-10-09.json}. Units: 1 USD = rate[i] units of
   * currency i. Roster matches doc 06 §4's proposed 8-currency graph.
   */
  private static final String[] REAL_NAMES = {"USD", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "INR"};
  private static final double[] REAL_RATES_PER_USD =
      {1.0, 0.89238, 0.75641, 158.25, 1.4324, 1.4226, 0.83107, 96.73};

  public static ArbitrageWorkload realSnapshot() {
    int n = REAL_NAMES.length;
    int edgeCount = n * (n - 1);
    int[] ef = new int[edgeCount];
    int[] et = new int[edgeCount];
    double[] ew = new double[edgeCount];
    int idx = 0;
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < n; j++) {
        if (i == j) {
          continue;
        }
        double rate = REAL_RATES_PER_USD[j] / REAL_RATES_PER_USD[i]; // R(i -> j)
        ef[idx] = i;
        et[idx] = j;
        ew[idx] = -Math.log(rate);
        idx++;
      }
    }
    return new ArbitrageWorkload(n, REAL_NAMES.clone(), ef, et, ew, 0,
        "real-frankfurter-2026-10-09");
  }

  /** Per-leg bid/ask-style spread applied to EVERY directed edge (doc 06's point: without
   * this, a reciprocal pair u-&gt;v-&gt;u can "arbitrage" on rounding/injection noise alone).
   * {@code 2 * SPREAD} is the cost of round-tripping any single pair; it must exceed the
   * per-leg injection amount below so an injected 3-cycle doesn't accidentally also flip a
   * trivial 2-cycle negative. */
  private static final double SPREAD = 0.0015; // 15 bps per leg
  private static final double INJECT_AMOUNT = 0.002; // SPREAD < INJECT_AMOUNT < 2*SPREAD

  /**
   * Complete directed graph over V synthetic currencies. Absent injection, arbitrage-free by
   * construction (every reciprocal pair costs {@code 2*SPREAD} to round-trip). If
   * {@code injectCycle} is true, one 3-cycle (deterministically chosen from the seed) is
   * perturbed just enough to force THAT cycle negative, while each individual edge's own
   * reciprocal pair stays positive — i.e. no accidental 2-currency "arbitrage" side effect.
   */
  public static ArbitrageWorkload synthetic(int v, long seed, boolean injectCycle) {
    if (v < 3) {
      throw new IllegalArgumentException("need at least 3 currencies for a triangular cycle: " + v);
    }
    Rng rng = new Rng(seed);
    String[] names = new String[v];
    for (int i = 0; i < v; i++) {
      names[i] = "CCY" + i;
    }
    double[] value = new double[v]; // underlying "fair value" vs an implicit numeraire
    for (int i = 0; i < v; i++) {
      value[i] = 0.5 + rng.nextDouble() * 9.5; // in [0.5, 10.0)
    }
    int edgeCount = v * (v - 1);
    int[] ef = new int[edgeCount];
    int[] et = new int[edgeCount];
    double[] ew = new double[edgeCount];
    int idx = 0;
    for (int i = 0; i < v; i++) {
      for (int j = 0; j < v; j++) {
        if (i == j) {
          continue;
        }
        ef[idx] = i;
        et[idx] = j;
        ew[idx] = -Math.log(value[j] / value[i]) + SPREAD;
        idx++;
      }
    }
    String label = "synthetic-v" + v + "-seed" + seed;
    if (injectCycle) {
      int a = rng.nextInt(v);
      int b = a;
      while (b == a) {
        b = rng.nextInt(v);
      }
      int c = a;
      while (c == a || c == b) {
        c = rng.nextInt(v);
      }
      shrinkEdge(ef, et, ew, edgeCount, a, b, INJECT_AMOUNT);
      shrinkEdge(ef, et, ew, edgeCount, b, c, INJECT_AMOUNT);
      shrinkEdge(ef, et, ew, edgeCount, c, a, INJECT_AMOUNT);
      label += "+injected(" + a + "->" + b + "->" + c + "->" + a + ")";
    }
    return new ArbitrageWorkload(v, names, ef, et, ew, seed, label);
  }

  /** Subtracts {@code amount} from edge u->v's weight (raises its implied rate), biasing a
   * candidate cycle through it toward negative total weight. */
  private static void shrinkEdge(int[] ef, int[] et, double[] ew, int edgeCount, int u, int w,
      double amount) {
    for (int e = 0; e < edgeCount; e++) {
      if (ef[e] == u && et[e] == w) {
        ew[e] -= amount;
        return;
      }
    }
    throw new IllegalStateException("edge " + u + "->" + w + " not found (u == w?)");
  }

  public String describe() {
    return label + " | V=" + v + " E=" + edgeFrom.length;
  }
}
