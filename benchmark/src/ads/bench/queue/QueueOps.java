package ads.bench.queue;

/**
 * CONTRACT (Phase 0): the per-price-level order queue interface every candidate must
 * satisfy. Mirrors the order queue from documentation doc 05 §2: FIFO execution at the
 * head (the matching loop) plus arbitrary cancellation by order id (a trader pulling their
 * order before it trades), with no scanning allowed on the hot paths.
 *
 * <p>Semantics (FROZEN; the oracle in {@link LinkedHashMapOracle} is the normative
 * reference):
 * <ul>
 *   <li>order ids are unique positive longs; each instance is created fresh for exactly
 *       one workload replay.</li>
 *   <li>{@code enqueue(orderId, volume)}: append a new order at the TAIL (arrival order).</li>
 *   <li>{@code dequeueHead()}: remove and return the id of the OLDEST live order (FIFO
 *       head), or -1 if the queue is empty.</li>
 *   <li>{@code peekHead()}: the FIFO head's id without removing it, or -1 if empty.</li>
 *   <li>{@code cancel(orderId)}: remove an arbitrary live order by id; returns true if it
 *       was present, false otherwise.</li>
 *   <li>{@code size()}: number of live orders.</li>
 *   <li>{@code dump(...)}: copy all live (orderId, volume) pairs into the given arrays in
 *       ANY order, return the count. Verification only, never timed. FIFO ORDER itself is
 *       validated by the replay checksum (dequeue and peek return values are all mixed
 *       in), not by dump ordering.</li>
 * </ul>
 */
public interface QueueOps {
  void enqueue(long orderId, long volume);

  long dequeueHead();

  boolean cancel(long orderId);

  long peekHead();

  int size();

  int dump(long[] orderIds, long[] volumes);
}
