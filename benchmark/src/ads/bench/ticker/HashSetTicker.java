package ads.bench.ticker;

/**
 * CANDIDATE (doc 05 §5): hand-rolled separate-chaining hash set. Fastest-possible exact
 * match — and STRUCTURALLY INCAPABLE of prefix search: a hash set has no ordered/prefix
 * structure to walk, so {@code prefixSearch} throws {@link UnsupportedOperationException}
 * by construction. The harness reports that as an explicit N/A row (never a silent skip);
 * this is precisely the gap doc 05 §5's rubric-limitation note flagged when Hash Set still
 * tied the TST and Sorted Array at 3.65.
 */
public final class HashSetTicker implements TickerOps {
  private static final class Node {
    final String key;
    Node next;

    Node(String key, Node next) {
      this.key = key;
      this.next = next;
    }
  }

  private Node[] table = new Node[16];
  private int mask = 15;
  private int threshold = 12;
  private int count;

  private static int mix(String s, int mask) {
    int h = s.hashCode();
    h ^= h >>> 16;
    return h & mask;
  }

  @Override
  public void insert(String ticker) {
    if (contains(ticker)) return; // idempotent, as the contract requires
    int b = mix(ticker, mask);
    table[b] = new Node(ticker, table[b]);
    count++;
    if (count > threshold) grow();
  }

  private void grow() {
    Node[] nt = new Node[table.length * 2];
    int nm = nt.length - 1;
    for (int b = 0; b < table.length; b++) {
      Node n = table[b];
      while (n != null) {
        Node nxt = n.next;
        int nb = mix(n.key, nm);
        n.next = nt[nb];
        nt[nb] = n;
        n = nxt;
      }
    }
    table = nt;
    mask = nm;
    threshold = (int) (nt.length * 0.75);
  }

  @Override
  public boolean contains(String ticker) {
    for (Node n = table[mix(ticker, mask)]; n != null; n = n.next) {
      if (n.key.equals(ticker)) return true;
    }
    return false;
  }

  @Override
  public int prefixSearch(String prefix, String[] out) {
    throw new UnsupportedOperationException(
        "hash set has no prefix structure: prefixSearch would require a full scan, "
            + "which is not what a hash set is for");
  }

  @Override
  public int size() {
    return count;
  }
}
