package ads.sim;

import ads.engine.MatchingEngine;

/**
 * The naive baseline (doc 08 Stage 4, the "linear scan" stand-in of doc 04 Phase 5): doc 08
 * Appendix A over plain arrays and nothing else. Each pair keeps its resting orders, both
 * sides, in one array in arrival order. Every order scans that array for the best opposite
 * price (the first one found at a price is the oldest, which is time priority); removing an
 * order shifts the rest down; a cancel scans every pair's array for the id; accounts are a
 * plain array searched by trader id. No tree, no index, no hash.
 *
 * <p>It shares no matching code with the engine and is checked against it, not only timed:
 * fed the same stream it must produce the same trades (same {@link Fnv64} checksum), the same
 * rejects and the same cancels.
 */
public final class NaiveMatcher {
  /** Called once per trade, in execution order. */
  public interface Trades {
    void onTrade(long tradeId, long seq, int pairId, long priceTick, long quantity,
        long buyOrderId, long sellOrderId, long buyerId, long sellerId, boolean takerBuys);
  }

  /** submit() result codes: the order outcome, or the reject reason. */
  public static final int OK = 0;
  public static final int UNKNOWN_PAIR = 1;
  public static final int BAD_QUANTITY = 2;
  public static final int BAD_PRICE = 3;
  public static final int NO_LIQUIDITY = 4;
  public static final int MARGIN = 5;

  private final int pairs;
  private final long[][] id;
  private final long[][] trader;
  private final long[][] price;
  private final long[][] remaining;
  private final boolean[][] isBuy;
  private final int[] count;
  private long[] accountTrader = new long[16];
  private long[] accountCash = new long[16];
  private long[] accountMargin = new long[16];
  private int accounts;
  private long nextOrderId = 1;
  private long nextTradeId = 1;
  private long seq;
  private final Trades trades;

  public NaiveMatcher(int pairs, Trades trades) {
    this.pairs = pairs;
    this.trades = trades;
    id = new long[pairs][];
    trader = new long[pairs][];
    price = new long[pairs][];
    remaining = new long[pairs][];
    isBuy = new boolean[pairs][];
    count = new int[pairs];
    for (int p = 0; p < pairs; p++) {
      id[p] = new long[16];
      trader[p] = new long[16];
      price[p] = new long[16];
      remaining[p] = new long[16];
      isBuy[p] = new boolean[16];
    }
  }

  public void open(long traderId, long cash, long marginLimit) {
    if (accounts == accountTrader.length) {
      accountTrader = grow(accountTrader);
      accountCash = grow(accountCash);
      accountMargin = grow(accountMargin);
    }
    accountTrader[accounts] = traderId;
    accountCash[accounts] = cash;
    accountMargin[accounts] = marginLimit;
    accounts++;
  }

  private int account(long traderId) {
    for (int i = 0; i < accounts; i++) {
      if (accountTrader[i] == traderId) return i;
    }
    return -1;
  }

  /** Index of the best resting order opposite a {@code buy} taker on {@code p}, or -1. */
  private int best(int p, boolean buy) {
    int found = -1;
    long[] px = price[p];
    boolean[] side = isBuy[p];
    for (int i = 0; i < count[p]; i++) {
      if (side[i] == buy) continue;
      if (found < 0 || (buy ? px[i] < px[found] : px[i] > px[found])) found = i;
    }
    return found;
  }

  /** Submits an order; returns OK or a reject code. Outcome counts go to the caller's sink. */
  public int submit(long traderId, int p, boolean buy, boolean market, long limitTick,
      long quantity) {
    long orderId = nextOrderId++;
    long now = ++seq;
    if (p < 0 || p >= pairs) return UNKNOWN_PAIR;
    if (quantity <= 0 || quantity > MatchingEngine.MAX_QUANTITY) return BAD_QUANTITY;
    if (!market && (limitTick <= 0 || limitTick > MatchingEngine.MAX_PRICE_TICK)) return BAD_PRICE;
    int top = best(p, buy);
    if (market && top < 0) return NO_LIQUIDITY;
    int acct = account(traderId);
    if (acct < 0) return MARGIN;
    long exposurePrice = market ? price[p][top] : limitTick;
    long exposure;
    try {
      exposure = Math.multiplyExact(quantity, exposurePrice);
    } catch (ArithmeticException overflow) {
      return MARGIN;
    }
    long allowed = buy ? Math.min(accountCash[acct], accountMargin[acct]) : accountMargin[acct];
    if (exposure > allowed) return MARGIN;

    long left = quantity;
    while (left > 0) {
      int m = best(p, buy);
      if (m < 0) break;
      long makerPrice = price[p][m];
      if (!market && (buy ? makerPrice > limitTick : makerPrice < limitTick)) break;
      long q = Math.min(left, remaining[p][m]);
      long makerTrader = trader[p][m];
      int makerAcct = account(makerTrader);
      long notional = makerPrice * q;
      int buyer = buy ? acct : makerAcct;
      int seller = buy ? makerAcct : acct;
      accountCash[buyer] = Math.subtractExact(accountCash[buyer], notional);
      accountCash[seller] = Math.addExact(accountCash[seller], notional);
      trades.onTrade(nextTradeId++, now, p, makerPrice, q,
          buy ? orderId : id[p][m], buy ? id[p][m] : orderId,
          buy ? traderId : makerTrader, buy ? makerTrader : traderId, buy);
      left -= q;
      remaining[p][m] -= q;
      if (remaining[p][m] == 0) removeAt(p, m);
    }
    if (left > 0 && !market) append(p, orderId, traderId, limitTick, left, buy);
    return OK;
  }

  /** Cancels by id; returns the remaining quantity, or -1 if no such order rests. */
  public long cancel(long orderId) {
    seq++;
    for (int p = 0; p < pairs; p++) {
      long[] ids = id[p];
      for (int i = 0; i < count[p]; i++) {
        if (ids[i] == orderId) {
          long left = remaining[p][i];
          removeAt(p, i);
          return left;
        }
      }
    }
    return -1;
  }

  /** Number of resting orders across every pair. */
  public int restingOrders() {
    int n = 0;
    for (int p = 0; p < pairs; p++) n += count[p];
    return n;
  }

  private void append(int p, long orderId, long traderId, long tick, long qty, boolean buy) {
    int n = count[p];
    if (n == id[p].length) {
      id[p] = grow(id[p]);
      trader[p] = grow(trader[p]);
      price[p] = grow(price[p]);
      remaining[p] = grow(remaining[p]);
      boolean[] b = new boolean[n * 2];
      System.arraycopy(isBuy[p], 0, b, 0, n);
      isBuy[p] = b;
    }
    id[p][n] = orderId;
    trader[p][n] = traderId;
    price[p][n] = tick;
    remaining[p][n] = qty;
    isBuy[p][n] = buy;
    count[p] = n + 1;
  }

  private void removeAt(int p, int i) {
    int tail = count[p] - i - 1;
    System.arraycopy(id[p], i + 1, id[p], i, tail);
    System.arraycopy(trader[p], i + 1, trader[p], i, tail);
    System.arraycopy(price[p], i + 1, price[p], i, tail);
    System.arraycopy(remaining[p], i + 1, remaining[p], i, tail);
    System.arraycopy(isBuy[p], i + 1, isBuy[p], i, tail);
    count[p]--;
  }

  private static long[] grow(long[] a) {
    long[] b = new long[a.length * 2];
    System.arraycopy(a, 0, b, 0, a.length);
    return b;
  }
}
