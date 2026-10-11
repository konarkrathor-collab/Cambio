package ads.bench.ticker;

/**
 * CANDIDATE (doc 05 §5): sorted array + binary search. Exact match is O(log n); prefix
 * search exploits the sorted order directly — lower-bound the prefix, then scan the
 * contiguous block of matches (the standard trick: all strings starting with a prefix form
 * one sorted run beginning at the prefix's lower bound). With only ~132 entries, memory
 * locality is excellent — the open question is whether that beats the trie/TST at this
 * scale despite the asymptotically worse per-op work.
 */
public final class SortedArrayTicker implements TickerOps {
  private String[] keys = new String[16];
  private int count;

  /** First index with keys[i].compareTo(key) >= 0. */
  private int lowerBound(String key) {
    int lo = 0;
    int hi = count;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (keys[mid].compareTo(key) < 0) lo = mid + 1;
      else hi = mid;
    }
    return lo;
  }

  @Override
  public void insert(String ticker) {
    int i = lowerBound(ticker);
    if (i < count && keys[i].equals(ticker)) return; // already registered
    if (count == keys.length) grow();
    System.arraycopy(keys, i, keys, i + 1, count - i);
    keys[i] = ticker;
    count++;
  }

  private void grow() {
    String[] nk = new String[keys.length * 2];
    System.arraycopy(keys, 0, nk, 0, count);
    keys = nk;
  }

  @Override
  public boolean contains(String ticker) {
    int i = lowerBound(ticker);
    return i < count && keys[i].equals(ticker);
  }

  @Override
  public int prefixSearch(String prefix, String[] out) {
    int k = 0;
    for (int i = lowerBound(prefix); i < count && keys[i].startsWith(prefix); i++) {
      out[k++] = keys[i];
    }
    return k;
  }

  @Override
  public int size() {
    return count;
  }
}
