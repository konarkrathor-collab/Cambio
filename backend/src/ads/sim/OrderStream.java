package ads.sim;

/**
 * A recorded command stream: what the engine and the naive baseline are both fed, so they
 * see the identical sequence. Parallel arrays, one entry per operation.
 */
public final class OrderStream {
  public static final byte SUBMIT = 0;
  public static final byte CANCEL = 1;

  final int size;
  final byte[] op;
  final int[] trader;
  final int[] pair;     // -1 for an unknown pair
  final boolean[] buy;
  final boolean[] market;
  final long[] price;   // ticks (ignored for MARKET)
  final long[] quantity;
  final long[] target;  // the order id a CANCEL names

  OrderStream(int size) {
    this.size = size;
    op = new byte[size];
    trader = new int[size];
    pair = new int[size];
    buy = new boolean[size];
    market = new boolean[size];
    price = new long[size];
    quantity = new long[size];
    target = new long[size];
  }

  public int size() {
    return size;
  }
}
