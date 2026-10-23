package ads.engine;

/** Appendix A rejection reasons, checked in this order. */
public enum RejectReason {
  /** The pair is not in the Directory. */
  UNKNOWN_PAIR,
  /** Quantity is not in {@code 1..MatchingEngine.MAX_QUANTITY}. */
  BAD_QUANTITY,
  /** A limit price is not in {@code 1..MatchingEngine.MAX_PRICE_TICK}. */
  BAD_PRICE,
  /** A market order found no opposite liquidity to price its margin check against. */
  NO_LIQUIDITY,
  /** The trader has no account, or the order's exposure exceeds what the account allows. */
  MARGIN
}
