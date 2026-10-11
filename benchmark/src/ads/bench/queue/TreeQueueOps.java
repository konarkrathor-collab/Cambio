package ads.bench.queue;

import ads.bench.core.Rng;

/**
 * CANDIDATE (doc 05 §2, rejected): randomized balanced BST (treap) keyed by orderId —
 * faithful to the rubric's "Balanced Tree keyed by orderId" row. enqueue (insert) and
 * cancel (find + delete) are expected O(log n); but PEEK and DEQUEUE-head need the OLDEST
 * live order, and id order is NOT arrival order, so the treap pays a full O(n) traversal
 * to locate the FIFO head. That index mismatch is exactly what the rubric penalized
 * (Op. Fit = 3) — this candidate measures the real cost of it. Priorities come from the
 * instance's own seeded RNG so replays are reproducible.
 */
public final class TreeQueueOps implements QueueOps {
  private static final class Node {
    final long id;
    final long seq; // arrival sequence; FIFO order = ascending seq
    final long volume;
    final long prio; // min-heap priority (lower value = higher priority)
    Node left;
    Node right;

    Node(long id, long seq, long volume, long prio) {
      this.id = id;
      this.seq = seq;
      this.volume = volume;
      this.prio = prio;
    }
  }

  private final Rng rng;
  private Node root;
  private long nextSeq;
  private int count;
  private int dumpIndex;

  public TreeQueueOps(long seed) {
    this.rng = new Rng(seed);
  }

  @Override
  public void enqueue(long orderId, long volume) {
    root = insert(root, new Node(orderId, nextSeq++, volume, rng.nextLong()));
    count++;
  }

  private static Node insert(Node h, Node n) {
    if (h == null) return n;
    if (n.id < h.id) {
      h.left = insert(h.left, n);
      if (h.left.prio < h.prio) h = rotateRight(h);
    } else {
      h.right = insert(h.right, n);
      if (h.right.prio < h.prio) h = rotateLeft(h);
    }
    return h;
  }

  private static Node rotateRight(Node h) {
    Node x = h.left;
    h.left = x.right;
    x.right = h;
    return x;
  }

  private static Node rotateLeft(Node h) {
    Node x = h.right;
    h.right = x.left;
    x.left = h;
    return x;
  }

  @Override
  public long dequeueHead() {
    if (root == null) return -1;
    Node oldest = oldest(root, null);
    root = erase(root, oldest.id);
    count--;
    return oldest.id;
  }

  @Override
  public long peekHead() {
    return root == null ? -1 : oldest(root, null).id;
  }

  /** Treap has no arrival index: walk the whole tree for the smallest seq. O(n). */
  private static Node oldest(Node h, Node best) {
    if (h == null) return best;
    if (best == null || h.seq < best.seq) best = h;
    best = oldest(h.left, best);
    return oldest(h.right, best);
  }

  @Override
  public boolean cancel(long orderId) {
    if (find(root, orderId) == null) return false;
    root = erase(root, orderId);
    count--;
    return true;
  }

  private static Node find(Node h, long id) {
    while (h != null) {
      if (id < h.id) h = h.left;
      else if (id > h.id) h = h.right;
      else return h;
    }
    return null;
  }

  private static Node erase(Node h, long id) {
    if (h == null) return null;
    if (id < h.id) {
      h.left = erase(h.left, id);
    } else if (id > h.id) {
      h.right = erase(h.right, id);
    } else {
      if (h.right == null) return h.left;
      if (h.left == null) return h.right;
      if (h.left.prio < h.right.prio) {
        h = rotateRight(h);
        h.right = erase(h.right, id);
      } else {
        h = rotateLeft(h);
        h.left = erase(h.left, id);
      }
    }
    return h;
  }

  @Override
  public int size() {
    return count;
  }

  @Override
  public int dump(long[] orderIds, long[] volumes) {
    dumpIndex = 0;
    dumpInto(root, orderIds, volumes);
    return dumpIndex;
  }

  private void dumpInto(Node h, long[] orderIds, long[] volumes) {
    if (h == null) return;
    dumpInto(h.left, orderIds, volumes);
    orderIds[dumpIndex] = h.id;
    volumes[dumpIndex] = h.volume;
    dumpIndex++;
    dumpInto(h.right, orderIds, volumes);
  }
}
