package ads.engine;

/**
 * One resting order, and at the same time the node that threads it through two intrusive
 * structures: its price level's FIFO queue ({@code prev}/{@code next}) and the engine-wide
 * {@link OrderIndex} bucket chain ({@code hnext}). Holding both link sets in one node is
 * what makes cancel O(1): the index finds the node, the node's {@code level} pointer finds
 * the queue, and its pair id and side find the ladder to prune if the level empties.
 *
 * <p>{@code remaining} is changed ONLY by {@link PriceLevel#fill}, which updates the level's
 * aggregate volume in the same step, so the two cannot drift apart.
 */
public final class Order {
  final long orderId;
  final int pairId;
  final Side side;
  final long traderId;
  final long priceTick;
  final long seq;   // engine sequence number at arrival
  long remaining;   // written only by PriceLevel.fill
  PriceLevel level; // the level whose queue holds this order; null when not resting
  Order prev;       // queue links (FIFO order within the level)
  Order next;
  Order hnext;      // OrderIndex bucket chain link

  public Order(long orderId, int pairId, Side side, long traderId, long priceTick,
      long quantity, long seq) {
    this.orderId = orderId;
    this.pairId = pairId;
    this.side = side;
    this.traderId = traderId;
    this.priceTick = priceTick;
    this.remaining = quantity;
    this.seq = seq;
  }

  public long orderId() {
    return orderId;
  }

  public int pairId() {
    return pairId;
  }

  public Side side() {
    return side;
  }

  public long traderId() {
    return traderId;
  }

  public long priceTick() {
    return priceTick;
  }

  public long seq() {
    return seq;
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
