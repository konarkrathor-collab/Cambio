package ads.bench.arbitrage;

/**
 * CONTRACT (Phase 0): the negative-weight-cycle detector interface every candidate algorithm
 * must satisfy. Mirrors the Currency Loophole Detector from documentation docs 02/03/05: a
 * directed weighted graph where {@code weight = -ln(exchangeRate)}, and a negative cycle
 * corresponds to a profitable triangular-arbitrage loop.
 *
 * <p>Semantics (FROZEN):
 * <ul>
 *   <li>{@code build(...)}: vertices are {@code 0..vertexCount-1}; edges are given as three
 *       parallel arrays. An instance is built fresh for exactly one workload.</li>
 *   <li>{@code hasNegativeCycle()}: true iff some directed cycle has strictly negative total
 *       weight (i.e. a currency conversion loop with net multiplicative gain {@code > 1}).</li>
 *   <li>{@code reconstructCycle()}: ANY one valid negative cycle as a vertex sequence,
 *       {@code first == last}; empty array if {@code hasNegativeCycle()} is false. "Any
 *       valid cycle" is acceptable — different algorithms may legitimately find different
 *       ones when more than one exists. Only called for verification, never timed.</li>
 * </ul>
 */
public interface GraphOps {
  void build(int vertexCount, int[] edgeFrom, int[] edgeTo, double[] edgeWeight);

  boolean hasNegativeCycle();

  int[] reconstructCycle();
}
