package ads.bench.ladder;

/**
 * CANDIDATE (doc 05 §1, scored 3.45): hand-rolled AVL tree over tick keys — strictly
 * height-balanced, every operation O(log P), no amortization. Instrumented with rotation
 * counters (single rotations counted individually; a double rotation counts as 2) because
 * doc 05 §1 claims AVL "does more rotations than Red-Black per structural mutation" and
 * that claim deserves verification with counters, not just wall-clock timing. Recursion
 * depth is O(log P) — the balance invariant makes that safe.
 */
public final class AvlLadder implements LadderOps {
  private static final class Node {
    long key;
    long volume;
    int height; // leaf = 1
    Node left;
    Node right;

    Node(long key, long volume) {
      this.key = key;
      this.volume = volume;
      this.height = 1;
    }
  }

  private Node root;
  private int count;
  private int dumpIndex;
  private long insertRotations;
  private long deleteRotations;

  public long rotations() {
    return insertRotations + deleteRotations;
  }

  public long insertRotations() {
    return insertRotations;
  }

  public long deleteRotations() {
    return deleteRotations;
  }

  private static int height(Node n) {
    return n == null ? 0 : n.height;
  }

  private static void update(Node n) {
    n.height = 1 + Math.max(height(n.left), height(n.right));
  }

  private Node rotateRight(Node x, boolean isInsert) {
    Node y = x.left;
    x.left = y.right;
    y.right = x;
    update(x);
    update(y);
    if (isInsert) insertRotations++;
    else deleteRotations++;
    return y;
  }

  private Node rotateLeft(Node x, boolean isInsert) {
    Node y = x.right;
    x.right = y.left;
    y.left = x;
    update(x);
    update(y);
    if (isInsert) insertRotations++;
    else deleteRotations++;
    return y;
  }

  private Node rebalance(Node n, boolean isInsert) {
    update(n);
    int balance = height(n.left) - height(n.right);
    if (balance > 1) {
      if (height(n.left.left) < height(n.left.right)) {
        n.left = rotateLeft(n.left, isInsert); // LR case: first a single rotation, counted
      }
      return rotateRight(n, isInsert); // LL case (or second half of LR)
    }
    if (balance < -1) {
      if (height(n.right.right) < height(n.right.left)) {
        n.right = rotateRight(n.right, isInsert); // RL case
      }
      return rotateLeft(n, isInsert); // RR case (or second half of RL)
    }
    return n;
  }
  @Override
  public void put(long tick, long volume) {
    root = insert(root, tick, volume);
  }

  private Node insert(Node n, long tick, long vol) {
    if (n == null) {
      count++;
      return new Node(tick, vol);
    }
    if (tick < n.key) {
      n.left = insert(n.left, tick, vol);
    } else if (tick > n.key) {
      n.right = insert(n.right, tick, vol);
    } else {
      n.volume = vol;
      return n;
    }
    return rebalance(n, true);
  }

  @Override
  public void remove(long tick) {
    root = delete(root, tick);
  }

  private Node delete(Node n, long tick) {
    if (n == null) return null;
    if (tick < n.key) {
      n.left = delete(n.left, tick);
    } else if (tick > n.key) {
      n.right = delete(n.right, tick);
    } else {
      if (n.left == null) {
        count--;
        return n.right;
      }
      if (n.right == null) {
        count--;
        return n.left;
      }
      // two children: copy the in-order successor up, then physically remove the minimum
      Node succ = n.right;
      while (succ.left != null) succ = succ.left;
      n.key = succ.key;
      n.volume = succ.volume;
      n.right = deleteMin(n.right);
      count--;
    }
    return rebalance(n, false);
  }

  /** Removes the leftmost node of the subtree; no count change (the caller owns the tally). */
  private Node deleteMin(Node n) {
    if (n.left == null) return n.right;
    n.left = deleteMin(n.left);
    return rebalance(n, false);
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
