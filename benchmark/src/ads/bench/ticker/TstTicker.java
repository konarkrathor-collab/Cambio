package ads.bench.ticker;

/**
 * CANDIDATE (doc 05 §5): ternary search tree over chars. Exact match walks left/mid/right
 * per character (comparisons instead of array indexing); prefix search navigates to the
 * node of the prefix's last character, then enumerates the mid-path subtree. More compact
 * than a fixed-array trie at large sparse alphabets, paying for it with per-char
 * comparisons. Insertion order comes from the workload's seeded shuffle, so the tree shape
 * is deterministic but history-dependent (no balancing).
 */
public final class TstTicker implements TickerOps {
  private static final class Node {
    char c;
    Node left;
    Node mid;
    Node right;
    boolean isEnd;
    String stored; // terminal nodes hold the original string (zero-alloc results)
  }

  private Node root;
  private int count;

  @Override
  public void insert(String ticker) {
    if (contains(ticker)) return; // idempotent, as the contract requires
    root = insert(root, ticker, 0);
    count++;
  }

  private static Node insert(Node h, String key, int d) {
    char c = key.charAt(d);
    if (h == null) {
      h = new Node();
      h.c = c;
    }
    if (c < h.c) h.left = insert(h.left, key, d);
    else if (c > h.c) h.right = insert(h.right, key, d);
    else if (d < key.length() - 1) h.mid = insert(h.mid, key, d + 1);
    else {
      h.isEnd = true;
      h.stored = key;
    }
    return h;
  }

  @Override
  public boolean contains(String key) {
    Node h = root;
    int d = 0;
    while (h != null) {
      char c = key.charAt(d);
      if (c < h.c) h = h.left;
      else if (c > h.c) h = h.right;
      else if (d < key.length() - 1) {
        h = h.mid;
        d++;
      } else {
        return h.isEnd;
      }
    }
    return false;
  }

  /**
   * NOTE on a bug the oracle gate caught here (found and fixed Run-4-style, not silently):
   * the first version also enumerated the prefix node's LEFT/RIGHT subtrees. At the
   * prefix's FINAL character position, left/right hold keys that differ in that character
   * — they do not share the prefix at all — so the returned set was wrong and the replay
   * checksum failed on the first phase-2 run (FAIL row retained in raw CSV). The fix:
   * below the prefix node only the MID subtree shares the prefix; enumerate it fully
   * (left/right/mid AT depths beyond the first mid are all part of that subtree).
   */
  @Override
  public int prefixSearch(String prefix, String[] out) {
    Node h = root;
    int d = 0;
    while (h != null && d < prefix.length()) {
      char c = prefix.charAt(d);
      if (c < h.c) h = h.left;
      else if (c > h.c) h = h.right;
      else if (d < prefix.length() - 1) {
        h = h.mid;
        d++;
      } else {
        break; // h is the node of the prefix's final char
      }
    }
    if (h == null) return 0;
    int k = 0;
    if (h.isEnd) out[k++] = h.stored; // the prefix itself is a registered ticker
    return collect(h.mid, out, k);    // full enumeration below the prefix node
  }

  /** Enumerates every key in n's subtree (everything beneath shares the ancestor path). */
  private int collect(Node n, String[] out, int k) {
    if (n == null) return k;
    k = collect(n.left, out, k);
    if (n.isEnd) out[k++] = n.stored;
    k = collect(n.mid, out, k);
    return collect(n.right, out, k);
  }

  @Override
  public int size() {
    return count;
  }
}
