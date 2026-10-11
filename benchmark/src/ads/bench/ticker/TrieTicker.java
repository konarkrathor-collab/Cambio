package ads.bench.ticker;

/**
 * CANDIDATE (doc 05 §5, THE PROJECT PICK): fixed-alphabet trie — every node carries the
 * full 27-slot child array ('A'-'Z' plus '/'), so navigation is one array index per
 * character with zero comparisons; prefix search walks to the prefix's node and
 * DFS-collects the subtree. It is the pick because prefix queries are this subsystem's
 * reason to exist, and this is the structure that exists for exactly that. Terminal nodes
 * store the original String reference, so autocomplete returns existing strings with zero
 * allocation — the same thing the sorted array gets for free — keeping the comparison
 * structural rather than about result-materialization allocation.
 */
public final class TrieTicker implements TickerOps {
  private static final int ALPHA = 27; // 'A'..'Z' then '/' at slot 26

  private static final class Node {
    final Node[] children = new Node[ALPHA];
    int bitmap; // bit s set iff children[s] != null — iterate present children only
    boolean isEnd;
    String stored; // terminal nodes hold the original string (zero-alloc results)
  }

  private final Node root = new Node();
  private int count;

  private static int slot(char c) {
    return c == '/' ? 26 : c - 'A';
  }

  @Override
  public void insert(String ticker) {
    Node n = root;
    for (int i = 0; i < ticker.length(); i++) {
      int s = slot(ticker.charAt(i));
      if (n.children[s] == null) {
        n.children[s] = new Node();
        n.bitmap |= 1 << s;
      }
      n = n.children[s];
    }
    if (!n.isEnd) {
      n.isEnd = true;
      n.stored = ticker;
      count++;
    }
  }

  @Override
  public boolean contains(String ticker) {
    Node n = root;
    for (int i = 0; i < ticker.length(); i++) {
      n = n.children[slot(ticker.charAt(i))];
      if (n == null) return false;
    }
    return n.isEnd;
  }

  @Override
  public int prefixSearch(String prefix, String[] out) {
    Node n = root;
    for (int i = 0; i < prefix.length(); i++) {
      n = n.children[slot(prefix.charAt(i))];
      if (n == null) return 0;
    }
    return collect(n, out, 0);
  }

  /** Enumerates every ticker in n's subtree, walking only PRESENT children (child bitmap). */
  private int collect(Node n, String[] out, int k) {
    if (n.isEnd) out[k++] = n.stored;
    for (int b = n.bitmap; b != 0; b &= b - 1) {
      k = collect(n.children[Integer.numberOfTrailingZeros(b)], out, k);
    }
    return k;
  }

  @Override
  public int size() {
    return count;
  }
}
