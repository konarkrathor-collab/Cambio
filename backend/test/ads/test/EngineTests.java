package ads.test;

import ads.bench.core.Rng;
import ads.directory.Directory;
import ads.engine.CancelResult;
import ads.engine.CancelStatus;
import ads.engine.MatchingEngine;
import ads.engine.Order;
import ads.engine.OrderBook;
import ads.engine.OrderResult;
import ads.engine.OrderStatus;
import ads.engine.OrderType;
import ads.engine.PriceLadder;
import ads.engine.PriceLevel;
import ads.engine.RejectReason;
import ads.engine.Side;
import ads.engine.Trade;
import ads.logbook.Logbook;
import ads.vault.Vault;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 1 engine tests (doc 08): the differential test against {@link ReferenceMatcher},
 * the book invariants after every order, and hand-written Appendix A scenarios.
 */
final class EngineTests {
  private EngineTests() {
  }

  private static final String[] PAIRS = { "EUR/USD", "USD/JPY", "GBP/USD" };
  private static final long[] MID = { 1_000, 1_500, 1_200 };
  private static final int BAND = 5;     // narrow: orders cross constantly
  private static final int TRADERS = 40;

  static void run(TestRunner r) {
    r.section("engine: differential test vs reference matcher (TreeMap + ArrayDeque)");
    for (long seed : new long[] { 1, 2, 3 }) {
      r.check("150000 limit/market/cancel/invalid orders, seed=" + seed
          + ": every result and trade identical, final depth + cash identical",
          () -> differential(seed, 150_000, false));
    }
    for (long seed : new long[] { 11, 12, 13 }) {
      r.check("20000 orders, seed=" + seed + ", invariants checked after EVERY order",
          () -> differential(seed, 20_000, true));
    }

    r.section("engine: Appendix A scenarios");
    r.check("partial fill: resting order keeps the rest, level volume follows",
        EngineTests::partialFill);
    r.check("FIFO within a level", EngineTests::fifo);
    r.check("trade executes at the resting price (price improvement for the taker)",
        EngineTests::restingPrice);
    r.check("market order sweeps several levels; emptied levels leave the ladder",
        EngineTests::marketSweep);
    r.check("market remainder is cancelled, never rests (PARTIAL)", EngineTests::marketRemainder);
    r.check("limit order does not trade through its limit; QUEUED then PARTIAL",
        EngineTests::limitRespectsPrice);
    r.check("cancel of a resting order returns its remaining qty and prunes the level",
        EngineTests::cancelResting);
    r.check("cancel of an unknown, filled or already-cancelled id is NOT_FOUND",
        EngineTests::cancelNotFound);
    r.check("rejection: UNKNOWN_PAIR (unknown, lowercase, null, bad id)", EngineTests::unknownPair);
    r.check("rejection: BAD_QUANTITY (0, negative, above max)", EngineTests::badQuantity);
    r.check("rejection: BAD_PRICE (limit 0, negative, above max; market ignores price)",
        EngineTests::badPrice);
    r.check("rejection: NO_LIQUIDITY (market order on an empty opposite side)",
        EngineTests::noLiquidity);
    r.check("reject precedence: every pair of checks failing together, 3- and 4-way failures,"
        + " and a fix-one-at-a-time ladder", EngineTests::rejectPrecedence);
    r.check("rejection: MARGIN (buy over cash, buy over margin, sell over margin, no account,"
        + " market checked at best opposite)", EngineTests::margin);
    r.check("settlement moves notional buyer -> seller; self-trade allowed",
        EngineTests::settlementAndSelfTrade);
    r.check("order ids are consumed by rejects; seq advances on submit and cancel",
        EngineTests::idsAndSeq);
    r.check("Appendix A reserves no cash: two resting buys that each pass can drive cash"
        + " negative (as specified)", EngineTests::noReservation);
  }

  // ---------------------------------------------------------------- differential

  private static TestRunner.Result differential(long seed, int n, boolean invariants) {
    Rng rng = new Rng(seed);
    Directory dir = new Directory();
    Vault vault = Vault.inMemory();
    MatchingEngine eng = new MatchingEngine(dir, vault);
    ReferenceMatcher ref = new ReferenceMatcher();
    for (long t = 1; t <= TRADERS; t++) {
      long cash = 20_000 + rng.nextInt(2_000_000);
      long margin = 50_000 + rng.nextInt(1_000_000);
      vault.open(t, cash, margin);
      ref.open(t, cash, margin);
    }
    List<Long> rested = new ArrayList<>();
    long lastId = 0;
    int trades = 0;
    int rejects = 0;
    int cancels = 0;
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      int roll = rng.nextInt(1_000);
      if (roll < 150) {
        int how = rng.nextInt(10);
        long id = how < 5 && !rested.isEmpty() ? rested.get(rng.nextInt(rested.size()))
            : how < 9 ? 1 + rng.nextInt((int) Math.max(1, lastId)) : lastId + 1_000;
        CancelResult c = eng.cancel(id);
        long want = ref.cancel(id);
        long got = c.status() == CancelStatus.CANCELLED ? c.remainingQuantity() : -1;
        if (got != want) {
          return TestRunner.Result.fail("op " + i + ": cancel " + id + " engine=" + got
              + " reference=" + want);
        }
        if (got >= 0) cancels++;
      } else {
        long trader = 1 + rng.nextInt(TRADERS);
        boolean buy = rng.nextInt(2) == 0;
        int p = rng.nextInt(PAIRS.length);
        String pair = PAIRS[p];
        boolean market = roll < 320;
        long price = MID[p] + rng.nextInt(2 * BAND + 1) - BAND;
        long qty = 1 + rng.nextInt(100);
        if (roll < 170) { // malformed or doomed input
          switch (rng.nextInt(10)) {
            case 0: qty = 0; break;
            case 1: qty = -3; break;
            case 2: qty = MatchingEngine.MAX_QUANTITY + 1; break;
            case 3: price = 0; market = false; break;
            case 4: price = -1; market = false; break;
            case 5: price = MatchingEngine.MAX_PRICE_TICK + 1; market = false; break;
            case 6: pair = "EUR/XXX"; break;
            case 7: pair = "eur/usd"; break;
            case 8: trader = 999; break; // no account
            default: qty = 1_000_000; break; // exposure far above any margin
          }
        }
        OrderType type = market ? OrderType.MARKET : OrderType.LIMIT;
        OrderResult got = eng.submit(trader, pair, buy ? Side.BUY : Side.SELL, type, price, qty);
        ReferenceMatcher.Result want = ref.submit(trader, pair, buy, market, price, qty);
        String problem = compare(i, got, want, dir, sb);
        if (problem != null) return TestRunner.Result.fail(problem);
        lastId = got.orderId();
        if (got.restingQuantity() > 0) rested.add(got.orderId());
        if (got.status() == OrderStatus.REJECTED) rejects++;
        trades += got.tradeCount();
      }
      if (invariants) {
        String problem = invariants(eng, dir);
        if (problem != null) return TestRunner.Result.fail("after op " + i + ": " + problem);
      }
    }
    // final book depth, resting count and every account's cash
    for (int p = 0; p < PAIRS.length; p++) {
      for (boolean bid : new boolean[] { true, false }) {
        List<String> mine = depth(eng.book(dir.pairId(PAIRS[p])), bid ? Side.BUY : Side.SELL);
        List<String> theirs = ref.depth(PAIRS[p], bid);
        if (!mine.equals(theirs)) {
          return TestRunner.Result.fail("final " + PAIRS[p] + (bid ? " bids" : " asks")
              + " differ:\n  engine    " + mine + "\n  reference " + theirs);
        }
      }
    }
    if (eng.restingOrders() != ref.restingOrders()) {
      return TestRunner.Result.fail("resting orders " + eng.restingOrders() + " vs "
          + ref.restingOrders());
    }
    for (long t = 1; t <= TRADERS; t++) {
      if (vault.account(t).cash() != ref.cash(t)) {
        return TestRunner.Result.fail("trader " + t + " cash " + vault.account(t).cash()
            + " vs " + ref.cash(t));
      }
    }
    String last = invariants(eng, dir);
    if (last != null) return TestRunner.Result.fail("final invariants: " + last);
    return TestRunner.Result.pass(trades + " trades, " + rejects + " rejects, " + cancels
        + " cancels, " + eng.restingOrders() + " resting at the end");
  }

  private static String compare(int i, OrderResult got, ReferenceMatcher.Result want,
      Directory dir, StringBuilder sb) {
    String gotReason = got.rejectReason() == null ? null : got.rejectReason().name();
    if (got.orderId() != want.orderId || !got.status().name().equals(want.status)
        || !java.util.Objects.equals(gotReason, want.reason)
        || got.filledQuantity() != want.filled || got.restingQuantity() != want.resting
        || got.tradeCount() != want.trades.size()) {
      return "op " + i + ": engine id=" + got.orderId() + " " + got.status() + "/" + gotReason
          + " filled=" + got.filledQuantity() + " resting=" + got.restingQuantity()
          + " trades=" + got.tradeCount() + " | reference id=" + want.orderId + " "
          + want.status + "/" + want.reason + " filled=" + want.filled + " resting="
          + want.resting + " trades=" + want.trades.size();
    }
    for (int k = 0; k < got.tradeCount(); k++) {
      sb.setLength(0);
      Logbook.appendRecord(sb, got.trade(k), dir);
      sb.setLength(sb.length() - 1); // drop the newline
      if (!sb.toString().equals(want.trades.get(k))) {
        return "op " + i + " trade " + k + ": engine " + sb + " | reference "
            + want.trades.get(k);
      }
    }
    return null;
  }

  /** One side rendered as ReferenceMatcher.depth renders it. */
  static List<String> depth(OrderBook book, Side side) {
    PriceLevel[] levels = new PriceLevel[book.ladder(side).size()];
    int n = book.depth(side, levels.length, levels);
    List<String> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      StringBuilder ids = new StringBuilder();
      for (Order o = levels[i].head(); o != null; o = o.next()) {
        ids.append(ids.length() == 0 ? "" : " ").append(o.orderId()).append('/')
            .append(o.remaining());
      }
      out.add(levels[i].price() + ":" + levels[i].totalVolume() + "[" + ids + "]");
    }
    return out;
  }

  /**
   * The invariants the supervisor fixed: every level's totalVolume is the sum of its
   * orders' remaining; the index count equals the number of resting orders; no empty level
   * remains in a ladder; best bid is below best ask. Plus structural consistency of each
   * resting order (level pointer, pair, side, price, indexed, positive remaining).
   */
  static String invariants(MatchingEngine eng, Directory dir) {
    long resting = 0;
    for (int p = 0; p < eng.pairCount(); p++) {
      OrderBook book = eng.book(p);
      for (Side side : Side.values()) {
        PriceLadder ladder = book.ladder(side);
        PriceLevel[] levels = new PriceLevel[ladder.size()];
        int n = ladder.walkUp(Long.MIN_VALUE, levels.length, levels);
        if (n != ladder.size()) return dir.pair(p) + " " + side + ": walk saw " + n + " of "
            + ladder.size() + " levels";
        for (int i = 0; i < n; i++) {
          PriceLevel l = levels[i];
          if (l.isEmpty() || l.orderCount() == 0) {
            return dir.pair(p) + " " + side + ": empty level " + l.price() + " left in ladder";
          }
          long sum = 0;
          int count = 0;
          for (Order o = l.head(); o != null; o = o.next()) {
            if (o.level() != l || o.pairId() != p || o.side() != side
                || o.priceTick() != l.price() || o.remaining() <= 0
                || eng.order(o.orderId()) != o) {
              return "order " + o.orderId() + " inconsistent at " + dir.pair(p) + " " + side
                  + " " + l.price();
            }
            sum += o.remaining();
            count++;
          }
          if (sum != l.totalVolume() || count != l.orderCount()) {
            return dir.pair(p) + " " + side + " level " + l.price() + ": totalVolume "
                + l.totalVolume() + " vs sum of remaining " + sum + ", orderCount "
                + l.orderCount() + " vs " + count;
          }
          resting += count;
        }
      }
      PriceLevel bid = book.bestBid();
      PriceLevel ask = book.bestAsk();
      if (bid != null && ask != null && bid.price() >= ask.price()) {
        return dir.pair(p) + " crossed: best bid " + bid.price() + " >= best ask " + ask.price();
      }
    }
    if (resting != eng.restingOrders()) {
      return "index holds " + eng.restingOrders() + " orders, books hold " + resting;
    }
    return null;
  }

  // ---------------------------------------------------------------- scenarios

  private static final String EURUSD = "EUR/USD";

  /** A fresh engine with traders 1..4 holding plenty of cash and margin. */
  private static final class Fixture {
    final Directory dir = new Directory();
    final Vault vault = Vault.inMemory();
    final MatchingEngine eng = new MatchingEngine(dir, vault);
    final int pair = dir.pairId(EURUSD);

    Fixture() {
      for (long t = 1; t <= 4; t++) vault.open(t, 10_000_000, 10_000_000);
    }

    OrderResult limit(long trader, Side side, long price, long qty) {
      return eng.submit(trader, EURUSD, side, OrderType.LIMIT, price, qty);
    }

    OrderResult market(long trader, Side side, long qty) {
      return eng.submit(trader, EURUSD, side, OrderType.MARKET, 0, qty);
    }

    OrderBook book() {
      return eng.book(pair);
    }

    String invariants() {
      return EngineTests.invariants(eng, dir);
    }
  }

  private static String expect(boolean ok, String what) {
    return ok ? null : what;
  }

  private static TestRunner.Result all(String... problems) {
    for (String p : problems) {
      if (p != null) return TestRunner.Result.fail(p);
    }
    return TestRunner.Result.pass(null);
  }

  private static String trade(OrderResult r, int k, long price, long qty, long buyId, long sellId) {
    if (k >= r.tradeCount()) return "missing trade " + k;
    Trade t = r.trade(k);
    return t.priceTick() == price && t.quantity() == qty && t.buyOrderId() == buyId
        && t.sellOrderId() == sellId ? null
        : "trade " + k + " was " + t.quantity() + "@" + t.priceTick() + " buy#" + t.buyOrderId()
            + " sell#" + t.sellOrderId() + ", want " + qty + "@" + price + " buy#" + buyId
            + " sell#" + sellId;
  }

  private static TestRunner.Result partialFill() {
    Fixture f = new Fixture();
    OrderResult sell = f.limit(1, Side.SELL, 100, 10);
    OrderResult buy = f.limit(2, Side.BUY, 100, 4);
    Order resting = f.eng.order(sell.orderId());
    return all(
        expect(sell.status() == OrderStatus.QUEUED, "sell not QUEUED"),
        expect(buy.status() == OrderStatus.FILLED && buy.filledQuantity() == 4, "buy not FILLED 4"),
        trade(buy, 0, 100, 4, buy.orderId(), sell.orderId()),
        expect(resting != null && resting.remaining() == 6, "resting sell should keep 6"),
        expect(f.book().bestAsk().totalVolume() == 6, "level volume should be 6"),
        f.invariants());
  }

  private static TestRunner.Result fifo() {
    Fixture f = new Fixture();
    OrderResult a = f.limit(1, Side.SELL, 100, 5);
    OrderResult b = f.limit(2, Side.SELL, 100, 5);
    OrderResult c = f.limit(3, Side.SELL, 100, 5);
    OrderResult buy = f.limit(4, Side.BUY, 100, 7);
    return all(
        expect(buy.tradeCount() == 2, "expected 2 trades, got " + buy.tradeCount()),
        trade(buy, 0, 100, 5, buy.orderId(), a.orderId()),
        trade(buy, 1, 100, 2, buy.orderId(), b.orderId()),
        expect(f.eng.order(a.orderId()) == null, "first order should be gone"),
        expect(f.eng.order(b.orderId()).remaining() == 3, "second keeps 3"),
        expect(f.book().bestAsk().head().orderId() == b.orderId(), "second is now the head"),
        expect(f.eng.order(c.orderId()).remaining() == 5, "third untouched"),
        f.invariants());
  }

  private static TestRunner.Result restingPrice() {
    Fixture f = new Fixture();
    OrderResult sell = f.limit(1, Side.SELL, 100, 5);
    long buyerBefore = f.vault.account(2).cash();
    OrderResult buy = f.limit(2, Side.BUY, 105, 5);
    OrderResult bid = f.limit(3, Side.BUY, 90, 5);
    OrderResult sell2 = f.limit(4, Side.SELL, 80, 2);
    return all(
        trade(buy, 0, 100, 5, buy.orderId(), sell.orderId()),
        expect(f.vault.account(2).cash() == buyerBefore - 500, "buyer should pay 5 x 100"),
        trade(sell2, 0, 90, 2, bid.orderId(), sell2.orderId()),
        f.invariants());
  }

  private static TestRunner.Result marketSweep() {
    Fixture f = new Fixture();
    OrderResult a = f.limit(1, Side.SELL, 100, 3);
    OrderResult b = f.limit(1, Side.SELL, 101, 4);
    OrderResult c = f.limit(1, Side.SELL, 102, 5);
    OrderResult m = f.market(2, Side.BUY, 10);
    PriceLadder asks = f.book().ladder(Side.SELL);
    return all(
        expect(m.status() == OrderStatus.FILLED && m.tradeCount() == 3, "sweep not FILLED in 3"),
        trade(m, 0, 100, 3, m.orderId(), a.orderId()),
        trade(m, 1, 101, 4, m.orderId(), b.orderId()),
        trade(m, 2, 102, 3, m.orderId(), c.orderId()),
        expect(asks.size() == 1 && asks.get(100) == null && asks.get(101) == null,
            "emptied levels should be removed"),
        expect(asks.get(102).totalVolume() == 2, "102 keeps 2"),
        f.invariants());
  }

  private static TestRunner.Result marketRemainder() {
    Fixture f = new Fixture();
    f.limit(1, Side.BUY, 100, 3);
    f.limit(1, Side.BUY, 99, 4);
    OrderResult m = f.market(2, Side.SELL, 20);
    return all(
        expect(m.status() == OrderStatus.PARTIAL, "should be PARTIAL, was " + m.status()),
        expect(m.filledQuantity() == 7 && m.restingQuantity() == 0, "filled 7, rests 0"),
        expect(f.book().ladder(Side.SELL).size() == 0, "market remainder must not rest"),
        expect(f.book().ladder(Side.BUY).size() == 0, "bids consumed"),
        expect(f.eng.restingOrders() == 0, "nothing resting"),
        f.invariants());
  }

  private static TestRunner.Result limitRespectsPrice() {
    Fixture f = new Fixture();
    f.limit(1, Side.SELL, 101, 5);
    OrderResult q = f.limit(2, Side.BUY, 100, 5);
    OrderResult p = f.limit(3, Side.BUY, 101, 8);
    return all(
        expect(q.status() == OrderStatus.QUEUED && q.tradeCount() == 0, "buy@100 should rest"),
        expect(p.status() == OrderStatus.PARTIAL && p.filledQuantity() == 5
            && p.restingQuantity() == 3, "buy@101 x8 should fill 5 and rest 3"),
        expect(f.book().bestBid().price() == 101 && f.book().bestBid().totalVolume() == 3,
            "remainder rests at its own limit"),
        expect(f.book().bestAsk() == null, "asks consumed"),
        f.invariants());
  }

  private static TestRunner.Result cancelResting() {
    Fixture f = new Fixture();
    OrderResult a = f.limit(1, Side.BUY, 99, 10);
    OrderResult b = f.limit(1, Side.BUY, 98, 10);
    f.limit(2, Side.SELL, 99, 4); // partially fills a
    CancelResult ca = f.eng.cancel(a.orderId());
    CancelResult cb = f.eng.cancel(b.orderId());
    return all(
        expect(ca.status() == CancelStatus.CANCELLED && ca.remainingQuantity() == 6,
            "cancel after a partial fill returns 6, got " + ca.remainingQuantity()),
        expect(cb.status() == CancelStatus.CANCELLED && cb.remainingQuantity() == 10,
            "cancel returns 10"),
        expect(f.book().ladder(Side.BUY).size() == 0, "emptied levels pruned"),
        expect(f.eng.restingOrders() == 0, "index empty"),
        f.invariants());
  }

  private static TestRunner.Result cancelNotFound() {
    Fixture f = new Fixture();
    OrderResult s = f.limit(1, Side.SELL, 100, 5);
    f.limit(2, Side.BUY, 100, 5); // fills s
    OrderResult r = f.limit(3, Side.BUY, 90, 5);
    CancelResult first = f.eng.cancel(r.orderId());
    CancelResult again = f.eng.cancel(r.orderId());
    CancelResult filled = f.eng.cancel(s.orderId());
    CancelResult unknown = f.eng.cancel(424_242);
    OrderResult rejected = f.limit(1, Side.BUY, 0, 5);
    CancelResult ofReject = f.eng.cancel(rejected.orderId());
    return all(
        expect(first.status() == CancelStatus.CANCELLED, "first cancel works"),
        expect(again.status() == CancelStatus.NOT_FOUND, "second cancel is NOT_FOUND"),
        expect(filled.status() == CancelStatus.NOT_FOUND, "filled order is NOT_FOUND"),
        expect(unknown.status() == CancelStatus.NOT_FOUND, "unknown id is NOT_FOUND"),
        expect(ofReject.status() == CancelStatus.NOT_FOUND, "rejected order is NOT_FOUND"),
        expect(again.remainingQuantity() == 0, "NOT_FOUND carries 0"),
        f.invariants());
  }

  private static String rejected(OrderResult r, RejectReason reason) {
    return r.status() == OrderStatus.REJECTED && r.rejectReason() == reason ? null
        : "expected REJECTED/" + reason + ", got " + r.status() + "/" + r.rejectReason();
  }

  private static TestRunner.Result unknownPair() {
    Fixture f = new Fixture();
    return all(
        rejected(f.eng.submit(1, "EUR/XXX", Side.BUY, OrderType.LIMIT, 100, 1),
            RejectReason.UNKNOWN_PAIR),
        rejected(f.eng.submit(1, "eur/usd", Side.BUY, OrderType.LIMIT, 100, 1),
            RejectReason.UNKNOWN_PAIR),
        rejected(f.eng.submit(1, "EUR/EUR", Side.BUY, OrderType.LIMIT, 100, 1),
            RejectReason.UNKNOWN_PAIR),
        rejected(f.eng.submit(1, (String) null, Side.BUY, OrderType.LIMIT, 100, 1),
            RejectReason.UNKNOWN_PAIR),
        rejected(f.eng.submit(1, 132, Side.BUY, OrderType.LIMIT, 100, 1),
            RejectReason.UNKNOWN_PAIR),
        rejected(f.eng.submit(1, -1, Side.BUY, OrderType.LIMIT, 100, 1),
            RejectReason.UNKNOWN_PAIR));
  }

  private static TestRunner.Result badQuantity() {
    Fixture f = new Fixture();
    return all(
        rejected(f.limit(1, Side.BUY, 100, 0), RejectReason.BAD_QUANTITY),
        rejected(f.limit(1, Side.BUY, 100, -5), RejectReason.BAD_QUANTITY),
        rejected(f.limit(1, Side.BUY, 100, MatchingEngine.MAX_QUANTITY + 1),
            RejectReason.BAD_QUANTITY),
        rejected(f.market(1, Side.BUY, 0), RejectReason.BAD_QUANTITY));
  }

  private static TestRunner.Result badPrice() {
    Fixture f = new Fixture();
    f.limit(2, Side.SELL, 100, 5);
    OrderResult m = f.eng.submit(1, EURUSD, Side.BUY, OrderType.MARKET, -77, 1);
    return all(
        rejected(f.limit(1, Side.BUY, 0, 1), RejectReason.BAD_PRICE),
        rejected(f.limit(1, Side.BUY, -1, 1), RejectReason.BAD_PRICE),
        rejected(f.limit(1, Side.SELL, MatchingEngine.MAX_PRICE_TICK + 1, 1),
            RejectReason.BAD_PRICE),
        expect(m.status() == OrderStatus.FILLED, "a market order's price field is ignored"));
  }

  private static TestRunner.Result noLiquidity() {
    Fixture f = new Fixture();
    f.limit(1, Side.BUY, 100, 5); // bids exist, asks do not
    return all(
        rejected(f.market(2, Side.BUY, 1), RejectReason.NO_LIQUIDITY),
        expect(f.market(2, Side.SELL, 1).status() == OrderStatus.FILLED, "sell side has bids"),
        rejected(f.eng.submit(2, "USD/JPY", Side.SELL, OrderType.MARKET, 0, 1),
            RejectReason.NO_LIQUIDITY));
  }

  private static TestRunner.Result margin() {
    Fixture f = new Fixture();
    f.vault.open(10, 1_000, 5_000); // cash binds for buys
    f.vault.open(11, 5_000, 1_000); // margin binds for buys and sells
    f.limit(1, Side.SELL, 200, 100);
    return all(
        expect(f.limit(10, Side.BUY, 100, 10).status() == OrderStatus.QUEUED,
            "10 x 100 = 1000 <= min(1000, 5000) is allowed"),
        rejected(f.limit(10, Side.BUY, 100, 11), RejectReason.MARGIN),
        rejected(f.limit(10, Side.BUY, 1_001, 1), RejectReason.MARGIN), // exactly cash + 1
        rejected(f.limit(11, Side.SELL, 1_001, 1), RejectReason.MARGIN), // exactly margin + 1
        rejected(f.limit(11, Side.BUY, 100, 11), RejectReason.MARGIN),
        expect(f.limit(11, Side.SELL, 300, 3).status() == OrderStatus.QUEUED,
            "sell 3 x 300 = 900 <= margin 1000 is allowed"),
        rejected(f.limit(11, Side.SELL, 300, 4), RejectReason.MARGIN),
        expect(f.limit(10, Side.SELL, 300, 16).status() == OrderStatus.QUEUED,
            "a sell ignores cash: 16 x 300 = 4800 <= margin 5000"),
        rejected(f.limit(999, Side.BUY, 100, 1), RejectReason.MARGIN),
        rejected(f.market(10, Side.BUY, 6), RejectReason.MARGIN), // 6 x best ask 200 > 1000
        expect(f.market(10, Side.BUY, 5).status() == OrderStatus.FILLED,
            "5 x best ask 200 = 1000 is allowed"),
        f.invariants());
  }

  /**
   * Appendix A precedence: UNKNOWN_PAIR, BAD_QUANTITY, BAD_PRICE (limit only), NO_LIQUIDITY
   * (market only), MARGIN; the first failure wins. For every pair of checks that can fail
   * on the same order there is a case failing EXACTLY those two, so swapping any two lines
   * of MatchingEngine.validate changes at least one outcome. BAD_PRICE and NO_LIQUIDITY
   * can never fail together (one is limit-only, the other market-only), so their relative
   * order is unobservable by construction.
   */
  private static TestRunner.Result rejectPrecedence() {
    Fixture f = new Fixture();
    f.vault.open(30, 10, 10); // margin binds almost everything
    f.limit(1, Side.SELL, 100, 50); // EUR/USD: asks only, so a market SELL has no liquidity
    final long none = 999;       // no account
    final long ok = 1;
    final String bad = "XXX/YYY";
    final long tooBig = MatchingEngine.MAX_QUANTITY + 1;
    Object[][] cases = {
        // pair, side, type, price, qty, trader, expected, what fails
        { bad, Side.BUY, OrderType.LIMIT, 100L, 0L, ok, RejectReason.UNKNOWN_PAIR, "pair+qty" },
        { bad, Side.BUY, OrderType.LIMIT, 0L, 5L, ok, RejectReason.UNKNOWN_PAIR, "pair+price" },
        { bad, Side.SELL, OrderType.MARKET, 0L, 5L, ok, RejectReason.UNKNOWN_PAIR,
            "pair+liquidity" },
        { bad, Side.BUY, OrderType.LIMIT, 100L, 5L, none, RejectReason.UNKNOWN_PAIR,
            "pair+margin" },
        { EURUSD, Side.BUY, OrderType.LIMIT, 0L, 0L, ok, RejectReason.BAD_QUANTITY, "qty+price" },
        { EURUSD, Side.SELL, OrderType.MARKET, 0L, 0L, ok, RejectReason.BAD_QUANTITY,
            "qty+liquidity" },
        { EURUSD, Side.BUY, OrderType.LIMIT, 100L, 0L, none, RejectReason.BAD_QUANTITY,
            "qty+margin (no account)" },
        { EURUSD, Side.BUY, OrderType.LIMIT, 100L, tooBig, 30L, RejectReason.BAD_QUANTITY,
            "qty+margin (exposure)" },
        { EURUSD, Side.BUY, OrderType.LIMIT, 0L, 5L, none, RejectReason.BAD_PRICE,
            "price+margin (no account)" },
        { EURUSD, Side.SELL, OrderType.LIMIT, MatchingEngine.MAX_PRICE_TICK + 1, 5L, 30L,
            RejectReason.BAD_PRICE, "price+margin (exposure)" },
        { EURUSD, Side.SELL, OrderType.MARKET, 0L, 5L, none, RejectReason.NO_LIQUIDITY,
            "liquidity+margin (no account)" },
        { "USD/JPY", Side.BUY, OrderType.MARKET, 0L, 1_000_000L, 30L, RejectReason.NO_LIQUIDITY,
            "liquidity+margin (exposure)" },
        // three and four failures at once
        { bad, Side.BUY, OrderType.LIMIT, 0L, 0L, none, RejectReason.UNKNOWN_PAIR,
            "pair+qty+price+margin" },
        { bad, Side.SELL, OrderType.MARKET, 0L, -3L, none, RejectReason.UNKNOWN_PAIR,
            "pair+qty+liquidity+margin" },
        { EURUSD, Side.BUY, OrderType.LIMIT, -1L, tooBig, none, RejectReason.BAD_QUANTITY,
            "qty+price+margin" },
        { EURUSD, Side.SELL, OrderType.MARKET, 0L, 0L, none, RejectReason.BAD_QUANTITY,
            "qty+liquidity+margin" },
        // one failure each (sanity)
        { EURUSD, Side.BUY, OrderType.LIMIT, 100L, 5L, none, RejectReason.MARGIN, "margin" },
        { EURUSD, Side.BUY, OrderType.MARKET, 0L, 1_000_000L, 30L, RejectReason.MARGIN,
            "margin (market, at best ask)" },
    };
    for (Object[] c : cases) {
      OrderResult r = f.eng.submit((Long) c[5], (String) c[0], (Side) c[1], (OrderType) c[2],
          (Long) c[3], (Long) c[4]);
      if (r.status() != OrderStatus.REJECTED || r.rejectReason() != c[6]) {
        return TestRunner.Result.fail(c[7] + ": expected " + c[6] + ", got " + r.status()
            + "/" + r.rejectReason());
      }
    }
    // fix-one-at-a-time ladder: each fix exposes the next reason in precedence order
    String[] pair = { bad, EURUSD, EURUSD, EURUSD, EURUSD };
    long[] qty = { 0, 0, 5, 5, 5 };
    long[] price = { 0, 0, 0, 100, 100 };
    long[] trader = { none, none, none, none, ok };
    RejectReason[] want = { RejectReason.UNKNOWN_PAIR, RejectReason.BAD_QUANTITY,
        RejectReason.BAD_PRICE, RejectReason.MARGIN, null };
    for (int i = 0; i < want.length; i++) {
      OrderResult r =
          f.eng.submit(trader[i], pair[i], Side.BUY, OrderType.LIMIT, price[i], qty[i]);
      if (r.rejectReason() != want[i]) {
        return TestRunner.Result.fail("ladder step " + i + ": expected " + want[i] + ", got "
            + r.status() + "/" + r.rejectReason());
      }
    }
    return TestRunner.Result.pass(cases.length + " multi-failure cases + 5-step ladder");
  }

  private static TestRunner.Result settlementAndSelfTrade() {
    Fixture f = new Fixture();
    long b0 = f.vault.account(1).cash();
    long s0 = f.vault.account(2).cash();
    f.limit(2, Side.SELL, 150, 4);
    f.limit(1, Side.BUY, 150, 4);
    long b1 = f.vault.account(1).cash();
    long s1 = f.vault.account(2).cash();
    f.limit(3, Side.SELL, 120, 2);
    OrderResult self = f.limit(3, Side.BUY, 120, 2);
    return all(
        expect(b1 == b0 - 600 && s1 == s0 + 600, "buyer -600, seller +600"),
        expect(self.status() == OrderStatus.FILLED && self.tradeCount() == 1
            && self.trade(0).buyerId() == 3 && self.trade(0).sellerId() == 3,
            "self-trade should execute"),
        expect(f.vault.account(3).cash() == 10_000_000, "a self-trade nets to zero"));
  }

  private static TestRunner.Result noReservation() {
    Fixture f = new Fixture();
    f.vault.open(20, 1_000, 1_000_000);
    OrderResult a = f.limit(20, Side.BUY, 100, 10); // 1000 <= min(1000, 1e6)
    OrderResult b = f.limit(20, Side.BUY, 100, 10); // checked against the same, unreserved cash
    f.limit(1, Side.SELL, 100, 20);
    return all(
        expect(a.status() == OrderStatus.QUEUED && b.status() == OrderStatus.QUEUED,
            "both buys pass the per-order check"),
        expect(f.vault.account(20).cash() == -1_000, "cash after both fill: "
            + f.vault.account(20).cash()),
        f.invariants());
  }

  private static TestRunner.Result idsAndSeq() {
    Fixture f = new Fixture();
    OrderResult a = f.limit(1, Side.BUY, 0, 1); // rejected
    OrderResult b = f.limit(1, Side.BUY, 100, 1);
    long seqBefore = f.eng.sequence();
    f.eng.cancel(777);
    OrderResult c = f.limit(1, Side.BUY, 100, 1);
    return all(
        expect(a.orderId() == 1 && b.orderId() == 2 && c.orderId() == 3,
            "ids 1,2,3 (cancel takes no id), got " + a.orderId() + "," + b.orderId() + ","
                + c.orderId()),
        expect(f.eng.sequence() == seqBefore + 2, "seq +1 for the cancel and +1 for the order"),
        expect(f.eng.order(c.orderId()).seq() == f.eng.sequence(), "order records its seq"));
  }
}
