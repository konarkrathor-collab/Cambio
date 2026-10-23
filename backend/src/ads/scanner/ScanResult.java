package ads.scanner;

/** One arbitrage scan: the profitable cycle (if any), its rate multiplier and profit %. */
public final class ScanResult {
  private static final String[] NONE = new String[0];

  final boolean hasArbitrage;
  final String[] cycle;
  final double multiplier;
  final double profitPercent;
  final int edges;
  final int excludedEdges;
  final boolean belowThreshold;

  ScanResult(boolean hasArbitrage, String[] cycle, double multiplier, double profitPercent,
      int edges, int excludedEdges, boolean belowThreshold) {
    this.hasArbitrage = hasArbitrage;
    this.cycle = cycle == null ? NONE : cycle;
    this.multiplier = multiplier;
    this.profitPercent = profitPercent;
    this.edges = edges;
    this.excludedEdges = excludedEdges;
    this.belowThreshold = belowThreshold;
  }

  public boolean hasArbitrage() {
    return hasArbitrage;
  }

  /** Currency codes along the loop, first == last (e.g. USD, EUR, GBP, USD); empty if none. */
  public String[] cycle() {
    return cycle.clone();
  }

  /** Product of the rates along the cycle (> 1 is a gain); 1.0 when there is none. */
  public double multiplier() {
    return multiplier;
  }

  /** {@code (multiplier - 1) x 100}; 0 when there is no arbitrage. */
  public double profitPercent() {
    return profitPercent;
  }

  /** Directed edges (known rates) in the graph that was scanned. */
  public int edges() {
    return edges;
  }

  /** Known rates left out of the scan because they (or a USD leg they derive from) were stale. */
  public int excludedEdges() {
    return excludedEdges;
  }

  /** True if a negative cycle was found but its profit was under the minimum threshold. */
  public boolean belowThreshold() {
    return belowThreshold;
  }
}
