package ads.bench.arbitrage;

/**
 * CANDIDATE (doc 05 §3, rejected: "doesn't natively carry edge weights through the
 * detection"): Tarjan's strongly-connected-components algorithm (O(V+E)) partitions the
 * graph, then a weighted Bellman-Ford pass restricted to each non-trivial component (size
 * {@code >= 2}) checks for a negative cycle within it. On a currency graph where every pair
 * quotes a rate, the whole graph is typically ONE strongly-connected component — so this
 * degenerates to a full Bellman-Ford pass anyway, PLUS the SCC-finding overhead on top. This
 * benchmark exists to confirm that "doesn't help here" claim with real numbers.
 *
 * <p>No {@code java.util}: Tarjan's own index/lowlink/on-stack bookkeeping and the
 * restricted per-component Bellman-Ford both use plain arrays.
 */
public final class TarjanArbitrage implements GraphOps {
  private int v;
  private int[] ef;
  private int[] et;
  private double[] ew;
  private int[] outStart;
  private int[] adjTo;

  @Override
  public void build(int vertexCount, int[] edgeFrom, int[] edgeTo, double[] edgeWeight) {
    this.v = vertexCount;
    this.ef = edgeFrom;
    this.et = edgeTo;
    this.ew = edgeWeight;
    int e = edgeFrom.length;
    int[] deg = new int[v + 1];
    for (int i = 0; i < e; i++) {
      deg[edgeFrom[i] + 1]++;
    }
    for (int i = 0; i < v; i++) {
      deg[i + 1] += deg[i];
    }
    outStart = deg;
    adjTo = new int[e];
    int[] cursor = new int[v];
    System.arraycopy(outStart, 0, cursor, 0, v);
    for (int i = 0; i < e; i++) {
      int u = edgeFrom[i];
      adjTo[cursor[u]++] = edgeTo[i];
    }
  }

  // Tarjan SCC state
  private int[] index_;
  private int[] lowlink;
  private boolean[] onStack;
  private int[] tstack;
  private int tsp;
  private int counter;
  private int[] comp;
  private int numComp;

  // result
  private int[] negComponentCycle;

  @Override
  public boolean hasNegativeCycle() {
    index_ = new int[v];
    lowlink = new int[v];
    onStack = new boolean[v];
    tstack = new int[v];
    tsp = 0;
    comp = new int[v];
    for (int i = 0; i < v; i++) {
      index_[i] = -1;
    }
    counter = 0;
    numComp = 0;
    negComponentCycle = null;

    for (int i = 0; i < v; i++) {
      if (index_[i] == -1) {
        strongConnect(i);
      }
    }

    int[] compSize = new int[numComp];
    for (int i = 0; i < v; i++) {
      compSize[comp[i]]++;
    }
    for (int c = 0; c < numComp; c++) {
      if (compSize[c] < 2) {
        continue;
      }
      if (checkComponentForNegativeCycle(c)) {
        return true;
      }
    }
    return false;
  }

  private void strongConnect(int u) {
    index_[u] = counter;
    lowlink[u] = counter;
    counter++;
    tstack[tsp++] = u;
    onStack[u] = true;
    for (int k = outStart[u]; k < outStart[u + 1]; k++) {
      int w = adjTo[k];
      if (index_[w] == -1) {
        strongConnect(w);
        lowlink[u] = Math.min(lowlink[u], lowlink[w]);
      } else if (onStack[w]) {
        lowlink[u] = Math.min(lowlink[u], index_[w]);
      }
    }
    if (lowlink[u] == index_[u]) {
      int c = numComp++;
      int w;
      do {
        w = tstack[--tsp];
        onStack[w] = false;
        comp[w] = c;
      } while (w != u);
    }
  }

  private boolean checkComponentForNegativeCycle(int c) {
    int memberCount = 0;
    int[] member = new int[v];
    int[] localId = new int[v];
    for (int i = 0; i < v; i++) {
      localId[i] = -1;
    }
    for (int i = 0; i < v; i++) {
      if (comp[i] == c) {
        localId[i] = memberCount;
        member[memberCount++] = i;
      }
    }

    int m = ef.length;
    int[] tmpFrom = new int[m];
    int[] tmpTo = new int[m];
    double[] tmpW = new double[m];
    int ecount = 0;
    for (int e = 0; e < m; e++) {
      if (comp[ef[e]] == c && comp[et[e]] == c) {
        tmpFrom[ecount] = localId[ef[e]];
        tmpTo[ecount] = localId[et[e]];
        tmpW[ecount] = ew[e];
        ecount++;
      }
    }

    double[] dist = new double[memberCount];
    int[] pred = new int[memberCount];
    for (int i = 0; i < memberCount; i++) {
      dist[i] = 0.0;
      pred[i] = -1;
    }
    for (int pass = 0; pass < memberCount - 1; pass++) {
      for (int e = 0; e < ecount; e++) {
        int u = tmpFrom[e];
        int w = tmpTo[e];
        double nd = dist[u] + tmpW[e];
        if (nd < dist[w] - 1e-12) {
          dist[w] = nd;
          pred[w] = u;
        }
      }
    }
    int negLocal = -1;
    for (int e = 0; e < ecount; e++) {
      int u = tmpFrom[e];
      int w = tmpTo[e];
      if (dist[u] + tmpW[e] < dist[w] - 1e-12) {
        pred[w] = u;
        negLocal = w;
        break;
      }
    }
    if (negLocal < 0) {
      return false;
    }
    int x = negLocal;
    for (int i = 0; i < memberCount; i++) {
      x = pred[x];
    }
    int[] localCycle = CycleTrace.trace(pred, x);
    negComponentCycle = new int[localCycle.length];
    for (int i = 0; i < localCycle.length; i++) {
      negComponentCycle[i] = member[localCycle[i]];
    }
    return true;
  }

  @Override
  public int[] reconstructCycle() {
    return negComponentCycle == null ? new int[0] : negComponentCycle;
  }
}
