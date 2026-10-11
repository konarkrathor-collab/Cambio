package ads.bench.cache;

/**
 * CANDIDATE (doc 05 §4, rejected): splay tree keyed by traderId with NO eviction — the
 * structure that is supposed to shine under Zipfian access (hot accounts splay to the
 * root). This benchmark's question: does its amortized advantage actually materialize
 * under a genuine Zipf stream, or does the rubric's rejection (unbounded memory, O(n)
 * worst-case single access, no eviction) hold anyway? Standard recursive bottom-up splay
 * (Sedgewick's formulation); Zipf access patterns keep tree depths modest in practice.
 */
public final class SplayCache implements CacheOps {
  private static final class Node {
    final long id;
    final long balance;
    Node left;
    Node right;

    Node(long id, long balance) {
      this.id = id;
      this.balance = balance;
    }
  }

  private Node root;
  private int count;

  @Override
  public long access(long traderId) {
    root = splay(root, traderId);
    if (root != null && root.id == traderId) {
      return root.balance; // splayed to the root = the touch
    }
    long bal = AccountStore.balance(traderId);
    Node n = new Node(traderId, bal);
    if (root == null) {
      root = n;
    } else if (traderId < root.id) {
      n.right = root;
      n.left = root.left;
      root.left = null;
      root = n;
    } else {
      n.left = root;
      n.right = root.right;
      root.right = null;
      root = n;
    }
    count++;
    return bal;
  }

  /** Splays id to the root if present, else the last accessed node. Standard formulation. */
  private static Node splay(Node h, long id) {
    if (h == null) return null;
    if (id < h.id) {
      if (h.left == null) return h;
      if (id < h.left.id) {
        h.left.left = splay(h.left.left, id);
        h = rotateRight(h);
      } else if (id > h.left.id) {
        h.left.right = splay(h.left.right, id);
        if (h.left.right != null) h.left = rotateLeft(h.left);
      }
      return h.left == null ? h : rotateRight(h);
    } else if (id > h.id) {
      if (h.right == null) return h;
      if (id > h.right.id) {
        h.right.right = splay(h.right.right, id);
        h = rotateLeft(h);
      } else if (id < h.right.id) {
        h.right.left = splay(h.right.left, id);
        if (h.right.left != null) h.right = rotateRight(h.right);
      }
      return h.right == null ? h : rotateLeft(h);
    } else {
      return h;
    }
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

  /** Pure residency probe: plain BST search, no splaying (see contract). */
  @Override
  public boolean contains(long traderId) {
    Node h = root;
    while (h != null) {
      if (traderId < h.id) h = h.left;
      else if (traderId > h.id) h = h.right;
      else return true;
    }
    return false;
  }

  @Override
  public int size() {
    return count;
  }
}
