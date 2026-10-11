package ads.bench.arbitrage;

/**
 * CANDIDATE (doc 05 §3, rejected: "built for a problem we don't have"): Johnson's algorithm
 * reweights every edge using potentials computed by ONE Bellman-Ford pass from a virtual
 * source, then runs Dijkstra from every vertex to get all-pairs shortest paths. The
 * negative-cycle check happens entirely inside that first Bellman-Ford pass — if it detects
 * one, Johnson's algorithm is undefined/aborts, because its reweighting trick assumes no
 * negative cycle exists. The subsequent "Dijkstra from every vertex" phase answers an
 * all-pairs question this project's single-cycle-detection contract never asks.
 *
 * <p>We therefore measure exactly the detection-relevant portion — which is, honestly,
 * identical work to {@link BellmanFordArbitrage}. This class exists to confirm that with
 * real numbers rather than assert it from the rubric alone: composition over duplication,
 * so "Johnson's" and "Bellman-Ford" rows in the results should land at essentially the same
 * cost, which is itself the finding.
 */
public final class JohnsonArbitrage implements GraphOps {
  private final BellmanFordArbitrage inner = new BellmanFordArbitrage();

  @Override
  public void build(int vertexCount, int[] edgeFrom, int[] edgeTo, double[] edgeWeight) {
    inner.build(vertexCount, edgeFrom, edgeTo, edgeWeight);
  }

  @Override
  public boolean hasNegativeCycle() {
    return inner.hasNegativeCycle();
  }

  @Override
  public int[] reconstructCycle() {
    return inner.reconstructCycle();
  }
}
