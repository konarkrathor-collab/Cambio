package ads.vault;

/**
 * Bounded account cache (doc 05 §4), ported from the benchmark's {@code LruHashCache}: a
 * hand-rolled separate-chaining hash map whose nodes ALSO thread an intrusive
 * most-recently-used list. Hits touch in O(1) (unlink/relink at MRU); misses load through
 * the {@link Loader}, insert at MRU, and evict the LRU tail once the capacity is exceeded.
 * Power-of-two buckets, doubled when the load exceeds 0.75. No JDK collections.
 *
 * <p>Change from the benchmark: values are generic and come from a caller-supplied
 * {@link Loader} instead of the benchmark's fixed {@code AccountStore}, so the vault can
 * back it with its account file in Stage 1.
 *
 * @param <V> cached value type
 */
public final class LruCache<V> {
  /** The slow path a miss falls through to. */
  public interface Loader<V> {
    /** The value for {@code key}, or null if none exists (a null is never cached). */
    V load(long key);
  }

  private static final class Node<V> {
    final long key;
    final V value;
    Node<V> hnext; // bucket chain link
    Node<V> mprev; // MRU list links (mruHead = most recent, lruTail = next victim)
    Node<V> mnext;

    Node(long key, V value) {
      this.key = key;
      this.value = value;
    }
  }

  private final int capacity;
  private final Loader<V> loader;
  private Node<V>[] table = newTable(16);
  private int mask = 15;
  private int threshold = 12;
  private Node<V> mruHead;
  private Node<V> lruTail;
  private int count;

  public LruCache(int capacity, Loader<V> loader) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be >= 1: " + capacity);
    }
    this.capacity = capacity;
    this.loader = loader;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static <V> Node<V>[] newTable(int size) {
    return (Node<V>[]) new Node[size];
  }

  private static int mix(long key, int mask) {
    long h = key * 0x9E3779B97F4A7C15L;
    h ^= h >>> 32;
    h *= 0xBF58476D1CE4E5B9L;
    h ^= h >>> 29;
    return (int) h & mask;
  }

  /**
   * The value for {@code key}. A hit counts as a use; a miss loads it, caches it as most
   * recently used and evicts the least recently used entry if over capacity. Returns null
   * (and caches nothing) if the loader has no value.
   */
  public V get(long key) {
    int b = mix(key, mask);
    for (Node<V> n = table[b]; n != null; n = n.hnext) {
      if (n.key == key) {
        touch(n);
        return n.value;
      }
    }
    V value = loader.load(key);
    if (value == null) return null;
    Node<V> n = new Node<>(key, value);
    n.hnext = table[b];
    table[b] = n;
    addMru(n);
    count++;
    if (count > capacity) evictLru();
    if (count > threshold) grow();
    return value;
  }

  private void touch(Node<V> n) {
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

  private void addMru(Node<V> n) {
    n.mprev = null;
    n.mnext = mruHead;
    if (mruHead != null) mruHead.mprev = n;
    mruHead = n;
    if (lruTail == null) lruTail = n;
  }

  private void evictLru() {
    Node<V> victim = lruTail;
    Node<V> prev = victim.mprev;
    lruTail = prev;
    if (prev == null) mruHead = null;
    else prev.mnext = null;
    int b = mix(victim.key, mask);
    Node<V> cur = table[b];
    if (cur == victim) {
      table[b] = victim.hnext;
    } else {
      while (cur.hnext != victim) cur = cur.hnext;
      cur.hnext = victim.hnext;
    }
    count--;
  }

  private void grow() {
    Node<V>[] nt = newTable(table.length * 2);
    int nm = nt.length - 1;
    for (int b = 0; b < table.length; b++) {
      Node<V> n = table[b];
      while (n != null) {
        Node<V> nxt = n.hnext;
        int nb = mix(n.key, nm);
        n.hnext = nt[nb];
        nt[nb] = n;
        n = nxt;
      }
    }
    table = nt;
    mask = nm;
    threshold = (int) (nt.length * 0.75);
  }

  /** Residency probe only: no load, and does NOT count as a use. */
  public boolean contains(long key) {
    for (Node<V> n = table[mix(key, mask)]; n != null; n = n.hnext) {
      if (n.key == key) return true;
    }
    return false;
  }

  /** Resident entries. */
  public int size() {
    return count;
  }

  public int capacity() {
    return capacity;
  }
}
