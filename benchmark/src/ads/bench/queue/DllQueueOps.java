package ads.bench.queue;

/**
 * CANDIDATE (doc 05 §2): plain doubly linked list with NO index — dequeue-head, peek and
 * enqueue are all O(1), and the unlink after finding a node is O(1) too, but LOCATING the
 * node for a cancel-by-id is still an O(n) scan. This is the candidate that proves the
 * doc's point: "the unlink is cheap, but finding it isn't" — the pick's hash map exists
 * purely to close exactly this gap.
 */
public final class DllQueueOps implements QueueOps {
  private static final class Node {
    final long id;
    final long volume;
    Node prev;
    Node next;

    Node(long id, long volume) {
      this.id = id;
      this.volume = volume;
    }
  }

  private Node head;
  private Node tail;
  private int count;

  @Override
  public void enqueue(long orderId, long volume) {
    Node n = new Node(orderId, volume);
    if (tail == null) {
      head = n;
    } else {
      n.prev = tail;
      tail.next = n;
    }
    tail = n;
    count++;
  }

  @Override
  public long dequeueHead() {
    if (head == null) return -1;
    long id = head.id;
    head = head.next;
    if (head == null) {
      tail = null;
    } else {
      head.prev = null;
    }
    count--;
    return id;
  }

  @Override
  public boolean cancel(long orderId) {
    for (Node cur = head; cur != null; cur = cur.next) {
      if (cur.id == orderId) {
        unlink(cur);
        return true;
      }
    }
    return false;
  }

  private void unlink(Node n) {
    if (n.prev == null) {
      head = n.next;
    } else {
      n.prev.next = n.next;
    }
    if (n.next == null) {
      tail = n.prev;
    } else {
      n.next.prev = n.prev;
    }
    count--;
  }

  @Override
  public long peekHead() {
    return head == null ? -1 : head.id;
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
