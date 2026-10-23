package ads.api;

import ads.engine.MatchingEngine;

import java.math.BigDecimal;

/**
 * Decimal prices at the API boundary, integer ticks inside the engine (doc 08 Stage 3).
 * Conversion is exact {@code BigDecimal} arithmetic; a price never passes through binary
 * floating point. A pair's tick is {@code 10^-tickDecimals} of its quote currency.
 */
public final class Prices {
  /** Ticks for a price that is zero, negative, or not an exact multiple of the tick. */
  static final long NOT_A_TICK = 0;
  private static final BigDecimal MAX_TICK = BigDecimal.valueOf(MatchingEngine.MAX_PRICE_TICK);

  private Prices() {
  }

  /**
   * The exact tick count for {@code price}, or a value the engine rejects as BAD_PRICE:
   * {@link #NOT_A_TICK} for a zero, negative or non-multiple price, and
   * {@code MAX_PRICE_TICK + 1} for one too large. Rejecting through the engine keeps
   * Appendix A's precedence (an unknown pair or bad quantity is reported first).
   */
  public static long toTicks(BigDecimal price, int tickDecimals) {
    if (price.signum() <= 0) return NOT_A_TICK;
    if (price.compareTo(MAX_TICK) > 0) return MatchingEngine.MAX_PRICE_TICK + 1; // cheap guard
    BigDecimal ticks = price.movePointRight(tickDecimals);
    if (ticks.compareTo(MAX_TICK) > 0) return MatchingEngine.MAX_PRICE_TICK + 1;
    BigDecimal whole = ticks.stripTrailingZeros();
    if (whole.scale() > 0) return NOT_A_TICK; // a fraction of a tick
    return whole.longValueExact();
  }

  /** The decimal price of {@code ticks}, with exactly {@code tickDecimals} places. */
  public static BigDecimal fromTicks(long ticks, int tickDecimals) {
    return BigDecimal.valueOf(ticks, tickDecimals);
  }

  /** The tick size itself, e.g. 0.00001. */
  public static BigDecimal tickSize(int tickDecimals) {
    return BigDecimal.valueOf(1, tickDecimals);
  }

  /**
   * True if {@code price} is small enough to echo back in a response: a client-supplied
   * {@code 1e999999999} is a valid number whose plain form is a billion characters.
   */
  static boolean echoable(BigDecimal price) {
    return price.precision() <= 40 && price.scale() <= 40 && price.scale() >= -20;
  }
}
