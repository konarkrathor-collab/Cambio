package ads.engine;

/**
 * One resting order, and at the same time the node that threads it through two intrusive
 * structures: its price level's FIFO queue ({@code prev}/{@code next}) and the global
 * {@link OrderIndex} bucket chain ({@code hnext}). Holding both link sets in one node is
 * what makes cancel O(1): the index finds the node, the node's {@code level} pointer finds
 * the queue, and unlinking needs no scan. Ported from the benchmark's
 * {@code DllHashQueueOps.Node} (doc 05 §2), plus the level back-pointer doc 08 Stage 0
 * asks for. Stage 1 adds side, trader and order type.
 */
public final class Order {
  final long orderId;
  long remaining;
  PriceLevel level; // the level whose queue holds this order; null when not resting
  Order prev;       // queue links (FIFO order within the level)
  Order next;
  Order hnext;      // OrderIndex bucket chain link

  public Order(long orderId, long quantity) {
    this.orderId = orderId;
    this.remaining = quantity;
  }

  public long orderId() {
    return orderId;
  }

  /** Unfilled quantity. */
  public long remaining() {
    return remaining;
  }

  /** The price level this order rests at, or null if it is not resting. */
  public PriceLevel level() {
    return level;
  }

  /** The next (younger) order at the same price level, or null at the tail. */
  public Order next() {
    return next;
  }
}
