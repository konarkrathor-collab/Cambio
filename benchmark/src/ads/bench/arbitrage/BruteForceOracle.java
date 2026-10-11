package ads.bench.arbitrage;

import java.util.ArrayList;
import java.util.List;

/**
 * ORACLE (independent of all 5 candidates): exhaustive DFS enumeration of every simple
 * directed cycle, directly summing real edge weights per candidate cycle — a completely
 * different algorithm family from shortest-path relaxation (Bellman-Ford/SPFA/Floyd-
 * Warshall/Johnson) or SCC decomposition (Tarjan), so agreement across all of them is real
 * evidence, not circular reasoning.
 *
 * <p>This is combinatorially infeasible beyond small graphs (simple-cycle counts grow
 * roughly factorially in a dense graph) — it is only ever invoked on the small correctness-
 * gated graphs (V &lt;= ~10), never on the larger scaling runs. {@code java.util} is allowed
 * here per the harness's fairness rule 6 (oracle + harness only, never inside a timed
 * candidate).
 */
public final class BruteForceOracle {
  private final int v;
  private final int[][] adjTo;
  private final double[][] adjW;
  private boolean found;
  private int[] foundCycle;

  public BruteForceOracle(int vertexCount, int[] edgeFrom, int[] edgeTo, double[] edgeWeight) {
    this.v = vertexCount;
    List<List<Integer>> to = new ArrayList<>();
    List<List<Double>> w = new ArrayList<>();
    for (int i = 0; i < v; i++) {
      to.add(new ArrayList<>());
      w.add(new ArrayList<>());
    }
    for (int e = 0; e < edgeFrom.length; e++) {
      to.get(edgeFrom[e]).add(edgeTo[e]);
      w.get(edgeFrom[e]).add(edgeWeight[e]);
    }
    adjTo = new int[v][];
    adjW = new double[v][];
    for (int i = 0; i < v; i++) {
      int sz = to.get(i).size();
      adjTo[i] = new int[sz];
      adjW[i] = new double[sz];
      for (int k = 0; k < sz; k++) {
        adjTo[i][k] = to.get(i).get(k);
        adjW[i][k] = w.get(i).get(k);
      }
    }
  }

  /** True iff some simple directed cycle has strictly negative total weight. */
  public boolean hasNegativeCycle() {
    found = false;
    foundCycle = null;
    boolean[] onPath = new boolean[v];
    int[] path = new int[v + 1];
    for (int start = 0; start < v && !found; start++) {
      dfs(start, start, 0.0, onPath, path, 0);
    }
    return found;
  }

  /** Valid only immediately after {@link #hasNegativeCycle()} returned true. */
  public int[] foundCycle() {
    return foundCycle;
  }

  private void dfs(int start, int u, double weightSoFar, boolean[] onPath, int[] path, int depth) {
    if (found) {
      return;
    }
    onPath[u] = true;
    path[depth] = u;
    for (int k = 0; k < adjTo[u].length; k++) {
      int w = adjTo[u][k];
      double nw = weightSoFar + adjW[u][k];
      if (w == start) {
        if (nw < -1e-9) {
          found = true;
          int[] cyc = new int[depth + 2];
          System.arraycopy(path, 0, cyc, 0, depth + 1);
          cyc[depth + 1] = start;
          foundCycle = cyc;
          onPath[u] = false;
          return;
        }
        continue; // closes a non-negative cycle back to start; not useful, keep searching
      }
      if (!onPath[w]) {
        dfs(start, w, nw, onPath, path, depth + 1);
        if (found) {
          return;
        }
      }
    }
    onPath[u] = false;
  }
}
