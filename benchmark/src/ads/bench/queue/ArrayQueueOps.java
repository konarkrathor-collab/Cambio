package ads.bench.queue;

/**
 * CANDIDATE (doc 05 §2): plain dynamic array with a head cursor — O(1) enqueue/dequeue
 * (amortized, with compaction on growth), but an arbitrary cancel must scan to find the
 * id AND shift the tail left to close the gap: O(n). Demonstrates why arrays lose the
 * moment deletion is in the workload, however cheap their FIFO ends are.
 */
public final class ArrayQueueOps implements QueueOps {
  private long[] ids;
  private long[] vols;
  private int head; // live range is [head, tail)
  private int tail;

  public ArrayQueueOps() {
    ids = new long[16];
    vols = new long[16];
  }

  @Override
  public void enqueue(long orderId, long volume) {
    if (tail == ids.length) {
      compactOrGrow();
    }
    ids[tail] = orderId;
    vols[tail] = volume;
    tail++;
  }

  private void compactOrGrow() {
    if (head > 0) {
      int live = tail - head;
      System.arraycopy(ids, head, ids, 0, live);
      System.arraycopy(vols, head, vols, 0, live);
      tail = live;
      head = 0;
      return;
    }
    int ncap = ids.length * 2;
    long[] ni = new long[ncap];
    long[] nv = new long[ncap];
    System.arraycopy(ids, 0, ni, 0, tail);
    System.arraycopy(vols, 0, nv, 0, tail);
    ids = ni;
    vols = nv;
  }

  @Override
  public long dequeueHead() {
    if (head == tail) return -1;
    long id = ids[head];
    head++;
    if (head == tail) {
      head = 0;
      tail = 0;
    }
    return id;
  }

  @Override
  public boolean cancel(long orderId) {
    for (int i = head; i < tail; i++) {
      if (ids[i] == orderId) {
        int moved = tail - i - 1;
        System.arraycopy(ids, i + 1, ids, i, moved);
        System.arraycopy(vols, i + 1, vols, i, moved);
        tail--;
        if (head == tail) {
          head = 0;
          tail = 0;
        }
        return true;
      }
    }
    return false;
  }

  @Override
  public long peekHead() {
    return head == tail ? -1 : ids[head];
  }

  @Override
  public int size() {
    return tail - head;
  }

  @Override
  public int dump(long[] orderIds, long[] volumes) {
    int n = tail - head;
    System.arraycopy(ids, head, orderIds, 0, n);
    System.arraycopy(vols, head, volumes, 0, n);
    return n;
  }
}
