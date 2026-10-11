package ads.bench.queue;

/**
 * CANDIDATE (doc 05 §2, THE PROJECT PICK): intrusive doubly linked list indexed by a
 * hand-rolled separate-chaining hash map. Each node carries BOTH link sets at once —
 * queue links ({@code prev}/{@code next}, FIFO order) and a single bucket-chain link
 * ({@code hnext}) — exactly the "Hash-Indexed Doubly Linked List" the architecture doc
 * specifies, and the trick that makes cancellation O(1): the map finds the node, the list
 * unlinks it, no scans anywhere. The table doubles when load exceeds 0.75. No java.util.
 */
public final class DllHashQueueOps implements QueueOps {
  private static final class Node {
    final long id;
    final long volume;
    Node prev;  // queue links (FIFO order)
    Node next;
    Node hnext; // bucket chain link

    Node(long id, long volume) {
      this.id = id;
      this.volume = volume;
    }
  }

  private Node[] table; // power-of-two buckets, separate chaining
  private int mask;
  private int threshold;
  private Node head;
  private Node tail;
  private int count;

  public DllHashQueueOps() {
    table = new Node[16];
    mask = 15;
    threshold = 12;
  }

  private static int mix(long id, int mask) {
    long h = id * 0x9E3779B97F4A7C15L;
    h ^= h >>> 32;
    h *= 0xBF58476D1CE4E5B9L;
    h ^= h >>> 29;
    return (int) h & mask;
  }

  @Override
  public void enqueue(long orderId, long volume) {
    Node n = new Node(orderId, volume);
    int b = mix(orderId, mask);
    n.hnext = table[b];
    table[b] = n;
    if (tail == null) {
      head = n;
    } else {
      n.prev = tail;
      tail.next = n;
    }
    tail = n;
    count++;
    if (count > threshold) {
      grow();
    }
  }

  private void grow() {
    Node[] nt = new Node[table.length * 2];
    int nm = nt.length - 1;
    for (int b = 0; b < table.length; b++) {
      Node n = table[b];
      while (n != null) {
        Node nxt = n.hnext;
        int nb = mix(n.id, nm);
        n.hnext = nt[nb];
        nt[nb] = n;
        n = nxt;
      }
    }
    table = nt;
    mask = nm;
    threshold = (int) (nt.length * 0.75);
  }

  @Override
  public long dequeueHead() {
    if (head == null) return -1;
    long id = head.id;
    remove(head);
    return id;
  }

  @Override
  public long peekHead() {
    return head == null ? -1 : head.id;
  }

  @Override
  public boolean cancel(long orderId) {
    Node n = find(orderId);
    if (n == null) return false;
    remove(n);
    return true;
  }

  private Node find(long id) {
    for (Node n = table[mix(id, mask)]; n != null; n = n.hnext) {
      if (n.id == id) return n;
    }
    return null;
  }

  /** Unlinks the node from BOTH the queue list and its hash bucket chain, then drops it. */
  private void remove(Node n) {
    if (n.prev == null) head = n.next;
    else n.prev.next = n.next;
    if (n.next == null) tail = n.prev;
    else n.next.prev = n.prev;
    int b = mix(n.id, mask);
    Node cur = table[b];
    if (cur == n) {
      table[b] = n.hnext;
    } else {
      while (cur.hnext != n) cur = cur.hnext;
      cur.hnext = n.hnext;
    }
    count--;
  }

  @Override
  public int size() {
    return count;
  }

  @Override
  public int dump(long[] orderIds, long[] volumes) {
    int i = 0;
    for (Node c = head; c != null; c = c.next) {
      orderIds[i] = c.id;
      volumes[i] = c.volume;
      i++;
    }
    return i;
  }
}
