package ads.directory;

/**
 * The ticker directory (doc 05 §5), ported from the benchmark's {@code TrieTicker}: a
 * fixed-alphabet trie where every node carries the full 27-slot child array ('A'-'Z' plus
 * '/'), so navigation is one array index per character with zero comparisons. Prefix
 * search walks to the prefix's node and collects its subtree, visiting only PRESENT
 * children via the node's child bitmap. Terminal nodes store the original String, so
 * results are returned with zero allocation. No JDK collections.
 *
 * <p>Changes from the benchmark, for untrusted input (see backend/BUGS.md, B1):
 * {@code contains} and {@code prefixSearch} treat a character outside the alphabet as "no
 * match" instead of indexing out of bounds, {@code insert} rejects it, and
 * {@code prefixSearch} stops when the caller's buffer is full.
 *
 * <p>Stage 1 addition: every registered ticker gets a small dense id (0, 1, 2, ... in
 * insertion order), stored at its terminal node, so books can live in an array indexed by
 * pair id instead of a map: {@link #idOf} and {@link #tickerOf}.
 */
public final class TickerTrie {
  private static final int ALPHA = 27; // 'A'..'Z' then '/' at slot 26

  private static final class Node {
    final Node[] children = new Node[ALPHA];
    int bitmap; // bit s set iff children[s] != null — iterate present children only
    boolean isEnd;
    String stored; // terminal nodes hold the original string (zero-alloc results)
    int id = -1;   // dense ticker id at terminal nodes
  }

  private final Node root = new Node();
  private int count;
  private String[] byId = new String[16];

  /** Child slot for c, or -1 if c is outside the alphabet. */
  private static int slot(char c) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    return c == '/' ? 26 : -1;
  }

  /**
   * Registers a ticker (idempotent) and returns its id. Characters must be 'A'-'Z' or '/'.
   */
  public int insert(String ticker) {
    for (int i = 0; i < ticker.length(); i++) {
      if (slot(ticker.charAt(i)) < 0) {
        throw new IllegalArgumentException("ticker has a character outside A-Z and '/': "
            + ticker);
      }
    }
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
      if (count == byId.length) {
        String[] grown = new String[byId.length * 2];
        System.arraycopy(byId, 0, grown, 0, count);
        byId = grown;
      }
      n.id = count;
      byId[count] = ticker;
      count++;
    }
    return n.id;
  }

  /** Exact-match validation. */
  public boolean contains(String ticker) {
    return idOf(ticker) >= 0;
  }

  /** The ticker's id, or -1 if it is not registered (or has characters outside the alphabet). */
  public int idOf(String ticker) {
    Node n = root;
    for (int i = 0; i < ticker.length(); i++) {
      int s = slot(ticker.charAt(i));
      if (s < 0) return -1;
      n = n.children[s];
      if (n == null) return -1;
    }
    return n.id;
  }

  /** The ticker registered under {@code id}, or null if no such id. */
  public String tickerOf(int id) {
    return id >= 0 && id < count ? byId[id] : null;
  }

  /**
   * Fills {@code out} with registered tickers starting with {@code prefix} (alphabet slot
   * order: 'A'-'Z', then '/') and returns how many; stops once {@code out} is full.
   */
  public int prefixSearch(String prefix, String[] out) {
    Node n = root;
    for (int i = 0; i < prefix.length(); i++) {
      int s = slot(prefix.charAt(i));
      if (s < 0) return 0;
      n = n.children[s];
      if (n == null) return 0;
    }
    return collect(n, out, 0);
  }

  /** Enumerates the tickers in n's subtree, walking only PRESENT children (child bitmap). */
  private int collect(Node n, String[] out, int k) {
    if (n.isEnd) {
      if (k == out.length) return k;
      out[k++] = n.stored;
    }
    for (int b = n.bitmap; b != 0 && k < out.length; b &= b - 1) {
      k = collect(n.children[Integer.numberOfTrailingZeros(b)], out, k);
    }
    return k;
  }

  /** Registered tickers. */
  public int size() {
    return count;
  }
}
