package ads.directory;

/**
 * The ticker directory service: the 12-currency, 132-pair universe of the ticker benchmark,
 * held in a {@link TickerTrie}. Pair ids are the trie's dense ids, so the matching engine
 * keeps its books in an array indexed by pair id. Also maps currencies to small indexes
 * for the scanner and carries each pair's tick size.
 *
 * <p>Tick size (Appendix A says each pair has one; the engine itself only sees integer
 * ticks): {@code 10^-3} of the quote currency when the quote is JPY, otherwise
 * {@code 10^-5}, held as a decimal exponent so no floating point is involved.
 */
public final class Directory {
  /** The ticker benchmark's roster, in its order. Index = currency id. */
  private static final String[] CURRENCIES =
      {"USD", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "INR", "CNY", "HKD", "SGD", "NZD"};

  private final TickerTrie trie = new TickerTrie();
  private final int[] base;
  private final int[] quote;

  public Directory() {
    int n = CURRENCIES.length;
    base = new int[n * (n - 1)];
    quote = new int[n * (n - 1)];
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < n; j++) {
        if (i == j) continue;
        int id = trie.insert(CURRENCIES[i] + "/" + CURRENCIES[j]);
        base[id] = i;
        quote[id] = j;
      }
    }
  }

  /** Pair id for "BASE/QUOTE" (case-sensitive), or -1 if not listed. */
  public int pairId(String pair) {
    return trie.idOf(pair);
  }

  /** The pair's name, e.g. "EUR/USD". */
  public String pair(int pairId) {
    return trie.tickerOf(pairId);
  }

  public int pairCount() {
    return trie.size();
  }

  public boolean contains(String pair) {
    return trie.contains(pair);
  }

  /** Fills {@code out} with listed pairs starting with {@code prefix}; returns how many. */
  public int prefixSearch(String prefix, String[] out) {
    return trie.prefixSearch(prefix, out);
  }

  /** Pair id for a currency pair given by currency ids, or -1. */
  public int pairId(int baseCurrency, int quoteCurrency) {
    if (baseCurrency == quoteCurrency || baseCurrency < 0 || quoteCurrency < 0
        || baseCurrency >= CURRENCIES.length || quoteCurrency >= CURRENCIES.length) {
      return -1;
    }
    return trie.idOf(CURRENCIES[baseCurrency] + "/" + CURRENCIES[quoteCurrency]);
  }

  public int baseCurrency(int pairId) {
    return base[pairId];
  }

  public int quoteCurrency(int pairId) {
    return quote[pairId];
  }

  public int currencyCount() {
    return CURRENCIES.length;
  }

  /** The currency code for a currency id. */
  public String currency(int id) {
    return CURRENCIES[id];
  }

  /** Currency id for a three-letter code, or -1. */
  public int currencyId(String code) {
    for (int i = 0; i < CURRENCIES.length; i++) {
      if (CURRENCIES[i].equals(code)) return i;
    }
    return -1;
  }

  /** Decimal places of the pair's tick: the tick is {@code 10^-tickDecimals} of the quote. */
  public int tickDecimals(int pairId) {
    return CURRENCIES[quote[pairId]].equals("JPY") ? 3 : 5;
  }
}
