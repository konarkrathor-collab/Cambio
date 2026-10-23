package ads.engine;

/** What {@link MatchingEngine#submit} returns for one order. */
public final class OrderResult {
  private static final Trade[] NO_TRADES = new Trade[0];

  final long orderId;
  final OrderStatus status;
  final RejectReason rejectReason;
  final long filledQuantity;
  final long restingQuantity;
  final Trade[] trades;

  OrderResult(long orderId, OrderStatus status, RejectReason rejectReason, long filledQuantity,
      long restingQuantity, Trade[] trades) {
    this.orderId = orderId;
    this.status = status;
    this.rejectReason = rejectReason;
    this.filledQuantity = filledQuantity;
    this.restingQuantity = restingQuantity;
    this.trades = trades == null ? NO_TRADES : trades;
  }

  public long orderId() {
    return orderId;
  }

  public OrderStatus status() {
    return status;
  }

  /** The reason when {@link #status()} is REJECTED, else null. */
  public RejectReason rejectReason() {
    return rejectReason;
  }

  public long filledQuantity() {
    return filledQuantity;
  }

  /** Quantity left resting on the book (0 unless a LIMIT order was QUEUED or PARTIAL). */
  public long restingQuantity() {
    return restingQuantity;
  }

  public int tradeCount() {
    return trades.length;
  }

  public Trade trade(int i) {
    return trades[i];
  }
}
