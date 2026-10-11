package ads.bench.ladder;

/**
 * CONTRACT (Phase 0): the price-ladder interface every candidate structure must satisfy.
 * Mirrors the order-book price ladder from documentation docs 02/05: a set of ACTIVE price
 * levels, each with an aggregate volume, plus best-price peeks and K-level depth walks.
 *
 * <p>Semantics (FROZEN; the oracle in {@link TreeMapLadder} is the normative reference):
 * <ul>
 *   <li>ticks are positive longs inside a bounded band; each instance is created fresh
 *       for exactly one workload replay.</li>
 *   <li>{@code put(tick, volume)}: insert a new level or OVERWRITE the volume of an
 *       existing one.</li>
 *   <li>{@code remove(tick)}: precondition - {@code tick} is currently populated; the
 *       level disappears.</li>
 *   <li>{@code bestAsk()}: smallest populated tick (best sell), or -1 if empty.</li>
 *   <li>{@code bestBid()}: largest populated tick (best buy), or -1 if empty.</li>
 *   <li>{@code walkUp(fromTick, k)}: sum of tick VALUES of the k smallest populated ticks
 *       that are {@code >= fromTick} (fewer if not enough exist); 0 if none.</li>
 *   <li>{@code walkDown(fromTick, k)}: mirror image, {@code <= fromTick}, largest first.</li>
 *   <li>{@code size()}: number of populated levels.</li>
 *   <li>{@code dump(...)}: copy all (tick, volume) pairs into the given arrays in ANY
 *       order, return the count. Used only by the validation harness, never timed.</li>
 * </ul>
 */
public interface LadderOps {
  void put(long tick, long volume);

  void remove(long tick);

  long bestAsk();

  long bestBid();

  long walkUp(long fromTick, int k);

  long walkDown(long fromTick, int k);

  int size();

  int dump(long[] ticks, long[] volumes);
}
