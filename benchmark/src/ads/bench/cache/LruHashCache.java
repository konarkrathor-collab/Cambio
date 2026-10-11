package ads.bench.cache;

/**
 * CANDIDATE (doc 05 §4, THE PROJECT PICK): bounded LRU built as a hand-rolled
 * separate-chaining hash map whose nodes ALSO thread an intrusive most-recently-used list
 * — the exact "Hash Map + LRU Cache" the architecture doc specifies. Hits touch in O(1)
 * (unlink/relink at MRU); misses insert at MRU and evict the LRU tail when the configured
 * capacity is exceeded. No java.util. Semantics match {@link LruReferenceOracle} exactly
 * (verified by full-residency lockstep comparison, not sampling).
 */
public final class LruHashCache implements CacheOps {
  private static final class Node {
    final long id;
    final long balance;
    Node hnext; // bucket chain link
    Node mprev; // MRU list links (mruHead = most recent, lruTail = next victim)
    Node mnext;

    Node(long id, long balance) {
      this.id = id;
      this.balance = balance;
    }
  }

  private final int capacity;
  private Node[] table = new Node[16];
  private int mask = 15;
  private int threshold = 12;
  private Node mruHead;
  private Node lruTail;
  private int count;

  public LruHashCache(int capacity) {
    this.capacity = capacity;
  }

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
    for (Node n = table[b]; n != null; n = n.hnext) {
      if (n.id == traderId) {
        touch(n);
        return n.balance;
      }
    }
    long bal = AccountStore.balance(traderId);
    Node n = new Node(traderId, bal);
    n.hnext = table[b];
    table[b] = n;
    addMru(n);
    count++;
    if (count > capacity) evictLru();
    if (count > threshold) grow();
    return bal;
  }

  private void touch(Node n) {
    if (n == mruHead) return;
    // unlink from the MRU list
    if (n.mprev != null) n.mprev.mnext = n.mnext;
    else mruHead = n.mnext;
    if (n.mnext != null) n.mnext.mprev = n.mprev;
    else lruTail = n.mprev;
    // relink at MRU
    n.mprev = null;
    n.mnext = mruHead;
    if (mruHead != null) mruHead.mprev = n;
    mruHead = n;
    if (lruTail == null) lruTail = n;
  }

  private void addMru(Node n) {
    n.mprev = null;
    n.mnext = mruHead;
    if (mruHead != null) mruHead.mprev = n;
    mruHead = n;
    if (lruTail == null) lruTail = n;
  }

  private void evictLru() {
    Node victim = lruTail;
    Node prev = victim.mprev;
    lruTail = prev;
    if (prev == null) mruHead = null;
    else prev.mnext = null;
    int b = mix(victim.id, mask);
    Node cur = table[b];
    if (cur == victim) {
      table[b] = victim.hnext;
    } else {
      while (cur.hnext != victim) cur = cur.hnext;
      cur.hnext = victim.hnext;
    }
    count--;
  }

  private void grow() {
    Node[] nt = new Node[table.length * 2];
    int nm = nt.length - 1;
    for (int b = 0; b < table.length; b++) {
      Node n = table[b];
      while (n != null) {
        Node nxt = n.hnext;
        int nb = mix(n.id, nm);
        n.hnext = nt[nb];
        nt[nb] = n;
        n = nxt;
      }
    }
    table = nt;
    mask = nm;
    threshold = (int) (nt.length * 0.75);
  }

  /** Pure residency probe: chain walk, no touch, no load (see contract). */
  @Override
  public boolean contains(long traderId) {
    for (Node n = table[mix(traderId, mask)]; n != null; n = n.hnext) {
      if (n.id == traderId) return true;
    }
    return false;
  }

  @Override
  public int size() {
    return count;
  }
}
