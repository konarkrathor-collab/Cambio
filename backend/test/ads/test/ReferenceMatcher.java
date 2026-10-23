package ads.test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reference matcher for the differential test: doc 08 Appendix A implemented the obvious
 * way with java.util ({@code TreeMap} of price -> {@code ArrayDeque} FIFO per side, a
 * {@code HashMap} order index and account table). It shares no code with the engine: pair
 * names, validation limits and rejection order are restated here from the spec, so a
 * disagreement means one of the two is wrong.
 */
final class ReferenceMatcher {
  static final long MAX_QUANTITY = 1_000_000_000L;
  static final long MAX_PRICE_TICK = 1_000_000_000L;
  private static final String[] CCY =
      {"USD", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "INR", "CNY", "HKD", "SGD", "NZD"};

  static final class Ord {
    final long id;
    final String pair;
    final boolean buy;
    final long trader;
    final long price;
    long remaining;

    Ord(long id, String pair, boolean buy, long trader, long price, long remaining) {
      this.id = id;
      this.pair = pair;
      this.buy = buy;
      this.trader = trader;
      this.price = price;
      this.remaining = remaining;
    }
  }

  /** Outcome as plain values: status/reason are Appendix A names. */
  static final class Result {
    long orderId;
    String status;
    String reason; // null unless REJECTED
    long filled;
    long resting;
    final List<String> trades = new ArrayList<>();
  }

  private final Map<String, TreeMap<Long, ArrayDeque<Ord>>> bids = new HashMap<>();
  private final Map<String, TreeMap<Long, ArrayDeque<Ord>>> asks = new HashMap<>();
  private final Map<Long, Ord> live = new HashMap<>();
  private final Map<Long, long[]> accounts = new HashMap<>(); // trader -> {cash, marginLimit}
  private long nextOrderId = 1;
  private long nextTradeId = 1;
  private long seq;

  ReferenceMatcher() {
    for (String a : CCY) {
      for (String b : CCY) {
        if (!a.equals(b)) {
          bids.put(a + "/" + b, new TreeMap<>());
          asks.put(a + "/" + b, new TreeMap<>());
        }
      }
    }
  }

  void open(long trader, long cash, long marginLimit) {
    accounts.put(trader, new long[] { cash, marginLimit });
  }

  long cash(long trader) {
    return accounts.get(trader)[0];
  }

  Result submit(long trader, String pair, boolean buy, boolean market, long price, long qty) {
    Result r = new Result();
    r.orderId = nextOrderId++;
    long now = ++seq;
    if (pair == null || !bids.containsKey(pair)) return reject(r, "UNKNOWN_PAIR");
    if (qty <= 0 || qty > MAX_QUANTITY) return reject(r, "BAD_QUANTITY");
    if (!market && (price <= 0 || price > MAX_PRICE_TICK)) return reject(r, "BAD_PRICE");
    TreeMap<Long, ArrayDeque<Ord>> opposite = buy ? asks.get(pair) : bids.get(pair);
    long checkPrice = price;
    if (market) {
      if (opposite.isEmpty()) return reject(r, "NO_LIQUIDITY");
      checkPrice = buy ? opposite.firstKey() : opposite.lastKey();
    }
    long[] acct = accounts.get(trader);
    if (acct == null) return reject(r, "MARGIN");
    long exposure = qty * checkPrice;
    if (buy ? exposure > Math.min(acct[0], acct[1]) : exposure > acct[1]) {
      return reject(r, "MARGIN");
    }

    long remaining = qty;
    while (remaining > 0 && !opposite.isEmpty()) {
      long best = buy ? opposite.firstKey() : opposite.lastKey();
      if (!market && (buy ? best > price : best < price)) break;
      ArrayDeque<Ord> queue = opposite.get(best);
      while (remaining > 0 && !queue.isEmpty()) {
        Ord maker = queue.peekFirst();
        long q = Math.min(remaining, maker.remaining);
        long buyOrder = buy ? r.orderId : maker.id;
        long sellOrder = buy ? maker.id : r.orderId;
        long buyer = buy ? trader : maker.trader;
        long seller = buy ? maker.trader : trader;
        r.trades.add(nextTradeId++ + "," + now + "," + pair + "," + best + "," + q + ","
            + buyOrder + "," + sellOrder + "," + buyer + "," + seller + ","
            + (buy ? "BUY" : "SELL"));
        accounts.get(buyer)[0] -= best * q;
        accounts.get(seller)[0] += best * q;
        remaining -= q;
        maker.remaining -= q;
        if (maker.remaining == 0) {
          queue.pollFirst();
          live.remove(maker.id);
        }
      }
      if (queue.isEmpty()) opposite.remove(best);
    }
    r.filled = qty - remaining;
    if (remaining == 0) {
      r.status = "FILLED";
    } else if (market) {
      r.status = "PARTIAL";
    } else {
      Ord o = new Ord(r.orderId, pair, buy, trader, price, remaining);
      (buy ? bids : asks).get(pair).computeIfAbsent(price, p -> new ArrayDeque<>()).addLast(o);
      live.put(o.id, o);
      r.resting = remaining;
      r.status = r.filled > 0 ? "PARTIAL" : "QUEUED";
    }
    return r;
  }

  private static Result reject(Result r, String reason) {
    r.status = "REJECTED";
    r.reason = reason;
    return r;
  }

  /** Remaining quantity of the cancelled order, or -1 for NOT_FOUND. */
  long cancel(long orderId) {
    seq++;
    Ord o = live.remove(orderId);
    if (o == null) return -1;
    TreeMap<Long, ArrayDeque<Ord>> side = (o.buy ? bids : asks).get(o.pair);
    ArrayDeque<Ord> queue = side.get(o.price);
    for (Iterator<Ord> it = queue.iterator(); it.hasNext();) {
      if (it.next() == o) {
        it.remove();
        break;
      }
    }
    if (queue.isEmpty()) side.remove(o.price);
    return o.remaining;
  }

  int restingOrders() {
    return live.size();
  }

  /**
   * One side of one pair, best first, as "price:volume[id/remaining id/remaining ...]"
   * entries: the exact shape the differential test renders the engine's book into.
   */
  List<String> depth(String pair, boolean bidSide) {
    TreeMap<Long, ArrayDeque<Ord>> side = (bidSide ? bids : asks).get(pair);
    List<String> out = new ArrayList<>();
    List<Long> prices = new ArrayList<>(side.keySet());
    if (bidSide) Collections.reverse(prices);
    for (long p : prices) {
      long vol = 0;
      StringBuilder ids = new StringBuilder();
      for (Ord o : side.get(p)) {
        vol += o.remaining;
        ids.append(ids.length() == 0 ? "" : " ").append(o.id).append('/').append(o.remaining);
      }
      out.add(p + ":" + vol + "[" + ids + "]");
    }
    return out;
  }
}
