package ads.bench.ladder;

import ads.bench.core.Rng;

/**
 * CANDIDATE (doc 05 §1): skip list (Redis-ZSET style) - O(log n) expected for all ops with
 * no rebalancing. Level decisions come from its own seeded RNG so runs are reproducible.
 */
public final class SkipListLadder implements LadderOps {
  private static final int MAX_LEVEL = 18;
  private static final double P = 0.25;

  private static final class Node {
    final long tick;
    long volume;
    final Node[] next;

    Node(long tick, long volume, int level) {
      this.tick = tick;
      this.volume = volume;
      this.next = new Node[level];
    }
  }

  private final Rng rng;
  private final Node head = new Node(Long.MIN_VALUE, 0, MAX_LEVEL);
  private int level = 1;
  private int count;

  public SkipListLadder(long seed) {
    this.rng = new Rng(seed);
  }

  private int randomLevel() {
    int lvl = 1;
    while (lvl < MAX_LEVEL && rng.nextDouble() < P) lvl++;
    return lvl;
  }

  @Override
  public void put(long tick, long volume) {
    Node[] update = new Node[MAX_LEVEL];
    Node x = head;
    for (int l = level - 1; l >= 0; l--) {
      while (x.next[l] != null && x.next[l].tick < tick) x = x.next[l];
      update[l] = x;
    }
    Node nxt = x.next[0];
    if (nxt != null && nxt.tick == tick) {
      nxt.volume = volume;
      return;
    }
    int lvl = randomLevel();
    if (lvl > level) {
      for (int l = level; l < lvl; l++) update[l] = head;
      level = lvl;
    }
    Node node = new Node(tick, volume, lvl);
    for (int l = 0; l < lvl; l++) {
      node.next[l] = update[l].next[l];
      update[l].next[l] = node;
    }
    count++;
  }

  @Override
  public void remove(long tick) {
    Node[] update = new Node[MAX_LEVEL];
    Node x = head;
    for (int l = level - 1; l >= 0; l--) {
      while (x.next[l] != null && x.next[l].tick < tick) x = x.next[l];
      update[l] = x;
    }
    Node target = x.next[0];
    if (target == null || target.tick != tick) return;
    for (int l = 0; l < target.next.length; l++) {
      update[l].next[l] = target.next[l];
    }
    while (level > 1 && head.next[level - 1] == null) level--;
    count--;
  }

  @Override
  public long bestAsk() {
    Node first = head.next[0];
    return first == null ? -1 : first.tick;
  }

  @Override
  public long bestBid() {
    Node x = head;
    for (int l = level - 1; l >= 0; l--) {
      while (x.next[l] != null) x = x.next[l];
    }
    return x == head ? -1 : x.tick;
  }

  /** First node with tick >= given, or null. */
  private Node findGE(long tick) {
    Node x = head;
    for (int l = level - 1; l >= 0; l--) {
      while (x.next[l] != null && x.next[l].tick < tick) x = x.next[l];
    }
    return x.next[0];
  }

  /** Greatest node with tick <= given, or null. */
  private Node findLE(long tick) {
    Node x = head;
    for (int l = level - 1; l >= 0; l--) {
      while (x.next[l] != null && x.next[l].tick <= tick) x = x.next[l];
    }
    return x == head ? null : x;
  }

  @Override
  public long walkUp(long fromTick, int k) {
    long sum = 0;
    Node c = findGE(fromTick);
    for (int i = 0; i < k && c != null; i++) {
      sum += c.tick;
      c = c.next[0];
    }
    return sum;
  }

  @Override
  public long walkDown(long fromTick, int k) {
    long sum = 0;
    Node c = findLE(fromTick);
    for (int i = 0; i < k && c != null; i++) {
      sum += c.tick;
      c = findLE(c.tick - 1); // no back pointers; repeated search is still O(k log n)
    }
    return sum;
  }

  @Override
  public int size() {
    return count;
  }

  @Override
  public int dump(long[] outTicks, long[] outVols) {
    int n = 0;
    for (Node c = head.next[0]; c != null; c = c.next[0]) {
      outTicks[n] = c.tick;
      outVols[n] = c.volume;
      n++;
    }
    return n;
  }
}
