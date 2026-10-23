package ads.bench.queue;

import ads.bench.core.Rng;

/**
 * CANDIDATE (doc 05 §2, rejected): randomized balanced BST (treap) keyed by orderId —
 * faithful to the rubric's "Balanced Tree keyed by orderId" row. Because order ids are
 * assigned in increasing arrival order (see the {@link QueueOps} contract — the same thing
 * a real exchange's sequencer does), the FIFO head is simply the SMALLEST id: peek and
 * dequeue-head walk to the leftmost node in expected O(log n). enqueue (insert) and cancel
 * (find + delete) are expected O(log n) too — so this is "O(log N) everywhere", exactly the
 * rubric's claim, and the bench measures what that costs against the O(1) pick.
 *
 * <p><b>Correction (Run 12):</b> the first version of this class assumed id order was NOT
 * arrival order and located the head with a full O(n) traversal by an arrival sequence
 * number. The workload has always assigned ids in arrival order, so that traversal was a
 * handicap the structure does not actually have; it made this candidate ~100x slower than
 * the pick in Run 6. Enqueueing a non-increasing id now throws rather than silently
 * returning the wrong head. Priorities come from the instance's own seeded RNG so replays
 * are reproducible.
 */
public final class TreeQueueOps implements QueueOps {
  private static final class Node {
    final long id;
    final long volume;
    final long prio; // min-heap priority (lower value = higher priority)
    Node left;
    Node right;

    Node(long id, long volume, long prio) {
      this.id = id;
      this.volume = volume;
      this.prio = prio;
    }
  }

  private final Rng rng;
  private Node root;
  private long lastId = Long.MIN_VALUE;
  private int count;
  private int dumpIndex;

  public TreeQueueOps(long seed) {
    this.rng = new Rng(seed);
  }

  @Override
  public void enqueue(long orderId, long volume) {
    if (orderId <= lastId) {
      throw new IllegalArgumentException("order ids must increase in arrival order: "
          + orderId + " after " + lastId);
    }
    lastId = orderId;
    root = insert(root, new Node(orderId, volume, rng.nextLong()));
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
    long id = leftmost(root).id;
    root = erase(root, id);
    count--;
    return id;
  }

  @Override
  public long peekHead() {
    return root == null ? -1 : leftmost(root).id;
  }

  /** Oldest live order = smallest id (ids increase with arrival). Expected O(log n). */
  private static Node leftmost(Node h) {
    while (h.left != null) h = h.left;
    return h;
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
