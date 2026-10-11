package ads.bench.arbitrage;

/**
 * CANDIDATE (doc 05 §3, the strongest rejected alternative): queue-driven Bellman-Ford
 * variant (Shortest Path Faster Algorithm). Only re-examines vertices whose distance was
 * just improved instead of blindly relaxing every edge {@code V-1} times; detects a
 * negative cycle the instant a vertex is enqueued {@code >= V} times. Same worst-case
 * O(V*E) bound as Bellman-Ford, but — the reason doc 05 ultimately preferred Bellman-Ford —
 * its running time depends on queue dynamics, not a fixed pass count.
 *
 * <p>No {@code java.util} containers: the FIFO queue is a plain circular array. Its capacity
 * only needs to be {@code V+1}, since a vertex can never be in the queue more than once at a
 * time (checked via {@code inQueue[]} before pushing) — the total LIFETIME push count can be
 * much larger, but concurrent queue occupancy is bounded by V.
 */
public final class SpfaArbitrage implements GraphOps {
  private int v;
  private int[] outStart;
  private int[] adjTo;
  private double[] adjW;
  private double[] dist;
  private int[] pred;
  private int negVertex = -1;

  @Override
  public void build(int vertexCount, int[] edgeFrom, int[] edgeTo, double[] edgeWeight) {
    this.v = vertexCount;
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
    adjW = new double[e];
    int[] cursor = new int[v];
    System.arraycopy(outStart, 0, cursor, 0, v);
    for (int i = 0; i < e; i++) {
      int u = edgeFrom[i];
      int pos = cursor[u]++;
      adjTo[pos] = edgeTo[i];
      adjW[pos] = edgeWeight[i];
    }
  }

  @Override
  public boolean hasNegativeCycle() {
    dist = new double[v];
    pred = new int[v];
    int[] visitCount = new int[v];
    boolean[] inQueue = new boolean[v];
    int cap = v + 1;
    int[] queue = new int[cap];
    int qHead = 0;
    int qTail = 0;
    int qSize = 0;

    for (int i = 0; i < v; i++) {
      dist[i] = 0.0;
      pred[i] = -1;
      queue[qTail] = i;
      qTail = (qTail + 1) % cap;
      qSize++;
      inQueue[i] = true;
      visitCount[i] = 1;
    }

    negVertex = -1;
    while (qSize > 0) {
      int u = queue[qHead];
      qHead = (qHead + 1) % cap;
      qSize--;
      inQueue[u] = false;
      for (int k = outStart[u]; k < outStart[u + 1]; k++) {
        int w = adjTo[k];
        double nd = dist[u] + adjW[k];
        if (nd < dist[w] - 1e-12) {
          dist[w] = nd;
          pred[w] = u;
          if (!inQueue[w]) {
            queue[qTail] = w;
            qTail = (qTail + 1) % cap;
            qSize++;
            inQueue[w] = true;
            visitCount[w]++;
            if (visitCount[w] >= v) {
              negVertex = w;
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  @Override
  public int[] reconstructCycle() {
    if (negVertex < 0) {
      return new int[0];
    }
    int x = negVertex;
    for (int i = 0; i < v; i++) {
      x = pred[x];
    }
    return CycleTrace.trace(pred, x);
  }
}
