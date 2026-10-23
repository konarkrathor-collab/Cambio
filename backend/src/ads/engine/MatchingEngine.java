package ads.engine;

import ads.directory.Directory;
import ads.vault.Account;
import ads.vault.Vault;

/**
 * The matching engine: one {@link OrderBook} per Directory pair (an array indexed by pair
 * id), one engine-wide {@link OrderIndex}, and the rules of doc 08 Appendix A, verbatim:
 *
 * <ul>
 *   <li>Price-time priority: best price first, FIFO within a price.</li>
 *   <li>An incoming BUY matches asks with {@code askTick <= limitTick} (MARKET: any ask),
 *       lowest first; a SELL mirrors it against bids. Each fill is
 *       {@code min(incomingRemaining, restingRemaining)} at the RESTING order's price.</li>
 *   <li>A LIMIT remainder rests at the tail of its level; a MARKET remainder is cancelled.</li>
 *   <li>Margin: a BUY needs {@code qty x limitTick <= min(cash, marginLimit)}, a SELL
 *       {@code qty x limitTick <= marginLimit}; a MARKET order is checked at the best
 *       opposite tick and is rejected NO_LIQUIDITY if there is none. Each trade moves
 *       {@code notional} from buyer cash to seller cash. Self-trades are not prevented.</li>
 *   <li>Order ids come from a counter (every submission takes one, rejected or not); the
 *       engine's sequence number advances once per submit and once per cancel.</li>
 * </ul>
 *
 * <p>Single-threaded and deterministic: the same command log always produces the same
 * trades. Integer arithmetic only. No JDK collections on this path.
 */
public final class MatchingEngine {
  /** Largest accepted quantity; with MAX_PRICE_TICK it keeps every notional inside a long. */
  public static final long MAX_QUANTITY = 1_000_000_000L;
  /** Largest accepted limit price in ticks. */
  public static final long MAX_PRICE_TICK = 1_000_000_000L;

  private final Directory directory;
  private final Vault vault;
  private final OrderBook[] books;
  private final OrderIndex index = new OrderIndex();
  private TradeSink sink;
  private long nextOrderId = 1;
  private long nextTradeId = 1;
  private long seq;
  private Trade[] tradeBuf = new Trade[16];

  public MatchingEngine(Directory directory, Vault vault) {
    this.directory = directory;
    this.vault = vault;
    this.books = new OrderBook[directory.pairCount()];
    for (int i = 0; i < books.length; i++) {
      books[i] = new OrderBook(i, new BPlusTreeLadder(), new BPlusTreeLadder());
    }
  }

  /** Every trade is passed to {@code sink} as it executes (null for none). */
  public void setTradeSink(TradeSink sink) {
    this.sink = sink;
  }

  /** Submits an order for a pair given by name; an unknown name is rejected UNKNOWN_PAIR. */
  public OrderResult submit(long traderId, String pair, Side side, OrderType type,
      long priceTick, long quantity) {
    int pairId = pair == null ? -1 : directory.pairId(pair);
    return submit(traderId, pairId, side, type, priceTick, quantity);
  }

  /** Submits an order. {@code priceTick} is ignored for MARKET orders. */
  public OrderResult submit(long traderId, int pairId, Side side, OrderType type,
      long priceTick, long quantity) {
    if (side == null || type == null) {
      throw new IllegalArgumentException("side and type are required");
    }
    long orderId = nextOrderId++;
    long now = ++seq;
    RejectReason reason = validate(traderId, pairId, side, type, priceTick, quantity);
    if (reason != null) {
      return reject(orderId, reason);
    }
    OrderBook book = books[pairId];
    boolean buy = side == Side.BUY;
    PriceLadder opposite = buy ? book.asks : book.bids;
    Account account = vault.account(traderId);

    long remaining = quantity;
    int tradeCount = 0;
    while (remaining > 0) {
      PriceLevel best = buy ? opposite.lowest() : opposite.highest();
      if (best == null) break;
      if (type == OrderType.LIMIT && (buy ? best.price > priceTick : best.price < priceTick)) {
        break;
      }
      while (remaining > 0) {
        Order maker = best.head();
        if (maker == null) break;
        long q = Math.min(remaining, maker.remaining);
        Account makerAccount = vault.account(maker.traderId);
        if (makerAccount == null) {
          throw new IllegalStateException("resting order " + maker.orderId
              + " has no account " + maker.traderId);
        }
        if (!Vault.canSettle(buy ? account : makerAccount, buy ? makerAccount : account,
            best.price * q)) {
          // the resting order's balance cannot absorb this fill without leaving the range of
          // a long: it is withdrawn from the book, the fill does not happen (B14); the
          // taker's own side was checked by validate()
          index.remove(maker.orderId);
          best.unlink(maker);
          continue;
        }
        Trade t = buy
            ? new Trade(nextTradeId++, now, pairId, best.price, q, orderId, maker.orderId,
                traderId, maker.traderId, side)
            : new Trade(nextTradeId++, now, pairId, best.price, q, maker.orderId, orderId,
                maker.traderId, traderId, side);
        vault.settle(buy ? account : makerAccount, buy ? makerAccount : account, t.notional());
        if (tradeCount == tradeBuf.length) {
          Trade[] grown = new Trade[tradeBuf.length * 2];
          System.arraycopy(tradeBuf, 0, grown, 0, tradeCount);
          tradeBuf = grown;
        }
        tradeBuf[tradeCount++] = t;
        if (sink != null) sink.onTrade(t);
        remaining -= q;
        if (best.fill(maker, q) == 0) {
          index.remove(maker.orderId);
        }
      }
      if (best.isEmpty()) {
        opposite.remove(best.price);
      }
    }

    Trade[] trades = new Trade[tradeCount];
    System.arraycopy(tradeBuf, 0, trades, 0, tradeCount);
    for (int i = 0; i < tradeCount; i++) tradeBuf[i] = null;
    long filled = quantity - remaining;
    if (remaining == 0) {
      return new OrderResult(orderId, OrderStatus.FILLED, null, filled, 0, trades);
    }
    if (type == OrderType.MARKET) {
      // liquidity existed at the margin check; it can only have vanished if every resting
      // order it met was withdrawn (B14), and then nothing traded
      if (filled == 0) return reject(orderId, RejectReason.NO_LIQUIDITY);
      return new OrderResult(orderId, OrderStatus.PARTIAL, null, filled, 0, trades);
    }
    Order resting = new Order(orderId, pairId, side, traderId, priceTick, remaining, now);
    book.ladder(side).getOrCreate(priceTick).append(resting);
    index.put(resting);
    return new OrderResult(orderId, filled > 0 ? OrderStatus.PARTIAL : OrderStatus.QUEUED,
        null, filled, remaining, trades);
  }

  /**
   * Appendix A validation precedence: the first failing check wins. One self-contained
   * check per line, in the normative order; each helper is total (no exceptions on invalid
   * input), so the order of the lines alone decides which reason an order that is wrong
   * in several ways gets.
   */
  private RejectReason validate(long traderId, int pairId, Side side, OrderType type,
      long priceTick, long quantity) {
    if (!knownPair(pairId)) return RejectReason.UNKNOWN_PAIR;
    if (quantity <= 0 || quantity > MAX_QUANTITY) return RejectReason.BAD_QUANTITY;
    if (type == OrderType.LIMIT && (priceTick <= 0 || priceTick > MAX_PRICE_TICK)) return RejectReason.BAD_PRICE;
    if (type == OrderType.MARKET && bestOpposite(pairId, side) == null) return RejectReason.NO_LIQUIDITY;
    if (!withinMargin(traderId, pairId, side, type, priceTick, quantity)) return RejectReason.MARGIN;
    return null;
  }

  private boolean knownPair(int pairId) {
    return pairId >= 0 && pairId < books.length;
  }

  /** Best level on the side an order of {@code side} trades against, or null. */
  private PriceLevel bestOpposite(int pairId, Side side) {
    if (!knownPair(pairId)) return null;
    OrderBook book = books[pairId];
    return side == Side.BUY ? book.asks.lowest() : book.bids.highest();
  }

  /**
   * Appendix A margin: a BUY needs {@code qty x tick <= min(cash, marginLimit)}, a SELL
   * {@code qty x tick <= marginLimit}; the tick is the limit price, or for a MARKET order
   * the best opposite price. No account means no margin.
   */
  private boolean withinMargin(long traderId, int pairId, Side side, OrderType type,
      long priceTick, long quantity) {
    Account account = vault.account(traderId);
    if (account == null) return false;
    long tick = priceTick;
    if (type == OrderType.MARKET) {
      PriceLevel best = bestOpposite(pairId, side);
      if (best == null) return true; // that is NO_LIQUIDITY's verdict, not this check's
      tick = best.price;
    }
    long exposure;
    try {
      exposure = Math.multiplyExact(quantity, tick); // cannot overflow once quantity/price pass
    } catch (ArithmeticException overflow) {
      return false;
    }
    long allowed = side == Side.BUY ? Math.min(account.cash(), account.marginLimit())
        : account.marginLimit();
    if (exposure > allowed) return false;
    // a SELL's proceeds are at most quantity x MAX_PRICE_TICK (<= 10^18): a balance so large
    // that they would overflow it cannot take the trade (B14); a BUY's cost cannot underflow,
    // since its cash covers the exposure at the moment it is checked
    return side == Side.BUY || account.cash() <= Long.MAX_VALUE - quantity * MAX_PRICE_TICK;
  }

  private OrderResult reject(long orderId, RejectReason reason) {
    return new OrderResult(orderId, OrderStatus.REJECTED, reason, 0, 0, null);
  }

  /**
   * Cancels a resting order by id alone: index lookup, then the order's own level pointer,
   * then its pair and side to prune the level from the right ladder if it empties.
   */
  public CancelResult cancel(long orderId) {
    seq++;
    Order o = index.remove(orderId);
    if (o == null) {
      return new CancelResult(orderId, CancelStatus.NOT_FOUND, 0);
    }
    long remaining = o.remaining;
    PriceLevel level = o.level;
    level.unlink(o);
    if (level.isEmpty()) {
      books[o.pairId].ladder(o.side).remove(level.price);
    }
    return new CancelResult(orderId, CancelStatus.CANCELLED, remaining);
  }

  /** The resting order with this id, or null. */
  public Order order(long orderId) {
    return index.get(orderId);
  }

  public OrderBook book(int pairId) {
    return books[pairId];
  }

  public int pairCount() {
    return books.length;
  }

  /** Number of resting orders across every book. */
  public int restingOrders() {
    return index.size();
  }

  /** The engine sequence number of the last command. */
  public long sequence() {
    return seq;
  }

  /** The id the next submitted order will get. */
  public long nextOrderId() {
    return nextOrderId;
  }

  /** The id the next trade will get. */
  public long nextTradeId() {
    return nextTradeId;
  }

  /**
   * Continues the counters of an earlier session, so order ids, trade ids and the sequence
   * number never repeat across restarts. Allowed only before the first command, and never
   * backwards.
   */
  public void restoreCounters(long nextOrderId, long nextTradeId, long seq) {
    if (this.seq != 0 || this.nextOrderId != 1 || this.nextTradeId != 1) {
      throw new IllegalStateException("counters can only be restored before the first command");
    }
    if (nextOrderId < 1 || nextTradeId < 1 || seq < 0) {
      throw new IllegalArgumentException("bad counters " + nextOrderId + "/" + nextTradeId + "/" + seq);
    }
    this.nextOrderId = nextOrderId;
    this.nextTradeId = nextTradeId;
    this.seq = seq;
  }
}
