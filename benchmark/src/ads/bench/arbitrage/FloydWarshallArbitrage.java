package ads.bench.arbitrage;

/**
 * CANDIDATE (doc 05 §3, rejected: O(V^3) overkill for a sparse-relative-to-dense workload):
 * all-pairs shortest paths via dynamic programming. A negative cycle exists iff
 * {@code dist[i][i] < 0} for some vertex {@code i} after relaxing through every possible
 * intermediate vertex. Reconstructs a cycle via the standard {@code next[][]} path matrix.
 */
public final class FloydWarshallArbitrage implements GraphOps {
  private int v;
  private double[][] dist;
  private int[][] next;
  private int negVertex = -1;

  @Override
  public void build(int vertexCount, int[] edgeFrom, int[] edgeTo, double[] edgeWeight) {
    this.v = vertexCount;
    dist = new double[v][v];
    next = new int[v][v];
    for (int i = 0; i < v; i++) {
      for (int j = 0; j < v; j++) {
        dist[i][j] = (i == j) ? 0.0 : Double.POSITIVE_INFINITY;
        next[i][j] = -1;
      }
    }
    for (int e = 0; e < edgeFrom.length; e++) {
      int u = edgeFrom[e];
      int w = edgeTo[e];
      if (edgeWeight[e] < dist[u][w]) {
        dist[u][w] = edgeWeight[e];
        next[u][w] = w;
      }
    }
  }

  @Override
  public boolean hasNegativeCycle() {
    for (int k = 0; k < v; k++) {
      double[] distK = dist[k];
      for (int i = 0; i < v; i++) {
        double dik = dist[i][k];
        if (dik == Double.POSITIVE_INFINITY) {
          continue;
        }
        double[] distI = dist[i];
        for (int j = 0; j < v; j++) {
          double dkj = distK[j];
          if (dkj == Double.POSITIVE_INFINITY) {
            continue;
          }
          double nd = dik + dkj;
          if (nd < distI[j] - 1e-12) {
            distI[j] = nd;
            next[i][j] = next[i][k];
          }
        }
      }
    }
    negVertex = -1;
    for (int i = 0; i < v; i++) {
      if (dist[i][i] < -1e-9) {
        negVertex = i;
        break;
      }
    }
    return negVertex >= 0;
  }

  /**
   * NOTE on a subtlety that cost a debugging round: in a complete (strongly-connected) graph,
   * once ANY negative cycle exists anywhere, every vertex that can reach it and be reached
   * from it also gets {@code dist[i][i] < 0} — a negative closed WALK through {@code i}, not
   * necessarily a simple cycle AT {@code i}. Naively walking {@code next[][]} from
   * {@code negVertex} back to itself can therefore oscillate forever inside some OTHER
   * embedded cycle without ever returning to the start. The fix: walk forward via
   * {@code next[][]} collecting visited vertices until ANY vertex repeats (not necessarily
   * {@code negVertex} itself), then extract just that repeated sub-loop — {@code negVertex}
   * is only an entry point into some reachable negative cycle, not a promise the cycle passes
   * through it.
   */
  @Override
  public int[] reconstructCycle() {
    if (negVertex < 0) {
      return new int[0];
    }
    boolean[] seen = new boolean[v];
    int[] stepIndex = new int[v];
    for (int i = 0; i < v; i++) {
      stepIndex[i] = -1;
    }
    int[] order = new int[v + 1];
    int cur = negVertex;
    int step = 0;
    while (!seen[cur]) {
      seen[cur] = true;
      stepIndex[cur] = step;
      order[step] = cur;
      step++;
      cur = next[cur][negVertex];
      if (cur < 0 || step > v) {
        return new int[0]; // malformed; should not happen if dist[negVertex][negVertex] < 0
      }
    }
    int startIdx = stepIndex[cur];
    int len = step - startIdx;
    int[] result = new int[len + 1];
    for (int i = 0; i < len; i++) {
      result[i] = order[startIdx + i]; // already forward order: next[][] steps toward negVertex
    }
    result[len] = result[0];
    return result;
  }
}
