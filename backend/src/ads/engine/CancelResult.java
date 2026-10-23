package ads.engine;

/** What {@link MatchingEngine#cancel} returns. */
public final class CancelResult {
  final long orderId;
  final CancelStatus status;
  final long remainingQuantity;

  CancelResult(long orderId, CancelStatus status, long remainingQuantity) {
    this.orderId = orderId;
    this.status = status;
    this.remainingQuantity = remainingQuantity;
  }

  public long orderId() {
    return orderId;
  }

  public CancelStatus status() {
    return status;
  }

  /** The cancelled order's unfilled quantity (0 when NOT_FOUND). */
  public long remainingQuantity() {
    return remainingQuantity;
  }
}
