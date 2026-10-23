package ads.bench.ladder;

/**
 * CANDIDATE (doc 05 §1, scored 3.60 — the last unmeasured row of its table): in-memory B+
 * tree. All (tick, volume) pairs live in LEAVES, which are doubly linked in key order;
 * internal nodes hold only separator keys (invariant: every key in child i is &lt; keys[i]
 * &lt;= every key in child i+1). Up to {@link #MAX} keys per node, at least {@link #MIN} in
 * any non-root node.
 *
 * <p>What the structure buys for a price ladder: best bid/ask is one root-to-leaf walk,
 * and a K-level depth walk is ONE descent followed by a sequential scan along the leaf
 * chain — O(log P + K) with contiguous primitive arrays, instead of the O(K log P)
 * repeated-ceiling walk the binary trees use. What it costs: split/borrow/merge
 * bookkeeping on both node kinds, the extra implementation risk doc 05 §1 scored it down
 * for (Feasibility = 2). Separators may go stale after deletes (still valid bounds), as in
 * standard B+ trees. No java.util.
 */
public final class BPlusTreeLadder implements LadderOps {
  static final int MAX = 32;     // max keys per node (leaf: entries; internal: separators)
  static final int MIN = MAX / 2;

  private static final class Node {
    final long[] keys = new long[MAX + 1];  // room for ONE overflow key before a split
    final long[] vols;                      // leaves only
    final Node[] children;                  // internal only; room for MAX+2
    Node prev;                              // leaf chain (leaves only)
    Node next;
    int n;

    Node(boolean leaf) {
      vols = leaf ? new long[MAX + 1] : null;
      children = leaf ? null : new Node[MAX + 2];
    }

    boolean leaf() {
      return children == null;
    }
  }

  private Node root = new Node(true);
  private int count;
  private int dumpIndex;

  /** First index i in [0, n) with keys[i] >= tick, else n. */
  private static int lowerBound(Node x, long tick) {
    int lo = 0;
    int hi = x.n;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (x.keys[mid] < tick) lo = mid + 1;
      else hi = mid;
    }
    return lo;
  }

  /** First index i in [0, n) with keys[i] > tick, else n (= child index for internals). */
  private static int upperBound(Node x, long tick) {
    int lo = 0;
    int hi = x.n;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (x.keys[mid] <= tick) lo = mid + 1;
      else hi = mid;
    }
    return lo;
  }

  @Override
  public void put(long tick, long volume) {
    if (insert(root, tick, volume)) count++;
    if (root.n > MAX) { // root overflow: grow upward by one level
      Node r = new Node(false);
      r.children[0] = root;
      root = r;
      splitChild(r, 0);
    }
  }

  private boolean insert(Node x, long tick, long volume) {
    if (x.leaf()) {
      int i = lowerBound(x, tick);
      if (i < x.n && x.keys[i] == tick) {
        x.vols[i] = volume;
        return false;
      }
      System.arraycopy(x.keys, i, x.keys, i + 1, x.n - i);
      System.arraycopy(x.vols, i, x.vols, i + 1, x.n - i);
      x.keys[i] = tick;
      x.vols[i] = volume;
      x.n++;
      return true;
    }
    int i = upperBound(x, tick);
    boolean added = insert(x.children[i], tick, volume);
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
      System.arraycopy(c.vols, mid, r.vols, 0, r.n);
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
  public void remove(long tick) {
    if (delete(root, tick)) count--;
    if (!root.leaf() && root.n == 0) {
      root = root.children[0]; // shrink by one level
    }
  }

  private boolean delete(Node x, long tick) {
    if (x.leaf()) {
      int i = lowerBound(x, tick);
      if (i >= x.n || x.keys[i] != tick) return false; // absent (defensive)
      System.arraycopy(x.keys, i + 1, x.keys, i, x.n - i - 1);
      System.arraycopy(x.vols, i + 1, x.vols, i, x.n - i - 1);
      x.n--;
      return true;
    }
    int i = upperBound(x, tick);
    boolean removed = delete(x.children[i], tick);
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
        System.arraycopy(c.vols, 0, c.vols, 1, c.n);
        c.keys[0] = left.keys[left.n - 1];
        c.vols[0] = left.vols[left.n - 1];
        c.n++;
        left.n--;
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
        c.vols[c.n] = right.vols[0];
        c.n++;
        System.arraycopy(right.keys, 1, right.keys, 0, right.n - 1);
        System.arraycopy(right.vols, 1, right.vols, 0, right.n - 1);
        right.n--;
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
      System.arraycopy(b.vols, 0, a.vols, a.n, b.n);
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

  /** The leaf whose key range covers tick (where tick is, or would be inserted). */
  private Node leafFor(long tick) {
    Node x = root;
    while (!x.leaf()) x = x.children[upperBound(x, tick)];
    return x;
  }

  @Override
  public long bestAsk() {
    Node x = leftmostLeaf();
    return x.n == 0 ? -1 : x.keys[0];
  }

  @Override
  public long bestBid() {
    Node x = rightmostLeaf();
    return x.n == 0 ? -1 : x.keys[x.n - 1];
  }

  /** One descent, then a forward scan along the leaf chain: O(log P + k). */
  @Override
  public long walkUp(long fromTick, int k) {
    Node leaf = leafFor(fromTick);
    int i = lowerBound(leaf, fromTick);
    long sum = 0;
    for (int taken = 0; taken < k; taken++) {
      while (leaf != null && i >= leaf.n) {
        leaf = leaf.next;
        i = 0;
      }
      if (leaf == null) break;
      sum += leaf.keys[i++];
    }
    return sum;
  }

  /** One descent, then a backward scan along the leaf chain: O(log P + k). */
  @Override
  public long walkDown(long fromTick, int k) {
    Node leaf = leafFor(fromTick);
    int i = upperBound(leaf, fromTick) - 1;
    long sum = 0;
    for (int taken = 0; taken < k; taken++) {
      while (leaf != null && i < 0) {
        leaf = leaf.prev;
        i = leaf == null ? -1 : leaf.n - 1;
      }
      if (leaf == null) break;
      sum += leaf.keys[i--];
    }
    return sum;
  }

  @Override
  public int size() {
    return count;
  }

  @Override
  public int dump(long[] outTicks, long[] outVols) {
    dumpIndex = 0;
    for (Node leaf = leftmostLeaf(); leaf != null; leaf = leaf.next) {
      for (int i = 0; i < leaf.n; i++) {
        outTicks[dumpIndex] = leaf.keys[i];
        outVols[dumpIndex] = leaf.vols[i];
        dumpIndex++;
      }
    }
    return dumpIndex;
  }
}
