package ads.bench.arbitrage;

/**
 * CANDIDATE (doc 05 §3, the project pick): classic Bellman-Ford negative-cycle detector.
 * Relaxes all E edges exactly {@code V-1} times from a virtual zero-cost source (every
 * vertex starts at {@code dist=0}, equivalent to adding a 0-weight edge from an imaginary
 * source to every vertex — the standard trick for cycle detection with no fixed start),
 * then one more pass to find any edge that still relaxes. Deterministic: identical work
 * regardless of input order or whether a cycle exists at all.
 */
public final class BellmanFordArbitrage implements GraphOps {
  private int v;
  private int[] ef;
  private int[] et;
  private double[] ew;
  private double[] dist;
  private int[] pred;
  private int negVertex = -1;

  @Override
  public void build(int vertexCount, int[] edgeFrom, int[] edgeTo, double[] edgeWeight) {
    this.v = vertexCount;
    this.ef = edgeFrom;
    this.et = edgeTo;
    this.ew = edgeWeight;
  }

  @Override
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

  @Override
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
