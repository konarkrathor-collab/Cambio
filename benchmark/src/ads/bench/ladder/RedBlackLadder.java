package ads.bench.ladder;

/**
 * CANDIDATE (doc 05 §1, scored 3.95 — the explicit comparison object for the Radix Tree
 * pick): hand-rolled classic red-black tree (CLRS formulation with an explicit sentinel),
 * instrumented with rotation counters (single rotations counted individually). This is the
 * candidate that RETIRES the benchmark's {@code TreeMap} stand-in — doc 05 §1's "close
 * call" was argued against Java's boxed TreeMap; this measures the real structure with
 * primitive longs. The delete fixup is the classic implementation hazard; the oracle gate
 * holds it to byte-exact agreement with every other candidate.
 */
public final class RedBlackLadder implements LadderOps {
  private static final class Node {
    long key;
    long volume;
    boolean red;
    Node left;
    Node right;
    Node parent;
  }

  private final Node nil = new Node(); // sentinel: always black, children/parent self-referential
  private Node root;
  private int count;
  private int dumpIndex;
  private long insertRotations;
  private long deleteRotations;

  public RedBlackLadder() {
    nil.red = false;
    nil.left = nil;
    nil.right = nil;
    nil.parent = nil;
    root = nil;
  }

  public long rotations() {
    return insertRotations + deleteRotations;
  }

  public long insertRotations() {
    return insertRotations;
  }

  public long deleteRotations() {
    return deleteRotations;
  }

  private void rotateLeft(Node x, boolean isInsert) {
    Node y = x.right;
    x.right = y.left;
    if (y.left != nil) y.left.parent = x;
    y.parent = x.parent;
    if (x.parent == nil) root = y;
    else if (x == x.parent.left) x.parent.left = y;
    else x.parent.right = y;
    y.left = x;
    x.parent = y;
    if (isInsert) insertRotations++;
    else deleteRotations++;
  }

  private void rotateRight(Node x, boolean isInsert) {
    Node y = x.left;
    x.left = y.right;
    if (y.right != nil) y.right.parent = x;
    y.parent = x.parent;
    if (x.parent == nil) root = y;
    else if (x == x.parent.right) x.parent.right = y;
    else x.parent.left = y;
    y.right = x;
    x.parent = y;
    if (isInsert) insertRotations++;
    else deleteRotations++;
  }

  @Override
  public void put(long tick, long volume) {
    Node parent = nil;
    Node cur = root;
    while (cur != nil) {
      parent = cur;
      if (tick < cur.key) {
        cur = cur.left;
      } else if (tick > cur.key) {
        cur = cur.right;
      } else {
        cur.volume = volume;
        return;
      }
    }
    Node z = new Node();
    z.key = tick;
    z.volume = volume;
    z.red = true;
    z.left = nil;
    z.right = nil;
    z.parent = parent;
    if (parent == nil) {
      root = z;
    } else if (tick < parent.key) {
      parent.left = z;
    } else {
      parent.right = z;
    }
    count++;
    insertFixup(z);
  }

  private void insertFixup(Node z) {
    while (z.parent.red) {
      if (z.parent == z.parent.parent.left) {
        Node y = z.parent.parent.right;
        if (y.red) {
          z.parent.red = false;
          y.red = false;
          z.parent.parent.red = true;
          z = z.parent.parent;
        } else {
          if (z == z.parent.right) {
            z = z.parent;
            rotateLeft(z, true);
          }
          z.parent.red = false;
          z.parent.parent.red = true;
          rotateRight(z.parent.parent, true);
        }
      } else {
        Node y = z.parent.parent.left;
        if (y.red) {
          z.parent.red = false;
          y.red = false;
          z.parent.parent.red = true;
          z = z.parent.parent;
        } else {
          if (z == z.parent.left) {
            z = z.parent;
            rotateRight(z, true);
          }
          z.parent.red = false;
          z.parent.parent.red = true;
          rotateLeft(z.parent.parent, true);
        }
      }
    }
    root.red = false;
  }
  @Override
  public void remove(long tick) {
    Node z = find(tick);
    if (z == nil) return; // precondition says present; defensive
    count--;
    Node y = z;
    boolean yWasRed = y.red;
    Node x;
    if (z.left == nil) {
      x = z.right;
      transplant(z, z.right);
    } else if (z.right == nil) {
      x = z.left;
      transplant(z, z.left);
    } else {
      y = minimum(z.right);
      yWasRed = y.red;
      x = y.right;
      if (y.parent == z) {
        x.parent = y;
      } else {
        transplant(y, y.right);
        y.right = z.right;
        y.right.parent = y;
      }
      transplant(z, y);
      y.left = z.left;
      y.left.parent = y;
      y.red = z.red;
    }
    if (!yWasRed) {
      deleteFixup(x);
    }
  }

  private void transplant(Node u, Node v) {
    if (u.parent == nil) {
      root = v;
    } else if (u == u.parent.left) {
      u.parent.left = v;
    } else {
      u.parent.right = v;
    }
    v.parent = u.parent;
  }

  private Node minimum(Node n) {
    while (n.left != nil) n = n.left;
    return n;
  }

  /**
   * CLRS RB-DELETE-FIXUP. Note w can never be the sentinel here: the red-black invariant
   * forces the sibling subtree to contain at least one black node whenever x is doubly
   * black, so {@code w.red = true} never corrupts the shared sentinel.
   */
  private void deleteFixup(Node x) {
    while (x != root && !x.red) {
      if (x == x.parent.left) {
        Node w = x.parent.right;
        if (w.red) {
          w.red = false;
          x.parent.red = true;
          rotateLeft(x.parent, false);
          w = x.parent.right;
        }
        if (!w.left.red && !w.right.red) {
          w.red = true;
          x = x.parent;
        } else {
          if (!w.right.red) {
            w.left.red = false;
            w.red = true;
            rotateRight(w, false);
            w = x.parent.right;
          }
          w.red = x.parent.red;
          x.parent.red = false;
          w.right.red = false;
          rotateLeft(x.parent, false);
          x = root;
        }
      } else {
        Node w = x.parent.left;
        if (w.red) {
          w.red = false;
          x.parent.red = true;
          rotateRight(x.parent, false);
          w = x.parent.left;
        }
        if (!w.right.red && !w.left.red) {
          w.red = true;
          x = x.parent;
        } else {
          if (!w.left.red) {
            w.right.red = false;
            w.red = true;
            rotateLeft(w, false);
            w = x.parent.left;
          }
          w.red = x.parent.red;
          x.parent.red = false;
          w.left.red = false;
          rotateRight(x.parent, false);
          x = root;
        }
      }
    }
    x.red = false;
  }
  private Node find(long tick) {
    Node cur = root;
    while (cur != nil) {
      if (tick < cur.key) {
        cur = cur.left;
      } else if (tick > cur.key) {
        cur = cur.right;
      } else {
        return cur;
      }
    }
    return nil;
  }

  @Override
  public long bestAsk() {
    if (root == nil) return -1;
    return minimum(root).key;
  }

  @Override
  public long bestBid() {
    if (root == nil) return -1;
    Node cur = root;
    while (cur.right != nil) cur = cur.right;
    return cur.key;
  }

  private long ceiling(long tick) {
    Node cur = root;
    long best = -1;
    while (cur != nil) {
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
    while (cur != nil) {
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
    if (n == nil) return;
    dumpIn(n.left, ticks, vols);
    ticks[dumpIndex] = n.key;
    vols[dumpIndex] = n.volume;
    dumpIndex++;
    dumpIn(n.right, ticks, vols);
  }
}
