package ads.bench.queue;

/**
 * CANDIDATE (doc 05 §2): singly linked list with a tail pointer — O(1) enqueue (append),
 * dequeue-head and peek, but cancel-by-id needs an O(n) forward scan (no back pointers,
 * and no index to jump to the node). The cheapest list to build; the most expensive to
 * cancel in.
 */
public final class SinglyListQueueOps implements QueueOps {
  private static final class Node {
    final long id;
    final long volume;
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
    }
    count--;
    return id;
  }

  @Override
  public boolean cancel(long orderId) {
    Node prev = null;
    for (Node cur = head; cur != null; prev = cur, cur = cur.next) {
      if (cur.id == orderId) {
        if (prev == null) {
          head = cur.next;
        } else {
          prev.next = cur.next;
        }
        if (tail == cur) {
          tail = prev;
        }
        count--;
        return true;
      }
    }
    return false;
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
