package ads.engine;

/**
 * Appendix A outcome of a submitted order: {@code FILLED} (fully matched), {@code PARTIAL}
 * (some matched; the remainder rests, or for a market order was cancelled), {@code QUEUED}
 * (nothing matched, now resting), {@code REJECTED} (with a {@link RejectReason}).
 */
public enum OrderStatus {
  FILLED,
  PARTIAL,
  QUEUED,
  REJECTED
}
