package ads.bench.cache;

/**
 * CANDIDATE (doc 05 §4): plain separate-chaining hash map with NO eviction — the
 * "unbounded memory" reference point the rubric rejected at 3.90. Lookups are as cheap as
 * they get; the cost is that every account ever touched stays resident forever (the
 * results table's finalSize column shows it growing far past capacity).
 */
public final class ChainHashCache implements CacheOps {
  private static final class Node {
    final long id;
    final long balance;
    Node next;

    Node(long id, long balance, Node next) {
      this.id = id;
      this.balance = balance;
      this.next = next;
    }
  }

  private Node[] table = new Node[16];
  private int mask = 15;
  private int threshold = 12;
  private int count;

  private static int mix(long id, int mask) {
    long h = id * 0x9E3779B97F4A7C15L;
    h ^= h >>> 32;
    h *= 0xBF58476D1CE4E5B9L;
    h ^= h >>> 29;
    return (int) h & mask;
  }

  @Override
  public long access(long traderId) {
    int b = mix(traderId, mask);
    for (Node n = table[b]; n != null; n = n.next) {
      if (n.id == traderId) return n.balance;
    }
    long bal = AccountStore.balance(traderId);
    table[b] = new Node(traderId, bal, table[b]);
    count++;
    if (count > threshold) grow();
    return bal;
  }

  private void grow() {
    Node[] nt = new Node[table.length * 2];
    int nm = nt.length - 1;
    for (int b = 0; b < table.length; b++) {
      Node n = table[b];
      while (n != null) {
        Node nxt = n.next;
        int nb = mix(n.id, nm);
        n.next = nt[nb];
        nt[nb] = n;
        n = nxt;
      }
    }
    table = nt;
    mask = nm;
    threshold = (int) (nt.length * 0.75);
  }

  @Override
  public boolean contains(long traderId) {
    for (Node n = table[mix(traderId, mask)]; n != null; n = n.next) {
      if (n.id == traderId) return true;
    }
    return false;
  }

  @Override
  public int size() {
    return count;
  }
}
