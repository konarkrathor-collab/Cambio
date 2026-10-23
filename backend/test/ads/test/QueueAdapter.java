package ads.test;

import ads.bench.queue.QueueOps;
import ads.engine.Order;
import ads.engine.OrderIndex;
import ads.engine.PriceLevel;
import ads.engine.Side;

/**
 * Presents the production order queue (one {@link PriceLevel}'s doubly linked list plus
 * the global {@link OrderIndex}) as the benchmark's {@link QueueOps}. Cancel goes the way
 * the engine will: index lookup, then the order's own level pointer, then an O(1) unlink.
 */
final class QueueAdapter implements QueueOps {
  private final PriceLevel level = new PriceLevel(1);
  private final OrderIndex index = new OrderIndex();

  @Override
  public void enqueue(long orderId, long volume) {
    Order o = new Order(orderId, 0, Side.BUY, 0, 1, volume, orderId);
    level.append(o);
    index.put(o);
  }

  @Override
  public long dequeueHead() {
    Order o = level.head();
    if (o == null) return -1;
    level.unlink(o);
    index.remove(o.orderId());
    return o.orderId();
  }

  @Override
  public boolean cancel(long orderId) {
    Order o = index.remove(orderId);
    if (o == null) return false;
    o.level().unlink(o);
    return true;
  }

  @Override
  public long peekHead() {
    Order o = level.head();
    return o == null ? -1 : o.orderId();
  }

  @Override
  public int size() {
    return index.size();
  }

  /**
   * Verification only. Also cross-checks the two structures against each other: the
   * queue's order count and aggregate volume must agree with the index and the orders.
   */
  @Override
  public int dump(long[] orderIds, long[] volumes) {
    int i = 0;
    long sum = 0;
    for (Order o = level.head(); o != null; o = o.next()) {
      if (index.get(o.orderId()) != o) {
        throw new IllegalStateException("queued order " + o.orderId() + " not in the index");
      }
      orderIds[i] = o.orderId();
      volumes[i] = o.remaining();
      sum += o.remaining();
      i++;
    }
    if (i != level.orderCount() || i != index.size() || sum != level.totalVolume()) {
      throw new IllegalStateException("queue/index disagree: walked " + i + " orders, level"
          + " count " + level.orderCount() + ", index " + index.size() + ", volume " + sum
          + " vs " + level.totalVolume());
    }
    return i;
  }
}
