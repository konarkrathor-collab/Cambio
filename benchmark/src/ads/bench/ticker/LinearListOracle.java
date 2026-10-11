package ads.bench.ticker;

import java.util.ArrayList;
import java.util.List;

/**
 * ORACLE (Phase 1): the deliberately naive reference — a linear list scanned in full for
 * every query (contains = linear search; prefix search = filter with startsWith).
 * Obviously correct and trivially independent of every candidate's indexing strategy.
 *
 * <p>Also timed as the "naive linear scan" baseline row: at ~132 pairs this is the
 * approach a first draft would reach for, and the benchmark quantifies why the structures
 * exist at all.
 */
public final class LinearListOracle implements TickerOps {
  private final List<String> tickers = new ArrayList<>();

  @Override
  public void insert(String ticker) {
    if (!tickers.contains(ticker)) {
      tickers.add(ticker);
    }
  }

  @Override
  public boolean contains(String ticker) {
    return tickers.contains(ticker);
  }

  @Override
  public int prefixSearch(String prefix, String[] out) {
    int k = 0;
    for (int i = 0; i < tickers.size(); i++) {
      String t = tickers.get(i);
      if (t.startsWith(prefix)) {
        out[k++] = t;
      }
    }
    return k;
  }

  @Override
  public int size() {
    return tickers.size();
  }
}
