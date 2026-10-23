package ads.test;

import ads.bench.arbitrage.GraphOps;
import ads.scanner.BellmanFord;

/** Presents the production {@link BellmanFord} as the benchmark's {@link GraphOps}. */
final class GraphAdapter implements GraphOps {
  private final BellmanFord bf = new BellmanFord();

  @Override
  public void build(int vertexCount, int[] edgeFrom, int[] edgeTo, double[] edgeWeight) {
    bf.build(vertexCount, edgeFrom, edgeTo, edgeWeight);
  }

  @Override
  public boolean hasNegativeCycle() {
    return bf.hasNegativeCycle();
  }

  @Override
  public int[] reconstructCycle() {
    return bf.reconstructCycle();
  }
}
