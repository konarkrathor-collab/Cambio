package ads.scanner;

/**
 * Negative-cycle detector for the currency graph (doc 05 §3), ported from the benchmark's
 * {@code BellmanFordArbitrage}. Edge weights are {@code -ln(rate)}, so a negative cycle is
 * a conversion loop whose rates multiply to more than 1.
 *
 * <p>Relaxes all E edges exactly {@code V-1} times from a virtual zero-cost source (every
 * vertex starts at {@code dist=0}: the standard trick for detecting a cycle anywhere in
 * the graph, with no fixed start), then one more pass to find any edge that still relaxes.
 * Deterministic: identical work regardless of input order or whether a cycle exists.
 * Doubles are fine here: this is the scanner, not the matching path. No JDK collections.
 */
public final class BellmanFord {
  private int v;
  private int[] ef;
  private int[] et;
  private double[] ew;
  private double[] dist;
  private int[] pred;
  private int negVertex = -1;

  /**
   * Sets the graph: vertices {@code 0..vertexCount-1}, edges as three parallel arrays
   * ({@code edgeFrom[e] -> edgeTo[e]} with weight {@code edgeWeight[e]}). The arrays are
   * referenced, not copied.
   */
  public void build(int vertexCount, int[] edgeFrom, int[] edgeTo, double[] edgeWeight) {
    this.v = vertexCount;
    this.ef = edgeFrom;
    this.et = edgeTo;
    this.ew = edgeWeight;
    this.negVertex = -1;
  }

  /** True iff some directed cycle has strictly negative total weight. */
  public boolean hasNegativeCycle() {
    dist = new double[v];
    pred = new int[v];
    for (int i = 0; i < v; i++) {
      dist[i] = 0.0;
      pred[i] = -1;
    }
    int m = ef.length;
    for (int pass = 0; pass < v - 1; pass++) {
      for (int e = 0; e < m; e++) {
        int u = ef[e];
        int w = et[e];
        double nd = dist[u] + ew[e];
        if (nd < dist[w] - 1e-12) {
          dist[w] = nd;
          pred[w] = u;
        }
      }
    }
    negVertex = -1;
    for (int e = 0; e < m; e++) {
      int u = ef[e];
      int w = et[e];
      if (dist[u] + ew[e] < dist[w] - 1e-12) {
        pred[w] = u;
        negVertex = w;
        break;
      }
    }
    return negVertex >= 0;
  }

  /**
   * One negative cycle found by the last {@link #hasNegativeCycle()} call, as a vertex
   * sequence in forward edge order with {@code first == last}; empty if there was none.
   */
  public int[] reconstructCycle() {
    if (negVertex < 0) {
      return new int[0];
    }
    int x = negVertex;
    for (int i = 0; i < v; i++) {
      x = pred[x]; // walk back V steps to guarantee landing strictly inside the cycle
    }
    return CycleTrace.trace(pred, x);
  }
}
