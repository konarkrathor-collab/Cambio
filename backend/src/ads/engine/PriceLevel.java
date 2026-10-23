package ads.engine;

/**
 * One active price on one side of a book: the value stored at each {@link PriceLadder}
 * leaf. Holds the level's tick, its aggregate resting volume, and the head/tail of the
 * doubly linked FIFO queue of {@link Order}s resting at that tick (doc 05 §2: oldest at
 * the head, which is where the matching loop executes). No JDK collections.
 */
public final class PriceLevel {
  final long price;
  private long totalVolume;
  private Order head;
  private Order tail;
  private int orderCount;

  public PriceLevel(long price) {
    this.price = price;
  }

  /** The level's price in integer ticks. */
  public long price() {
    return price;
  }

  /** Sum of the remaining quantity of every order queued here. */
  public long totalVolume() {
    return totalVolume;
  }

  /**
   * Adjusts the aggregate volume without touching the queue — for partial fills, where
   * the head order's remaining quantity shrinks but it stays queued.
   */
  public void adjustVolume(long delta) {
    totalVolume += delta;
  }

  public int orderCount() {
    return orderCount;
  }

  public boolean isEmpty() {
    return head == null;
  }

  /** The oldest order (FIFO head), or null if the level is empty. */
  public Order head() {
    return head;
  }

  public Order tail() {
    return tail;
  }

  /** Appends at the tail (arrival order) and adds its remaining quantity to the level. */
  public void append(Order o) {
    if (o.level != null) {
      throw new IllegalStateException("order " + o.orderId + " is already queued");
    }
    o.level = this;
    o.next = null;
    o.prev = tail;
    if (tail == null) {
      head = o;
    } else {
      tail.next = o;
    }
    tail = o;
    orderCount++;
    totalVolume += o.remaining;
  }

  /** Unlinks an order queued at THIS level in O(1) and removes its remaining quantity. */
  public void unlink(Order o) {
    if (o.level != this) {
      throw new IllegalStateException("order " + o.orderId + " is not queued at " + price);
    }
    if (o.prev == null) head = o.next;
    else o.prev.next = o.next;
    if (o.next == null) tail = o.prev;
    else o.next.prev = o.prev;
    o.prev = null;
    o.next = null;
    o.level = null;
    orderCount--;
    totalVolume -= o.remaining;
  }
}
