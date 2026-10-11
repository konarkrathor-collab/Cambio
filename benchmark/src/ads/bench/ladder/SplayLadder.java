package ads.bench.ladder;

/**
 * CANDIDATE (syllabus tree; Ladder v2 coverage): splay tree over tick keys — every
 * put/remove splays the accessed node to the root, so recently-active price levels stay
 * cheap. Queries (best/seek/walk) use plain BST searches that do NOT restructure —
 * documented rather than hidden. Instrumented with a restructure-rotation counter (total
 * splay rotations) for the Ladder v2 counter report. The splay recursion depth follows the
 * access path: amortized O(log P) under normal access, but NOT worst-case bounded — the
 * sorted-insertion run is sized so the deepest realistic path stays well inside the JVM
 * stack, and this residual worst-case looseness is itself part of why doc 05 §1 scored
 * splay-type structures on determinism rather than raw speed.
 */
public final class SplayLadder implements LadderOps {
  private static final class Node {
    final long key;
    long volume;
    Node left;
    Node right;

    Node(long key, long volume) {
      this.key = key;
      this.volume = volume;
    }
  }

  private Node root;
  private int count;
  private int dumpIndex;
  private long rotations;

  /** Total splay restructure rotations performed (informational; not a balancing counter). */
  public long rotations() {
    return rotations;
  }

  private Node rotateRight(Node h) {
    rotations++;
    Node x = h.left;
    h.left = x.right;
    x.right = h;
    return x;
  }

  private Node rotateLeft(Node h) {
    rotations++;
    Node x = h.right;
    h.right = x.left;
    x.left = h;
    return x;
  }

  /** Standard recursive splay (Sedgewick formulation); brings key — or the last accessed node — to the root. */
  private Node splay(Node h, long key) {
    if (h == null) return null;
    if (key < h.key) {
      if (h.left == null) return h;
      if (key < h.left.key) {
        h.left.left = splay(h.left.left, key);
        h = rotateRight(h);
      } else if (key > h.left.key) {
        h.left.right = splay(h.left.right, key);
        if (h.left.right != null) h.left = rotateLeft(h.left);
      }
      return h.left == null ? h : rotateRight(h);
    } else if (key > h.key) {
      if (h.right == null) return h;
      if (key > h.right.key) {
        h.right.right = splay(h.right.right, key);
        h = rotateLeft(h);
      } else if (key < h.right.key) {
        h.right.left = splay(h.right.left, key);
        if (h.right.left != null) h.right = rotateRight(h.right);
      }
      return h.right == null ? h : rotateLeft(h);
    } else {
      return h;
    }
  }

  @Override
  public void put(long tick, long volume) {
    root = splay(root, tick);
    if (root != null && root.key == tick) {
      root.volume = volume;
      return;
    }
    Node n = new Node(tick, volume);
    if (root == null) {
      root = n;
    } else if (tick < root.key) {
      n.right = root;
      n.left = root.left;
      root.left = null;
      root = n;
    } else {
      n.left = root;
      n.right = root.right;
      root.right = null;
      root = n;
    }
    count++;
  }

  @Override
  public void remove(long tick) {
    root = splay(root, tick);
    if (root == null || root.key != tick) return; // precondition says present; defensive
    count--;
    if (root.left == null) {
      root = root.right;
    } else {
      Node rightSubtree = root.right;
      Node newRoot = splay(root.left, tick); // brings the left side's MAXIMUM to its root
      newRoot.right = rightSubtree;
      root = newRoot;
    }
  }

  @Override
  public long bestAsk() {
    Node cur = root;
    if (cur == null) return -1;
    while (cur.left != null) cur = cur.left;
    return cur.key;
  }

  @Override
  public long bestBid() {
    Node cur = root;
    if (cur == null) return -1;
    while (cur.right != null) cur = cur.right;
    return cur.key;
  }

  private long ceiling(long tick) {
    Node cur = root;
    long best = -1;
    while (cur != null) {
      if (cur.key >= tick) {
        best = cur.key;
        cur = cur.left;
      } else {
        cur = cur.right;
      }
    }
    return best;
  }

  private long floor(long tick) {
    Node cur = root;
    long best = -1;
    while (cur != null) {
      if (cur.key <= tick) {
        best = cur.key;
        cur = cur.right;
      } else {
        cur = cur.left;
      }
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
    dumpIn(root, outTicks, outVols);
    return dumpIndex;
  }

  private void dumpIn(Node n, long[] ticks, long[] vols) {
    if (n == null) return;
    dumpIn(n.left, ticks, vols);
    ticks[dumpIndex] = n.key;
    vols[dumpIndex] = n.volume;
    dumpIndex++;
    dumpIn(n.right, ticks, vols);
  }
}
