package ads.bench.ladder;

/**
 * CANDIDATE (Ladder v2b — syllabus trees): classic B-tree of configurable ORDER m (max m
 * children, max m-1 keys, min ceil(m/2)-1 keys per non-root node), keys AND volumes stored
 * in every node. Order 3 is exactly the 2-3 tree (1-2 keys, 2-3 children per node); a wide
 * order (32 here) is the cache-oriented B-tree doc 05 §1 discusses alongside the B+ tree.
 *
 * <p>Insert is top-down search + bottom-up split (a node is allowed to hold m keys for an
 * instant, then splits around its median). Delete is the standard bottom-up repair: an
 * internal key is replaced by its in-order predecessor, and any child left below the
 * minimum borrows through the parent from a richer sibling or merges with one. No
 * rotations — balance comes from all leaves sitting at the same depth. Depth walks reuse
 * the ceiling/floor-per-step pattern of the other trees (O(k log P)); the B+ tree's leaf
 * chain is the structural answer to that, measured beside it. No java.util.
 */
public final class BTreeLadder implements LadderOps {
  private final class Node {
    final long[] keys = new long[order];       // room for ONE overflow key before a split
    final long[] vols = new long[order];
    final Node[] children;                    // null for leaves; room for order+1
    int n;

    Node(boolean leaf) {
      children = leaf ? null : new Node[order + 1];
    }

    boolean leaf() {
      return children == null;
    }
  }

  private final int order;
  private final int minKeys;
  private Node root;
  private int count;
  private long splits;
  private long merges;
  private int dumpIndex;

  public BTreeLadder(int order) {
    if (order < 3) throw new IllegalArgumentException("B-tree order must be >= 3: " + order);
    this.order = order;
    this.minKeys = (order + 1) / 2 - 1;
  }

  /** Node splits performed (insert-side restructuring, informational). */
  public long splits() {
    return splits;
  }

  /** Node merges performed (delete-side restructuring, informational). */
  public long merges() {
    return merges;
  }

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

  /** First index i in [0, n) with keys[i] > tick, else n. */
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
    if (root == null) {
      root = new Node(true);
    }
    if (insert(root, tick, volume)) count++;
    if (root.n == order) { // root overflow: grow upward by one level
      Node r = new Node(false);
      r.children[0] = root;
      root = r;
      splitChild(r, 0);
    }
  }

  /** Returns true iff a NEW key was added (false = volume overwrite). */
  private boolean insert(Node x, long tick, long volume) {
    int i = lowerBound(x, tick);
    if (i < x.n && x.keys[i] == tick) {
      x.vols[i] = volume;
      return false;
    }
    if (x.leaf()) {
      System.arraycopy(x.keys, i, x.keys, i + 1, x.n - i);
      System.arraycopy(x.vols, i, x.vols, i + 1, x.n - i);
      x.keys[i] = tick;
      x.vols[i] = volume;
      x.n++;
      return true;
    }
    boolean added = insert(x.children[i], tick, volume);
    if (x.children[i].n == order) splitChild(x, i);
    return added;
  }

  /** Splits overflowing child i of p around its median, which moves up into p. */
  private void splitChild(Node p, int i) {
    splits++;
    Node c = p.children[i];
    int mid = order / 2;
    Node r = new Node(c.leaf());
    r.n = order - 1 - mid;
    System.arraycopy(c.keys, mid + 1, r.keys, 0, r.n);
    System.arraycopy(c.vols, mid + 1, r.vols, 0, r.n);
    if (!c.leaf()) {
      System.arraycopy(c.children, mid + 1, r.children, 0, r.n + 1);
      for (int j = mid + 1; j <= order; j++) c.children[j] = null;
    }
    c.n = mid;
    System.arraycopy(p.keys, i, p.keys, i + 1, p.n - i);
    System.arraycopy(p.vols, i, p.vols, i + 1, p.n - i);
    System.arraycopy(p.children, i + 1, p.children, i + 2, p.n - i);
    p.keys[i] = c.keys[mid];
    p.vols[i] = c.vols[mid];
    p.children[i + 1] = r;
    p.n++;
  }

  @Override
  public void remove(long tick) {
    if (root == null) return;
    if (delete(root, tick)) count--;
    if (root.n == 0) {
      root = root.leaf() ? null : root.children[0]; // shrink by one level
    }
  }

  private boolean delete(Node x, long tick) {
    int i = lowerBound(x, tick);
    if (i < x.n && x.keys[i] == tick) {
      if (x.leaf()) {
        System.arraycopy(x.keys, i + 1, x.keys, i, x.n - i - 1);
        System.arraycopy(x.vols, i + 1, x.vols, i, x.n - i - 1);
        x.n--;
        return true;
      }
      // internal hit: copy the in-order predecessor up, then delete it from the left subtree
      Node p = x.children[i];
      while (!p.leaf()) p = p.children[p.n];
      x.keys[i] = p.keys[p.n - 1];
      x.vols[i] = p.vols[p.n - 1];
      delete(x.children[i], x.keys[i]);
      fix(x, i);
      return true;
    }
    if (x.leaf()) return false; // absent (precondition says present; defensive)
    boolean removed = delete(x.children[i], tick);
    fix(x, i);
    return removed;
  }

  /** Restores child i of p to >= minKeys by borrowing from a sibling or merging. */
  private void fix(Node p, int i) {
    Node c = p.children[i];
    if (c.n >= minKeys) return;
    Node left = i > 0 ? p.children[i - 1] : null;
    Node right = i < p.n ? p.children[i + 1] : null;
    if (left != null && left.n > minKeys) {
      // rotate through the parent: p.key[i-1] down into c, left's last key up into p
      System.arraycopy(c.keys, 0, c.keys, 1, c.n);
      System.arraycopy(c.vols, 0, c.vols, 1, c.n);
      c.keys[0] = p.keys[i - 1];
      c.vols[0] = p.vols[i - 1];
      if (!c.leaf()) {
        System.arraycopy(c.children, 0, c.children, 1, c.n + 1);
        c.children[0] = left.children[left.n];
        left.children[left.n] = null;
      }
      c.n++;
      p.keys[i - 1] = left.keys[left.n - 1];
      p.vols[i - 1] = left.vols[left.n - 1];
      left.n--;
    } else if (right != null && right.n > minKeys) {
      c.keys[c.n] = p.keys[i];
      c.vols[c.n] = p.vols[i];
      if (!c.leaf()) c.children[c.n + 1] = right.children[0];
      c.n++;
      p.keys[i] = right.keys[0];
      p.vols[i] = right.vols[0];
      System.arraycopy(right.keys, 1, right.keys, 0, right.n - 1);
      System.arraycopy(right.vols, 1, right.vols, 0, right.n - 1);
      if (!right.leaf()) {
        System.arraycopy(right.children, 1, right.children, 0, right.n);
        right.children[right.n] = null;
      }
      right.n--;
    } else {
      merge(p, left != null ? i - 1 : i);
    }
  }

  /** Merges child j+1 of p into child j, pulling separator key j down between them. */
  private void merge(Node p, int j) {
    merges++;
    Node a = p.children[j];
    Node b = p.children[j + 1];
    a.keys[a.n] = p.keys[j];
    a.vols[a.n] = p.vols[j];
    System.arraycopy(b.keys, 0, a.keys, a.n + 1, b.n);
    System.arraycopy(b.vols, 0, a.vols, a.n + 1, b.n);
    if (!a.leaf()) System.arraycopy(b.children, 0, a.children, a.n + 1, b.n + 1);
    a.n += 1 + b.n;
    System.arraycopy(p.keys, j + 1, p.keys, j, p.n - j - 1);
    System.arraycopy(p.vols, j + 1, p.vols, j, p.n - j - 1);
    System.arraycopy(p.children, j + 2, p.children, j + 1, p.n - j - 1);
    p.children[p.n] = null;
    p.n--;
  }

  @Override
  public long bestAsk() {
    Node x = root;
    if (x == null) return -1;
    while (!x.leaf()) x = x.children[0];
    return x.keys[0];
  }

  @Override
  public long bestBid() {
    Node x = root;
    if (x == null) return -1;
    while (!x.leaf()) x = x.children[x.n];
    return x.keys[x.n - 1];
  }

  /** Smallest populated tick >= given, or -1. */
  private long ceiling(long tick) {
    long best = -1;
    Node x = root;
    while (x != null) {
      int i = lowerBound(x, tick);
      if (i < x.n) {
        if (x.keys[i] == tick) return tick;
        best = x.keys[i]; // anything better lies in children[i], all < keys[i]
      }
      x = x.leaf() ? null : x.children[i];
    }
    return best;
  }

  /** Largest populated tick <= given, or -1. */
  private long floor(long tick) {
    long best = -1;
    Node x = root;
    while (x != null) {
      int i = upperBound(x, tick);
      if (i > 0) {
        if (x.keys[i - 1] == tick) return tick;
        best = x.keys[i - 1]; // anything better lies in children[i], all > keys[i-1]
      }
      x = x.leaf() ? null : x.children[i];
    }
    return best;
  }

  @Override
  public long walkUp(long fromTick, int k) {
    long sum = 0;
    long t = ceiling(fromTick);
    for (int j = 0; j < k && t >= 0; j++) {
      sum += t;
      t = ceiling(t + 1);
    }
    return sum;
  }

  @Override
  public long walkDown(long fromTick, int k) {
    long sum = 0;
    long t = floor(fromTick);
    for (int j = 0; j < k && t >= 0; j++) {
      sum += t;
      t = floor(t - 1);
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
    if (root != null) dumpNode(root, outTicks, outVols);
    return dumpIndex;
  }

  private void dumpNode(Node x, long[] outTicks, long[] outVols) {
    for (int i = 0; i < x.n; i++) {
      if (!x.leaf()) dumpNode(x.children[i], outTicks, outVols);
      outTicks[dumpIndex] = x.keys[i];
      outVols[dumpIndex] = x.vols[i];
      dumpIndex++;
    }
    if (!x.leaf()) dumpNode(x.children[x.n], outTicks, outVols);
  }
}
