package ads.engine;

/**
 * The engine-wide order index: {@code orderId -> Order} for every resting order of every
 * pair (order ids are global, and a cancel carries only the id).
 * A hand-rolled separate-chaining hash table whose chains run through the orders
 * themselves ({@link Order#hnext}), so indexing an order allocates nothing beyond table
 * growth. Power-of-two buckets, doubled when the load exceeds 0.75. Hash function and
 * growth policy are the benchmark's {@code DllHashQueueOps}. No JDK collections.
 */
public final class OrderIndex {
  private Order[] table;
  private int mask;
  private int threshold;
  private int count;

  public OrderIndex() {
    table = new Order[16];
    mask = 15;
    threshold = 12;
  }

  private static int mix(long id, int mask) {
    long h = id * 0x9E3779B97F4A7C15L;
    h ^= h >>> 32;
    h *= 0xBF58476D1CE4E5B9L;
    h ^= h >>> 29;
    return (int) h & mask;
  }

  /** Indexes an order. Precondition: no order with the same id is indexed. */
  public void put(Order o) {
    int b = mix(o.orderId, mask);
    o.hnext = table[b];
    table[b] = o;
    count++;
    if (count > threshold) {
      grow();
    }
  }

  /** The indexed order with this id, or null. */
  public Order get(long orderId) {
    for (Order o = table[mix(orderId, mask)]; o != null; o = o.hnext) {
      if (o.orderId == orderId) return o;
    }
    return null;
  }

  /** Removes and returns the order with this id, or null if none is indexed. */
  public Order remove(long orderId) {
    int b = mix(orderId, mask);
    Order prev = null;
    for (Order o = table[b]; o != null; prev = o, o = o.hnext) {
      if (o.orderId == orderId) {
        if (prev == null) table[b] = o.hnext;
        else prev.hnext = o.hnext;
        o.hnext = null;
        count--;
        return o;
      }
    }
    return null;
  }

  public int size() {
    return count;
  }

  private void grow() {
    Order[] nt = new Order[table.length * 2];
    int nm = nt.length - 1;
    for (int b = 0; b < table.length; b++) {
      Order o = table[b];
      while (o != null) {
        Order nxt = o.hnext;
        int nb = mix(o.orderId, nm);
        o.hnext = nt[nb];
        nt[nb] = o;
        o = nxt;
      }
    }
    table = nt;
    mask = nm;
    threshold = (int) (nt.length * 0.75);
  }
}
