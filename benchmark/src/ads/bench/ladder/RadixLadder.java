package ads.bench.ladder;

/**
 * CANDIDATE (doc 05 §1, the project pick): 16-ary nibble-bucketed radix tree over the
 * 64-bit tick key. Fixed depth 16 (4-bit nibbles) => search / insert / delete / best-price
 * peek are all bounded by a CONSTANT number of steps for a fixed key width - deterministic,
 * no rotations, no rebalancing (the "O(1)-for-fixed-key-width" claim in the docs).
 *
 * <p>Each node: lazily-allocated {@code children[16]} + a 16-bit child bitmap, so the
 * best-price walk reads the bitmap instead of scanning 16 children. Empty branches are
 * pruned eagerly on delete: memory scales with ACTIVE levels, not with the tick range.
 */
public final class RadixLadder implements LadderOps {
  private static final int DEPTH = 16;

  private static final class Node {
    Node[] children; // null until the first child arrives
    int bitmap;      // bit i set iff children[i] != null
    boolean hasValue;
    long volume;
  }

  private final Node root = new Node();
  private int count;

  // scratch path buffers reused by search/delete (depth <= 16; single-threaded harness)
  private final Node[] pathNodes = new Node[DEPTH];
  private final int[] pathNibs = new int[DEPTH];

  private static int nibble(long tick, int level) {
    return (int) ((tick >>> (60 - 4 * level)) & 0xF);
  }

  /** tick with nibble 'level' and everything below cleared. */
  private static long prefixAbove(long tick, int level) {
    if (level == 0) return 0L;
    int shift = 60 - 4 * level;
    return tick & ~((1L << (shift + 4)) - 1);
  }

  private static int higherSetBitAtLeast(int bitmap, int from) {
    if (from > 15) return -1;
    int m = bitmap & ~((1 << from) - 1);
    return m == 0 ? -1 : Integer.numberOfTrailingZeros(m);
  }

  private static int highestSetBitBelow(int bitmap, int below) {
    if (below <= 0) return -1;
    int m = bitmap & ((1 << below) - 1);
    return m == 0 ? -1 : 31 - Integer.numberOfLeadingZeros(m);
  }

  @Override
  public void put(long tick, long volume) {
    Node node = root;
    for (int l = 0; l < DEPTH; l++) {
      int b = nibble(tick, l);
      if ((node.bitmap & (1 << b)) == 0) {
        if (node.children == null) node.children = new Node[16];
        node.children[b] = new Node();
        node.bitmap |= 1 << b;
      }
      node = node.children[b];
    }
    if (!node.hasValue) {
      node.hasValue = true;
      count++;
    }
    node.volume = volume;
  }

  @Override
  public void remove(long tick) {
    Node node = root;
    for (int l = 0; l < DEPTH; l++) {
      int b = nibble(tick, l);
      if ((node.bitmap & (1 << b)) == 0) return; // absent
      pathNodes[l] = node;
      pathNibs[l] = b;
      node = node.children[b];
    }
    if (!node.hasValue) return;
    node.hasValue = false;
    node.volume = 0;
    count--;
    // prune empty nodes upward; stop at the first non-empty ancestor
    for (int l = DEPTH - 1; l >= 0; l--) {
      Node child = pathNodes[l].children[pathNibs[l]];
      if (child == null || child.hasValue || child.bitmap != 0) break;
      pathNodes[l].children[pathNibs[l]] = null;
      pathNodes[l].bitmap &= ~(1 << pathNibs[l]);
    }
  }

  private long findMin() {
    Node node = root;
    long tick = 0;
    for (int l = 0; l < DEPTH; l++) {
      int b = Integer.numberOfTrailingZeros(node.bitmap);
      if (b >= 16) return -1; // empty
      node = node.children[b];
      tick |= ((long) b) << (60 - 4 * l);
    }
    return node.hasValue ? tick : -1;
  }

  private long findMax() {
    Node node = root;
    long tick = 0;
    for (int l = 0; l < DEPTH; l++) {
      int b = 31 - Integer.numberOfLeadingZeros(node.bitmap);
      if (b < 0) return -1; // empty (bitmap == 0)
      node = node.children[b];
      tick |= ((long) b) << (60 - 4 * l);
    }
    return node.hasValue ? tick : -1;
  }

  @Override
  public long bestAsk() {
    return findMin();
  }

  @Override
  public long bestBid() {
    return findMax();
  }
  /** Smallest populated tick >= given, or -1. O(16) fixed. */
  private long successorGE(long tick) {
    Node node = root;
    int level = 0;
    for (; level < DEPTH; level++) {
      int b = nibble(tick, level);
      pathNodes[level] = node;
      pathNibs[level] = b;
      if ((node.bitmap & (1 << b)) == 0) break;
      node = node.children[b];
    }
    if (level == DEPTH && node.hasValue) return tick;
    int l = (level < DEPTH) ? level : DEPTH - 1;
    for (; l >= 0; l--) {
      int higher = higherSetBitAtLeast(pathNodes[l].bitmap, pathNibs[l] + 1);
      if (higher >= 0) {
        long acc = prefixAbove(tick, l) | (((long) higher) << (60 - 4 * l));
        Node sub = pathNodes[l].children[higher];
        for (int lv = l + 1; lv < DEPTH; lv++) {
          int b2 = Integer.numberOfTrailingZeros(sub.bitmap);
          sub = sub.children[b2];
          acc |= ((long) b2) << (60 - 4 * lv);
        }
        return sub.hasValue ? acc : -1;
      }
    }
    return -1;
  }

  /** Largest populated tick <= given, or -1. O(16) fixed. */
  private long predecessorLE(long tick) {
    Node node = root;
    int level = 0;
    for (; level < DEPTH; level++) {
      int b = nibble(tick, level);
      pathNodes[level] = node;
      pathNibs[level] = b;
      if ((node.bitmap & (1 << b)) == 0) break;
      node = node.children[b];
    }
    if (level == DEPTH && node.hasValue) return tick;
    int l = (level < DEPTH) ? level : DEPTH - 1;
    for (; l >= 0; l--) {
      int lower = highestSetBitBelow(pathNodes[l].bitmap, pathNibs[l]);
      if (lower >= 0) {
        long acc = prefixAbove(tick, l) | (((long) lower) << (60 - 4 * l));
        Node sub = pathNodes[l].children[lower];
        for (int lv = l + 1; lv < DEPTH; lv++) {
          int b2 = 31 - Integer.numberOfLeadingZeros(sub.bitmap);
          sub = sub.children[b2];
          acc |= ((long) b2) << (60 - 4 * lv);
        }
        return sub.hasValue ? acc : -1;
      }
    }
    return -1;
  }

  @Override
  public long walkUp(long fromTick, int k) {
    long sum = 0;
    long t = successorGE(fromTick);
    for (int i = 0; i < k && t >= 0; i++) {
      sum += t;
      t = successorGE(t + 1);
    }
    return sum;
  }

  @Override
  public long walkDown(long fromTick, int k) {
    long sum = 0;
    long t = predecessorLE(fromTick);
    for (int i = 0; i < k && t >= 0; i++) {
      sum += t;
      t = predecessorLE(t - 1);
    }
    return sum;
  }

  @Override
  public int size() {
    return count;
  }

  private int dumpIndex;

  @Override
  public int dump(long[] outTicks, long[] outVols) {
    dumpIndex = 0;
    dumpNode(root, 0L, 0, outTicks, outVols);
    return dumpIndex;
  }

  private void dumpNode(Node node, long acc, int level, long[] outTicks, long[] outVols) {
    if (node.hasValue) {
      outTicks[dumpIndex] = acc;
      outVols[dumpIndex] = node.volume;
      dumpIndex++;
    }
    if (node.bitmap == 0) return;
    for (int b = node.bitmap; b != 0; b &= b - 1) {
      int i = Integer.numberOfTrailingZeros(b);
      dumpNode(node.children[i], acc | (((long) i) << (60 - 4 * level)), level + 1,
          outTicks, outVols);
    }
  }
}

