package ads.scanner;

import ads.directory.Directory;

/**
 * The currency-loophole scanner (doc 06, doc 08 Stage 1): a rate store over the
 * Directory's 12 currencies (or any currency list that includes USD) plus a Bellman-Ford
 * scan.
 *
 * <p><b>Rates.</b> {@code rate[from][to]} = units of {@code to} per one {@code from}
 * (Appendix B: tick {@code EUR_USD 1.0852} means 1 EUR = 1.0852 USD). A tick sets both
 * directions, the reverse being the reciprocal, and marks them DIRECT. Every other pair is
 * DERIVED through USD when both USD legs are known: {@code R(a,b) = R(USD,b) / R(USD,a)}
 * (doc 06 §4); a direct rate is never overwritten by a derived one. Unknown rates are NaN
 * and simply have no edge.
 *
 * <p><b>Scan.</b> Edge {@code u->v} has weight {@code -ln(rate)}, so a negative cycle is a
 * loop whose rates multiply to more than 1. A cycle counts as arbitrage only if its exact
 * multiplier (the product of its rates) reaches {@code 1 + threshold} (default 0.001%), so
 * floating-point noise is never reported. Bellman-Ford returns SOME negative cycle, not
 * the best one, and a sub-threshold cycle must not hide a real one (backend/BUGS.md B4).
 * So the scan runs several passes, the L-th on weights raised by {@code ln(1+t)/L} for
 * {@code L = 2, 3, ..., V+1}, and reports the most profitable returned cycle that passes
 * the exact check:
 *
 * <ul>
 *   <li>pass L = 2: every cycle has at least 2 edges, so any cycle it returns earns at
 *       least the threshold. It is therefore guaranteed to report whenever some cycle of
 *       k edges earns more than {@code (1+t)^(k/2) - 1} (for a triangle, about 1.5 t);</li>
 *   <li>passes L = 3..V+1 catch cycles closer to the threshold, down to any cycle of fewer
 *       than L edges earning at least t, as long as Bellman-Ford returns it.</li>
 * </ul>
 *
 * <p>Before those, one pass on the unshifted weights: shifting only raises weights, so if
 * that pass finds no negative cycle, none of the shifted passes can, and the scan stops
 * there (one Bellman-Ford run instead of V). The result is identical either way.
 *
 * <p>Exact detection of "some simple cycle earns at least t" is NP-hard in general (it
 * contains the longest-path problem); the gap above is the documented price of a scan that
 * costs at most (V+1) x V x E relaxations. Doubles are fine here: no order is priced or
 * matched in the scanner.
 */
public final class ArbitrageScanner {
  /** Minimum profit, in percent, for a cycle to count as arbitrage (doc 08: 0.001%). */
  public static final double DEFAULT_MIN_PROFIT_PERCENT = 0.001;

  private final String[] currencies;
  private final int n;
  private final int usd;
  private final double[][] rate;
  private final boolean[][] direct;
  private final double minProfitPercent;
  private final BellmanFord bf = new BellmanFord();

  public ArbitrageScanner(Directory directory) {
    this(directory, DEFAULT_MIN_PROFIT_PERCENT);
  }

  public ArbitrageScanner(Directory directory, double minProfitPercent) {
    this(currenciesOf(directory), minProfitPercent);
  }

  private static String[] currenciesOf(Directory directory) {
    String[] codes = new String[directory.currencyCount()];
    for (int i = 0; i < codes.length; i++) codes[i] = directory.currency(i);
    return codes;
  }

  /** A scanner over any list of three-letter currency codes; it must include USD. */
  public ArbitrageScanner(String[] currencyCodes, double minProfitPercent) {
    if (!(minProfitPercent >= 0)) {
      throw new IllegalArgumentException("minProfitPercent must be >= 0: " + minProfitPercent);
    }
    this.currencies = currencyCodes.clone();
    this.n = currencies.length;
    this.usd = currencyId("USD");
    if (usd < 0) {
      throw new IllegalArgumentException("the currency list must include USD");
    }
    this.rate = new double[n][n];
    this.direct = new boolean[n][n];
    this.minProfitPercent = minProfitPercent;
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < n; j++) rate[i][j] = Double.NaN;
    }
  }

  /** Applies a tick for "BASE/QUOTE" ({@code 1 BASE = price QUOTE}); false if unknown pair. */
  public boolean onTick(String pair, double price) {
    if (pair == null || pair.length() != 7 || pair.charAt(3) != '/') return false;
    int base = currencyId(pair.substring(0, 3));
    int quote = currencyId(pair.substring(4));
    if (base < 0 || quote < 0 || base == quote) return false;
    onTick(base, quote, price);
    return true;
  }

  /** Applies a tick: {@code 1 base = price quote}, and the reciprocal for the reverse. */
  public void onTick(int base, int quote, double price) {
    if (base == quote || base < 0 || quote < 0 || base >= n || quote >= n) {
      throw new IllegalArgumentException("bad currency pair " + base + "/" + quote);
    }
    if (!(price > 0) || Double.isInfinite(price)) {
      throw new IllegalArgumentException("rate must be a positive finite number: " + price);
    }
    rate[base][quote] = price;
    rate[quote][base] = 1.0 / price;
    direct[base][quote] = true;
    direct[quote][base] = true;
    deriveCrosses();
  }

  /** Recomputes every non-direct rate from the two USD legs, or NaN if a leg is missing. */
  private void deriveCrosses() {
    for (int a = 0; a < n; a++) {
      for (int b = 0; b < n; b++) {
        if (a == b || direct[a][b]) continue;
        if (a == usd || b == usd) {
          rate[a][b] = Double.NaN; // a USD leg exists only once ticked
          continue;
        }
        double ua = rate[usd][a];
        double ub = rate[usd][b];
        rate[a][b] = ua > 0 && ub > 0 ? ub / ua : Double.NaN;
      }
    }
  }

  /** Index of a currency code, or -1. */
  public int currencyId(String code) {
    for (int i = 0; i < currencies.length; i++) {
      if (currencies[i].equals(code)) return i;
    }
    return -1;
  }

  public int currencyCount() {
    return n;
  }

  public String currency(int id) {
    return currencies[id];
  }

  /** Units of {@code to} per one {@code from}, or NaN if unknown. */
  public double rate(int from, int to) {
    return rate[from][to];
  }

  /** True if the rate came from a tick (either direction), false if derived or unknown. */
  public boolean isDirect(int from, int to) {
    return direct[from][to];
  }

  public double minProfitPercent() {
    return minProfitPercent;
  }

  /** Builds the graph from every known rate and looks for a profitable cycle. */
  public ScanResult scan() {
    int edges = 0;
    for (int a = 0; a < n; a++) {
      for (int b = 0; b < n; b++) {
        if (a != b && rate[a][b] > 0) edges++;
      }
    }
    int[] from = new int[edges];
    int[] to = new int[edges];
    double[] base = new double[edges];
    int e = 0;
    for (int a = 0; a < n; a++) {
      for (int b = 0; b < n; b++) {
        if (a != b && rate[a][b] > 0) {
          from[e] = a;
          to[e] = b;
          base[e] = -Math.log(rate[a][b]);
          e++;
        }
      }
    }
    // Base pass on the unshifted weights. Every shifted weight is >= its base weight, so
    // if the base graph has no negative cycle no shifted pass can find one: the common
    // no-arbitrage case costs one Bellman-Ford run instead of V.
    bf.build(n, from, to, base);
    if (!bf.hasNegativeCycle()) {
      return new ScanResult(false, null, 1.0, 0.0, edges, false);
    }
    double gain = Math.log1p(minProfitPercent / 100.0);
    double[] weight = new double[edges];
    int[] best = null;
    double bestMultiplier = 0;
    boolean sawBelow = false;
    for (int pass = 2; pass <= n + 1; pass++) {
      double shift = gain / pass;
      for (int i = 0; i < edges; i++) weight[i] = base[i] + shift;
      bf.build(n, from, to, weight);
      if (!bf.hasNegativeCycle()) {
        continue;
      }
      int[] cycle = bf.reconstructCycle();
      if (cycle.length < 3) continue;
      double multiplier = 1.0;
      for (int i = 0; i + 1 < cycle.length; i++) multiplier *= rate[cycle[i]][cycle[i + 1]];
      if ((multiplier - 1.0) * 100.0 < minProfitPercent) {
        sawBelow = true;
      } else if (multiplier > bestMultiplier) {
        best = cycle;
        bestMultiplier = multiplier;
      }
    }
    if (best == null) {
      return new ScanResult(false, null, 1.0, 0.0, edges, sawBelow);
    }
    String[] names = new String[best.length];
    for (int i = 0; i < best.length; i++) names[i] = currencies[best[i]];
    return new ScanResult(true, names, bestMultiplier, (bestMultiplier - 1.0) * 100.0, edges,
        false);
  }
}
