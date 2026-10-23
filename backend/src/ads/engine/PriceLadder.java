package ads.engine;

/**
 * One side of an order book: the set of active {@link PriceLevel}s ordered by price. The
 * matching engine depends on the ladder ONLY through this interface (doc 08 §3a), so the
 * B+ tree can be swapped for another ordered structure without touching the matching loop.
 *
 * <p>Bids and asks each get their own ladder: the best ask is {@link #lowest()}, the best
 * bid is {@link #highest()}. Prices are integer ticks.
 */
public interface PriceLadder {
  /** The level at {@code price}, or null if that price is not active. */
  PriceLevel get(long price);

  /** The level at {@code price}, inserting a new empty level if it is not active yet. */
  PriceLevel getOrCreate(long price);

  /** Removes and returns the level at {@code price}, or null if it was not active. */
  PriceLevel remove(long price);

  /** The lowest-priced level (best ask on an ask ladder), or null if empty. */
  PriceLevel lowest();

  /** The highest-priced level (best bid on a bid ladder), or null if empty. */
  PriceLevel highest();

  /**
   * Copies up to {@code k} levels with price {@code >= fromPrice} into {@code out}, lowest
   * first, and returns how many were copied (never more than {@code out.length}).
   */
  int walkUp(long fromPrice, int k, PriceLevel[] out);

  /**
   * Copies up to {@code k} levels with price {@code <= fromPrice} into {@code out},
   * highest first, and returns how many were copied (never more than {@code out.length}).
   */
  int walkDown(long fromPrice, int k, PriceLevel[] out);

  /** Number of active levels. */
  int size();
}
