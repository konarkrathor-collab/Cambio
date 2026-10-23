package ads.sim;

import ads.directory.Directory;
import ads.engine.MatchingEngine;
import ads.engine.OrderResult;
import ads.engine.OrderType;
import ads.engine.Side;
import ads.util.SplitMix64;
import ads.vault.Vault;

/**
 * The simulator's seeded order generator (doc 08 Stage 4). One {@link SplitMix64} seeded
 * with the request's {@code seed} draws every choice, so a seed always gives the same
 * stream. Each operation is, independently:
 *
 * <ul>
 *   <li><b>55% LIMIT</b>: one of the {@link #PAIRS six pairs}, BUY or SELL with equal odds,
 *       at {@code mid + k} ticks with {@code k} uniform in {@code [-10, +10]} and quantity
 *       uniform in {@code [1, 100]}. The mid never moves, so every book lives inside a
 *       21-tick band, and a buy above the mid meets the sells below it: orders cross
 *       constantly.</li>
 *   <li><b>15% MARKET</b>: same pairs and sides, quantity uniform in {@code [1, 50]}. On an
 *       empty side it is rejected NO_LIQUIDITY.</li>
 *   <li><b>20% CANCEL</b> of a live order: an order id drawn uniformly from the orders
 *       still resting at that moment. If none rests, the cancel names id 0 and misses.</li>
 *   <li><b>10% invalid</b>, one of five kinds with equal odds, so every reject path runs:
 *       an unknown pair (UNKNOWN_PAIR), quantity 0 or {@code MAX_QUANTITY + 1}
 *       (BAD_QUANTITY), a LIMIT at price 0 (BAD_PRICE), or trader 99, who has no account
 *       (MARGIN).</li>
 * </ul>
 *
 * <p><b>Profile {@code deep}</b> (Stage 4 addendum; the default above is {@code shallow}).
 * Built so books grow deep, the case the structures exist for. Each operation is:
 *
 * <ul>
 *   <li><b>72% passive LIMIT</b>: a BUY at {@code mid - k} or a SELL at {@code mid + k}, with
 *       equal odds and {@code k} uniform in {@code [1, 5000]}, quantity uniform in
 *       {@code [1, 100]}. A passive order rests unless the book has been swept past the mid.</li>
 *   <li><b>10% aggressive LIMIT</b>: a BUY at {@code mid + k} or a SELL at {@code mid - k},
 *       {@code k} uniform in {@code [1, 500]}, quantity uniform in {@code [1, 100]}, so it
 *       crosses the near side of the opposite book.</li>
 *   <li><b>3% MARKET</b>, quantity uniform in {@code [1, 50]}.</li>
 *   <li><b>10% CANCEL</b> of a live order, drawn as in the shallow profile.</li>
 *   <li><b>5% invalid</b>, the same five kinds as the shallow profile.</li>
 * </ul>
 *
 * <p>About 70% of operations add a resting order and about 10% cancel one, so each pair holds
 * thousands of resting orders over a long run. Both profiles use the same pairs, mids,
 * traders and accounts.
 *
 * <p>Traders 1 to 12 place the orders, each with cash and margin limit of
 * {@link #TRADER_CASH}, so a valid order never fails the margin check.
 *
 * <p>To know which orders are live when it draws a cancel, the generator drives its own
 * scratch engine over the stream as it writes it. That pass is not timed; the engine and the
 * baseline replay the finished stream from empty books, deterministically, so every cancel
 * that named a live order here names a live order there.
 */
public final class OrderGenerator {
  /** The simulated pairs and their fixed mid prices in ticks (EUR/USD 1.08500 = 108500). */
  public static final String[] PAIRS =
      {"EUR/USD", "GBP/USD", "USD/JPY", "EUR/GBP", "AUD/USD", "USD/CHF"};
  static final long[] MID_TICKS = {108_500, 130_000, 156_870, 83_400, 66_000, 88_000};
  public static final int TRADERS = 12;
  public static final long TRADER_CASH = 1_000_000_000_000_000L;
  /** A trader with no account: every order of theirs is rejected MARGIN. */
  static final int NO_ACCOUNT_TRADER = 99;
  static final int BAND_TICKS = 10;

  private OrderGenerator() {
  }

  static final int DEEP_BAND_TICKS = 5_000;
  static final int DEEP_AGGRESSIVE_TICKS = 500;
  /** deepRoll's answer when it has written a submission itself. */
  private static final int DEEP_FILLED = -1;
  /** A roll the shallow code reads as a cancel. */
  private static final int CANCEL_ROLL = 70;
  /** A roll the shallow code reads as an invalid order. */
  private static final int INVALID_ROLL = 90;

  /**
   * The deep profile's draw for operation {@code i}: a cancel or an invalid order is handed
   * back to the shared code as the shallow roll for that kind; any other submission is
   * written here and reported as {@link #DEEP_FILLED}.
   */
  private static int deepRoll(int roll, SplitMix64 rng, OrderStream s, int i, int[] pairIds) {
    if (roll >= 85 && roll < 95) return CANCEL_ROLL;
    if (roll >= 95) return INVALID_ROLL;
    int p = rng.nextInt(PAIRS.length);
    s.op[i] = OrderStream.SUBMIT;
    s.trader[i] = 1 + rng.nextInt(TRADERS);
    s.pair[i] = pairIds[p];
    boolean buy = rng.nextInt(2) == 0;
    s.buy[i] = buy;
    if (roll < 72) {
      long k = 1 + rng.nextInt(DEEP_BAND_TICKS);
      s.price[i] = MID_TICKS[p] + (buy ? -k : k);
      s.quantity[i] = 1 + rng.nextInt(100);
    } else if (roll < 82) {
      long k = 1 + rng.nextInt(DEEP_AGGRESSIVE_TICKS);
      s.price[i] = MID_TICKS[p] + (buy ? k : -k);
      s.quantity[i] = 1 + rng.nextInt(100);
    } else {
      s.market[i] = true;
      s.quantity[i] = 1 + rng.nextInt(50);
    }
    return DEEP_FILLED;
  }

  /** Opens the twelve simulation accounts in {@code vault}. */
  public static void openAccounts(Vault vault) {
    for (long t = 1; t <= TRADERS; t++) vault.open(t, TRADER_CASH, TRADER_CASH);
  }

  /** The stream of {@code n} operations for {@code seed}, in the shallow profile. */
  public static OrderStream generate(long seed, int n) {
    return generate(seed, n, false);
  }

  /** The stream of {@code n} operations for {@code seed}; {@code deep} picks the profile. */
  public static OrderStream generate(long seed, int n, boolean deep) {
    Directory directory = new Directory();
    int[] pairIds = new int[PAIRS.length];
    for (int i = 0; i < PAIRS.length; i++) pairIds[i] = directory.pairId(PAIRS[i]);
    Vault vault = Vault.inMemory();
    openAccounts(vault);
    MatchingEngine scratch = new MatchingEngine(directory, vault);

    SplitMix64 rng = new SplitMix64(seed);
    OrderStream s = new OrderStream(n);
    long[] live = new long[1024]; // ids that rested; filled ones are dropped when drawn
    int liveCount = 0;
    for (int i = 0; i < n; i++) {
      int roll = rng.nextInt(100);
      if (deep) roll = deepRoll(roll, rng, s, i, pairIds);
      if (roll >= 70 && roll < 90) {
        long id = 0;
        while (liveCount > 0) {
          int k = rng.nextInt(liveCount);
          long candidate = live[k];
          live[k] = live[--liveCount];
          if (scratch.order(candidate) != null) {
            id = candidate;
            break;
          }
        }
        s.op[i] = OrderStream.CANCEL;
        s.target[i] = id;
        scratch.cancel(id);
        continue;
      }
      if (roll != DEEP_FILLED) shallowSubmit(roll, rng, s, i, pairIds);
      OrderResult r = scratch.submit(s.trader[i], s.pair[i], s.buy[i] ? Side.BUY : Side.SELL,
          s.market[i] ? OrderType.MARKET : OrderType.LIMIT, s.price[i], s.quantity[i]);
      if (r.restingQuantity() > 0) {
        if (liveCount == live.length) {
          long[] grown = new long[live.length * 2];
          System.arraycopy(live, 0, grown, 0, liveCount);
          live = grown;
        }
        live[liveCount++] = r.orderId();
      }
    }
    return s;
  }

  /** The shallow profile's submission for operation {@code i} (roll in [0, 70) or [90, 100)). */
  private static void shallowSubmit(int roll, SplitMix64 rng, OrderStream s, int i, int[] pairIds) {
    int p = rng.nextInt(PAIRS.length);
    s.op[i] = OrderStream.SUBMIT;
    s.trader[i] = 1 + rng.nextInt(TRADERS);
    s.pair[i] = pairIds[p];
    s.buy[i] = rng.nextInt(2) == 0;
    if (roll < 55) {
      s.price[i] = MID_TICKS[p] + rng.nextInt(2 * BAND_TICKS + 1) - BAND_TICKS;
      s.quantity[i] = 1 + rng.nextInt(100);
    } else if (roll < 70) {
      s.market[i] = true;
      s.quantity[i] = 1 + rng.nextInt(50);
    } else {
      s.price[i] = MID_TICKS[p];
      s.quantity[i] = 10;
      switch (rng.nextInt(5)) {
        case 0: s.pair[i] = -1; break;
        case 1: s.quantity[i] = 0; break;
        case 2: s.quantity[i] = MatchingEngine.MAX_QUANTITY + 1; break;
        case 3: s.price[i] = 0; break;
        default: s.trader[i] = NO_ACCOUNT_TRADER; break;
      }
    }
  }
}
