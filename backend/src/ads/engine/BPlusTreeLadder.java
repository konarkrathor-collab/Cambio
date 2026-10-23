package ads.engine;

/**
 * The production price ladder (doc 08 §3a): an in-memory B+ tree of order 32, ported from
 * the benchmark's {@code BPlusTreeLadder}. All (price, level) pairs live in LEAVES, which
 * are doubly linked in key order; internal nodes hold only separator keys (invariant:
 * every key in child i is &lt; keys[i] &lt;= every key in child i+1). Up to {@link #MAX}
 * keys per node, at least {@link #MIN} in any non-root node.
 *
 * <p>Best price is one root-to-leaf walk; a K-level depth walk is ONE descent followed by
 * a sequential scan along the leaf chain, O(log P + K). Separators may go stale after
 * deletes (still valid bounds), as in standard B+ trees.
 *
 * <p>Change from the benchmark: each leaf entry holds a {@link PriceLevel} instead of a
 * bare volume. Leaves keep the primitive {@code keys[]} array for the binary searches, and
 * every slot an entry moves out of is cleared so removed levels are not kept reachable.
 * No JDK collections.
 */
public final class BPlusTreeLadder implements PriceLadder {
  static final int MAX = 32;     // max keys per node (leaf: entries; internal: separators)
  static final int MIN = MAX / 2;

  private static final class Node {
    final long[] keys = new long[MAX + 1];  // room for ONE overflow key before a split
    final PriceLevel[] levels;              // leaves only
    final Node[] children;                  // internal only; room for MAX+2
    Node prev;                              // leaf chain (leaves only)
    Node next;
    int n;

    Node(boolean leaf) {
      levels = leaf ? new PriceLevel[MAX + 1] : null;
      children = leaf ? null : new Node[MAX + 2];
    }

    boolean leaf() {
      return children == null;
    }
  }

  private Node root = new Node(true);
  private int count;
  private PriceLevel touched; // level found/created by insert, or removed by delete

  /** First index i in [0, n) with keys[i] >= price, else n. */
  private static int lowerBound(Node x, long price) {
    int lo = 0;
    int hi = x.n;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (x.keys[mid] < price) lo = mid + 1;
      else hi = mid;
    }
    return lo;
  }

  /** First index i in [0, n) with keys[i] > price, else n (= child index for internals). */
  private static int upperBound(Node x, long price) {
    int lo = 0;
    int hi = x.n;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (x.keys[mid] <= price) lo = mid + 1;
      else hi = mid;
    }
    return lo;
  }

  @Override
  public PriceLevel get(long price) {
    Node leaf = leafFor(price);
    int i = lowerBound(leaf, price);
    return i < leaf.n && leaf.keys[i] == price ? leaf.levels[i] : null;
  }

  @Override
  public PriceLevel getOrCreate(long price) {
    if (insert(root, price)) count++;
    if (root.n > MAX) { // root overflow: grow upward by one level
      Node r = new Node(false);
      r.children[0] = root;
      root = r;
      splitChild(r, 0);
    }
    PriceLevel level = touched;
    touched = null;
    return level;
  }

  private boolean insert(Node x, long price) {
    if (x.leaf()) {
      int i = lowerBound(x, price);
      if (i < x.n && x.keys[i] == price) {
        touched = x.levels[i];
        return false;
      }
      System.arraycopy(x.keys, i, x.keys, i + 1, x.n - i);
      System.arraycopy(x.levels, i, x.levels, i + 1, x.n - i);
      PriceLevel level = new PriceLevel(price);
      x.keys[i] = price;
      x.levels[i] = level;
      x.n++;
      touched = level;
      return true;
    }
    int i = upperBound(x, price);
    boolean added = insert(x.children[i], price);
    if (x.children[i].n > MAX) splitChild(x, i);
    return added;
  }

  /** Splits overflowing child i of p; a separator moves into p. */
  private void splitChild(Node p, int i) {
    Node c = p.children[i];
    Node r = new Node(c.leaf());
    long sep;
    if (c.leaf()) {
      int mid = (MAX + 1) / 2;
      r.n = c.n - mid;
      System.arraycopy(c.keys, mid, r.keys, 0, r.n);
      System.arraycopy(c.levels, mid, r.levels, 0, r.n);
      for (int j = mid; j < c.n; j++) c.levels[j] = null;
      c.n = mid;
      r.next = c.next;
      if (r.next != null) r.next.prev = r;
      r.prev = c;
      c.next = r;
      sep = r.keys[0]; // leaf split: separator is COPIED up (it stays in the leaf)
    } else {
      int mid = (MAX + 1) / 2;
      sep = c.keys[mid]; // internal split: separator MOVES up
      r.n = c.n - mid - 1;
      System.arraycopy(c.keys, mid + 1, r.keys, 0, r.n);
      System.arraycopy(c.children, mid + 1, r.children, 0, r.n + 1);
      for (int j = mid + 1; j <= c.n; j++) c.children[j] = null;
      c.n = mid;
    }
    System.arraycopy(p.keys, i, p.keys, i + 1, p.n - i);
    System.arraycopy(p.children, i + 1, p.children, i + 2, p.n - i);
    p.keys[i] = sep;
    p.children[i + 1] = r;
    p.n++;
  }

  @Override
  public PriceLevel remove(long price) {
    if (delete(root, price)) count--;
    if (!root.leaf() && root.n == 0) {
      root = root.children[0]; // shrink by one level
    }
    PriceLevel level = touched;
    touched = null;
    return level;
  }

  private boolean delete(Node x, long price) {
    if (x.leaf()) {
      int i = lowerBound(x, price);
      if (i >= x.n || x.keys[i] != price) return false; // absent
      touched = x.levels[i];
      System.arraycopy(x.keys, i + 1, x.keys, i, x.n - i - 1);
      System.arraycopy(x.levels, i + 1, x.levels, i, x.n - i - 1);
      x.n--;
      x.levels[x.n] = null;
      return true;
    }
    int i = upperBound(x, price);
    boolean removed = delete(x.children[i], price);
    if (x.children[i].n < MIN) fix(x, i);
    return removed;
  }

  /** Restores child i of p to >= MIN by borrowing from a sibling or merging. */
  private void fix(Node p, int i) {
    Node c = p.children[i];
    Node left = i > 0 ? p.children[i - 1] : null;
    Node right = i < p.n ? p.children[i + 1] : null;
    if (left != null && left.n > MIN) {
      if (c.leaf()) {
        System.arraycopy(c.keys, 0, c.keys, 1, c.n);
        System.arraycopy(c.levels, 0, c.levels, 1, c.n);
        c.keys[0] = left.keys[left.n - 1];
        c.levels[0] = left.levels[left.n - 1];
        c.n++;
        left.n--;
        left.levels[left.n] = null;
        p.keys[i - 1] = c.keys[0];
      } else {
        System.arraycopy(c.keys, 0, c.keys, 1, c.n);
        System.arraycopy(c.children, 0, c.children, 1, c.n + 1);
        c.keys[0] = p.keys[i - 1];
        c.children[0] = left.children[left.n];
        left.children[left.n] = null;
        c.n++;
        p.keys[i - 1] = left.keys[left.n - 1];
        left.n--;
      }
    } else if (right != null && right.n > MIN) {
      if (c.leaf()) {
        c.keys[c.n] = right.keys[0];
        c.levels[c.n] = right.levels[0];
        c.n++;
        System.arraycopy(right.keys, 1, right.keys, 0, right.n - 1);
        System.arraycopy(right.levels, 1, right.levels, 0, right.n - 1);
        right.n--;
        right.levels[right.n] = null;
        p.keys[i] = right.keys[0];
      } else {
        c.keys[c.n] = p.keys[i];
        c.children[c.n + 1] = right.children[0];
        c.n++;
        p.keys[i] = right.keys[0];
        System.arraycopy(right.keys, 1, right.keys, 0, right.n - 1);
        System.arraycopy(right.children, 1, right.children, 0, right.n);
        right.children[right.n] = null;
        right.n--;
      }
    } else {
      merge(p, left != null ? i - 1 : i);
    }
  }

  /** Merges child j+1 of p into child j and drops separator j from p. */
  private void merge(Node p, int j) {
    Node a = p.children[j];
    Node b = p.children[j + 1];
    if (a.leaf()) {
      System.arraycopy(b.keys, 0, a.keys, a.n, b.n);
      System.arraycopy(b.levels, 0, a.levels, a.n, b.n);
      a.n += b.n;
      a.next = b.next;
      if (a.next != null) a.next.prev = a;
    } else {
      a.keys[a.n] = p.keys[j];
      System.arraycopy(b.keys, 0, a.keys, a.n + 1, b.n);
      System.arraycopy(b.children, 0, a.children, a.n + 1, b.n + 1);
      a.n += 1 + b.n;
    }
    System.arraycopy(p.keys, j + 1, p.keys, j, p.n - j - 1);
    System.arraycopy(p.children, j + 2, p.children, j + 1, p.n - j - 1);
    p.children[p.n] = null;
    p.n--;
  }

  private Node leftmostLeaf() {
    Node x = root;
    while (!x.leaf()) x = x.children[0];
    return x;
  }

  private Node rightmostLeaf() {
    Node x = root;
    while (!x.leaf()) x = x.children[x.n];
    return x;
  }

  /** The leaf whose key range covers price (where price is, or would be inserted). */
  private Node leafFor(long price) {
    Node x = root;
    while (!x.leaf()) x = x.children[upperBound(x, price)];
    return x;
  }

  @Override
  public PriceLevel lowest() {
    Node x = leftmostLeaf();
    return x.n == 0 ? null : x.levels[0];
  }

  @Override
  public PriceLevel highest() {
    Node x = rightmostLeaf();
    return x.n == 0 ? null : x.levels[x.n - 1];
  }

  /** One descent, then a forward scan along the leaf chain: O(log P + k). */
  @Override
  public int walkUp(long fromPrice, int k, PriceLevel[] out) {
    if (k > out.length) k = out.length;
    Node leaf = leafFor(fromPrice);
    int i = lowerBound(leaf, fromPrice);
    int taken = 0;
    while (taken < k) {
      while (leaf != null && i >= leaf.n) {
        leaf = leaf.next;
        i = 0;
      }
      if (leaf == null) break;
      out[taken++] = leaf.levels[i++];
    }
    return taken;
  }

  /** One descent, then a backward scan along the leaf chain: O(log P + k). */
  @Override
  public int walkDown(long fromPrice, int k, PriceLevel[] out) {
    if (k > out.length) k = out.length;
    Node leaf = leafFor(fromPrice);
    int i = upperBound(leaf, fromPrice) - 1;
    int taken = 0;
    while (taken < k) {
      while (leaf != null && i < 0) {
        leaf = leaf.prev;
        i = leaf == null ? -1 : leaf.n - 1;
      }
      if (leaf == null) break;
      out[taken++] = leaf.levels[i--];
    }
    return taken;
  }

  @Override
  public int size() {
    return count;
  }
}
