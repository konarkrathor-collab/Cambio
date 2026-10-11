package ads.bench.ladder;

/**
 * CANDIDATE (doc 05 §1, scored 2.05): plain unbalanced BST over tick keys — no
 * rebalancing, no rotations. Perfectly serviceable under random insertion order; the
 * reason it exists in this benchmark is the adversarial pattern: under monotonic (sorted)
 * tick arrival the tree degenerates into a spine, pushing subsequent operations toward
 * O(P) — the exact claim doc 05 §1 makes and explicitly flagged as having no data to back.
 * The {@code sorted} workload run supplies that data.
 *
 * <p>All operations are ITERATIVE (remove uses parent-tracking relinks, dump uses an
 * explicit stack): under the sorted pattern the tree height reaches ~O(P), and recursion
 * would risk the JVM stack for exactly the operations the demo exists to stress.
 */
public final class BstLadder implements LadderOps {
  private static final class Node {
    long key;
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
  private Node[] dumpStack = new Node[16];

  @Override
  public void put(long tick, long volume) {
    if (root == null) {
      root = new Node(tick, volume);
      count++;
      return;
    }
    Node cur = root;
    while (true) {
      if (tick < cur.key) {
        if (cur.left == null) {
          cur.left = new Node(tick, volume);
          count++;
          return;
        }
        cur = cur.left;
      } else if (tick > cur.key) {
        if (cur.right == null) {
          cur.right = new Node(tick, volume);
          count++;
          return;
        }
        cur = cur.right;
      } else {
        cur.volume = volume;
        return;
      }
    }
  }

  @Override
  public void remove(long tick) {
    Node cur = root;
    Node parent = null;
    int lastDir = 0; // -1 = cur is parent.left, +1 = parent.right
    while (cur != null && cur.key != tick) {
      parent = cur;
      if (tick < cur.key) {
        lastDir = -1;
        cur = cur.left;
      } else {
        lastDir = 1;
        cur = cur.right;
      }
    }
    if (cur == null) return; // precondition says present; defensive
    count--;
    Node reattach;
    if (cur.left == null) {
      reattach = cur.right;
    } else if (cur.right == null) {
      reattach = cur.left;
    } else {
      // two children: copy the in-order successor up, then splice out the successor node
      Node sp = cur;
      Node s = cur.right;
      while (s.left != null) {
        sp = s;
        s = s.left;
      }
      cur.key = s.key;
      cur.volume = s.volume;
      reattach = s.right; // s has no left child
      if (sp == cur) {
        sp.right = reattach;
      } else {
        sp.left = reattach;
      }
      return;
    }
    if (parent == null) {
      root = reattach;
    } else if (lastDir < 0) {
      parent.left = reattach;
    } else {
      parent.right = reattach;
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
    int top = 0;
    int i = 0;
    Node cur = root;
    while (cur != null || top > 0) {
      while (cur != null) {
        if (top == dumpStack.length) {
          Node[] bigger = new Node[dumpStack.length * 2];
          System.arraycopy(dumpStack, 0, bigger, 0, top);
          dumpStack = bigger;
        }
        dumpStack[top++] = cur;
        cur = cur.left;
      }
      cur = dumpStack[--top];
      outTicks[i] = cur.key;
      outVols[i] = cur.volume;
      i++;
      cur = cur.right;
    }
    return i;
  }
}
