package ads.test;

import ads.bench.ticker.TickerOps;
import ads.directory.TickerTrie;

/** Presents the production {@link TickerTrie} as the benchmark's {@link TickerOps}. */
final class TickerAdapter implements TickerOps {
  private final TickerTrie trie = new TickerTrie();

  @Override
  public void insert(String ticker) {
    trie.insert(ticker);
  }

  @Override
  public boolean contains(String ticker) {
    return trie.contains(ticker);
  }

  @Override
  public int prefixSearch(String prefix, String[] out) {
    return trie.prefixSearch(prefix, out);
  }

  @Override
  public int size() {
    return trie.size();
  }
}
