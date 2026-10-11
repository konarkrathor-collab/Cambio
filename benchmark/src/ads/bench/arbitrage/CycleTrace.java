package ads.bench.arbitrage;

/**
 * Shared predecessor-chain cycle extraction for the Bellman-Ford family of detectors
 * (Bellman-Ford, SPFA, Johnson). Given a predecessor array and a vertex known to lie on or
 * be reachable from a negative cycle, walks {@code pred[]} backward until a vertex repeats,
 * then returns the closed loop in FORWARD edge order (so {@code cycle[i] -> cycle[i+1]} is
 * always a real directed edge in the graph — important, since {@code pred[]} itself encodes
 * the relation in the opposite direction).
 */
final class CycleTrace {
  private CycleTrace() {
  }

  static int[] trace(int[] pred, int start) {
    int n = pred.length;
    boolean[] seen = new boolean[n];
    int[] stepIndex = new int[n];
    for (int i = 0; i < n; i++) {
      stepIndex[i] = -1;
    }
    int[] backward = new int[n + 1];
    int x = start;
    int step = 0;
    while (!seen[x]) {
      seen[x] = true;
      stepIndex[x] = step;
      backward[step] = x;
      step++;
      x = pred[x];
      if (x < 0 || step > n) {
        return new int[0]; // malformed predecessor chain; should not happen if hasNegativeCycle() == true
      }
    }
    int startIdx = stepIndex[x];
    int cycleLen = step - startIdx; // distinct vertices in the cycle
    int[] forward = new int[cycleLen + 1];
    for (int i = 0; i < cycleLen; i++) {
      forward[i] = backward[step - 1 - i];
    }
    forward[cycleLen] = forward[0];
    return forward;
  }
}
