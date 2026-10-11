package ads.bench.queue;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ORACLE (Phase 1): the normative reference for the queue contract, backed by a
 * {@code java.util.LinkedHashMap} in insertion order. Insertion order IS FIFO order here:
 * ids are unique, never re-inserted, and cancels/dequeues only remove entries, so the
 * first iteration-order entry is always the oldest live order.
 *
 * <p>{@code java.util} is allowed in the oracle only, per the fairness rules. Per this
 * module's brief, a JDK-hardened container is the accepted independence bar: every
 * candidate is hand-rolled, and any implementation bug (wrong head order, leaked nodes,
 * broken cancel lookup) surfaces as a checksum or final-state mismatch against this
 * reference.
 */
public final class LinkedHashMapOracle implements QueueOps {
  private final LinkedHashMap<Long, Long> q = new LinkedHashMap<>();

  @Override
  public void enqueue(long orderId, long volume) {
    q.put(orderId, volume);
  }

  @Override
  public long dequeueHead() {
    Iterator<Map.Entry<Long, Long>> it = q.entrySet().iterator();
    if (!it.hasNext()) return -1;
    long id = it.next().getKey();
    it.remove();
    return id;
  }

  @Override
  public boolean cancel(long orderId) {
    return q.remove(orderId) != null;
  }

  @Override
  public long peekHead() {
    Iterator<Map.Entry<Long, Long>> it = q.entrySet().iterator();
    return it.hasNext() ? it.next().getKey() : -1;
  }

  @Override
  public int size() {
    return q.size();
  }

  @Override
  public int dump(long[] orderIds, long[] volumes) {
    int i = 0;
    for (Map.Entry<Long, Long> e : q.entrySet()) {
      orderIds[i] = e.getKey();
      volumes[i] = e.getValue();
      i++;
    }
    return i;
  }
}
