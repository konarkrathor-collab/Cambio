package ads.engine;

/**
 * One fill. Executes at the RESTING (maker) order's price (Appendix A); {@code notional =
 * priceTick x quantity} is the pair-agnostic simulation unit the accounts settle in.
 */
public final class Trade {
  final long tradeId;
  final long seq;
  final int pairId;
  final long priceTick;
  final long quantity;
  final long buyOrderId;
  final long sellOrderId;
  final long buyerId;
  final long sellerId;
  final Side takerSide;

  Trade(long tradeId, long seq, int pairId, long priceTick, long quantity, long buyOrderId,
      long sellOrderId, long buyerId, long sellerId, Side takerSide) {
    this.tradeId = tradeId;
    this.seq = seq;
    this.pairId = pairId;
    this.priceTick = priceTick;
    this.quantity = quantity;
    this.buyOrderId = buyOrderId;
    this.sellOrderId = sellOrderId;
    this.buyerId = buyerId;
    this.sellerId = sellerId;
    this.takerSide = takerSide;
  }

  public long tradeId() {
    return tradeId;
  }

  /** Engine sequence number of the order whose arrival caused this trade. */
  public long seq() {
    return seq;
  }

  public int pairId() {
    return pairId;
  }

  public long priceTick() {
    return priceTick;
  }

  public long quantity() {
    return quantity;
  }

  public long notional() {
    return priceTick * quantity;
  }

  public long buyOrderId() {
    return buyOrderId;
  }

  public long sellOrderId() {
    return sellOrderId;
  }

  public long buyerId() {
    return buyerId;
  }

  public long sellerId() {
    return sellerId;
  }

  /** The side of the incoming (aggressing) order. */
  public Side takerSide() {
    return takerSide;
  }
}
