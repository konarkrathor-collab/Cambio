package ads.engine;

/**
 * One pair's book: a bid ladder (best = highest) and an ask ladder (best = lowest). The
 * book holds its ladders only through the {@link PriceLadder} interface (doc 08 §3a).
 * Resting orders are indexed engine-wide, not here.
 */
public final class OrderBook {
  final int pairId;
  final PriceLadder bids;
  final PriceLadder asks;

  OrderBook(int pairId, PriceLadder bids, PriceLadder asks) {
    this.pairId = pairId;
    this.bids = bids;
    this.asks = asks;
  }

  public int pairId() {
    return pairId;
  }

  /** The ladder that orders of this side rest on. */
  public PriceLadder ladder(Side side) {
    return side == Side.BUY ? bids : asks;
  }

  /** Highest bid level, or null. */
  public PriceLevel bestBid() {
    return bids.highest();
  }

  /** Lowest ask level, or null. */
  public PriceLevel bestAsk() {
    return asks.lowest();
  }

  /**
   * Up to {@code k} levels of one side into {@code out}, best first (bids descending, asks
   * ascending); returns how many.
   */
  public int depth(Side side, int k, PriceLevel[] out) {
    return side == Side.BUY ? bids.walkDown(Long.MAX_VALUE, k, out)
        : asks.walkUp(Long.MIN_VALUE, k, out);
  }
}
