package ads.engine;

/**
 * Appendix A order types. A {@code LIMIT} remainder rests until filled or cancelled; a
 * {@code MARKET} order takes liquidity at any price and its unfilled remainder is
 * cancelled, never rested.
 */
public enum OrderType {
  LIMIT,
  MARKET
}
