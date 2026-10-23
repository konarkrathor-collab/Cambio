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
    if (pairId < 0 || pairId >= books.length) {
      return reject(orderId, RejectReason.UNKNOWN_PAIR);
    }
    if (quantity <= 0 || quantity > MAX_QUANTITY) {
      return reject(orderId, RejectReason.BAD_QUANTITY);
    }
    if (type == OrderType.LIMIT && (priceTick <= 0 || priceTick > MAX_PRICE_TICK)) {
      return reject(orderId, RejectReason.BAD_PRICE);
    }
    OrderBook book = books[pairId];
    boolean buy = side == Side.BUY;
    PriceLadder opposite = buy ? book.asks : book.bids;

    long checkTick = priceTick;
    if (type == OrderType.MARKET) {
      PriceLevel best = buy ? opposite.lowest() : opposite.highest();
      if (best == null) {
        return reject(orderId, RejectReason.NO_LIQUIDITY);
      }
      checkTick = best.price;
    }
    Account account = vault.account(traderId);
    if (account == null) {
      return reject(orderId, RejectReason.MARGIN);
    }
    long exposure = quantity * checkTick; // both bounded by 1e9: cannot overflow
    long allowed = buy ? Math.min(account.cash(), account.marginLimit()) : account.marginLimit();
    if (exposure > allowed) {
      return reject(orderId, RejectReason.MARGIN);
    }

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
        Trade t = buy
            ? new Trade(nextTradeId++, now, pairId, best.price, q, orderId, maker.orderId,
                traderId, maker.traderId, side)
            : new Trade(nextTradeId++, now, pairId, best.price, q, maker.orderId, orderId,
                maker.traderId, traderId, side);
        Account makerAccount = vault.account(maker.traderId);
        if (makerAccount == null) {
          throw new IllegalStateException("resting order " + maker.orderId
              + " has no account " + maker.traderId);
        }
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
      // liquidity existed at the margin check, so at least one fill happened
      return new OrderResult(orderId, OrderStatus.PARTIAL, null, filled, 0, trades);
    }
    Order resting = new Order(orderId, pairId, side, traderId, priceTick, remaining, now);
    book.ladder(side).getOrCreate(priceTick).append(resting);
    index.put(resting);
    return new OrderResult(orderId, filled > 0 ? OrderStatus.PARTIAL : OrderStatus.QUEUED,
        null, filled, remaining, trades);
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
}
