package ads.sim;

import ads.directory.Directory;
import ads.engine.CancelStatus;
import ads.engine.MatchingEngine;
import ads.engine.OrderStatus;
import ads.engine.OrderType;
import ads.engine.Side;
import ads.engine.Trade;
import ads.engine.TradeSink;
import ads.scanner.ArbitrageScanner;
import ads.vault.Vault;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One simulation run (doc 08 Stage 4), on objects of its own: a fresh Directory, an
 * in-memory Vault with the twelve simulation accounts, a MatchingEngine with no Logbook and
 * no event stream, and an ArbitrageScanner on synthetic rates. Nothing here can reach the
 * live server's books, accounts or stream.
 *
 * <ol>
 *   <li>{@link OrderGenerator} writes the stream (untimed; its scratch engine also warms
 *       the JIT for the engine's code).</li>
 *   <li>The engine replays it from empty books. Each submit and each cancel is timed on its
 *       own with {@link System#nanoTime()} around the engine call alone. Every
 *       {@code scanEvery} operations the rates take a step and one scan runs, timed on its
 *       own. {@code durationMs} is the wall time of the order operations only: the clock
 *       stops for the rate step and the scan.</li>
 *   <li>If asked, the {@link NaiveMatcher} replays the same stream, timed the same way,
 *       after an untimed warm-up on a separate instance over the first
 *       {@link #BASELINE_WARMUP} operations.</li>
 * </ol>
 *
 * <p>{@code numOrders} counts every operation, cancels and invalid orders included, and
 * {@code ordersPerSec} is {@code numOrders / durationMs}.
 */
public final class Simulator {
  public static final int MAX_ORDERS = 1_000_000;
  public static final int DEFAULT_ORDERS = 5_000;
  public static final long DEFAULT_SEED = 42;
  public static final int DEFAULT_SCAN_EVERY = 100;
  public static final BigDecimal DEFAULT_VOLATILITY = new BigDecimal("0.01");
  public static final BigDecimal MAX_VOLATILITY = BigDecimal.ONE;
  static final int BASELINE_WARMUP = 20_000;

  /** Validated request parameters. */
  public static final class Params {
    public int numOrders = DEFAULT_ORDERS;
    public long seed = DEFAULT_SEED;
    public boolean baseline = true;
    public int scanEvery = DEFAULT_SCAN_EVERY;
    public BigDecimal rateVolatility = DEFAULT_VOLATILITY;
  }

  private final double minProfitPercent;

  public Simulator(double minProfitPercent) {
    this.minProfitPercent = minProfitPercent;
  }

  /** Runs one simulation and returns the response body (see 09_api_contract.md §3.12). */
  public Map<String, Object> run(Params p) {
    OrderStream stream = OrderGenerator.generate(p.seed, p.numOrders);
    Run engine = runEngine(stream, p);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("numOrders", p.numOrders);
    out.put("seed", p.seed);
    out.put("scanEvery", p.scanEvery);
    out.put("rateVolatility", p.rateVolatility);
    engine.put(out);
    Map<String, Object> scans = new LinkedHashMap<>();
    long[] s = Percentiles.summarize(engine.scanNs, engine.scanCount);
    scans.put("count", engine.scanCount);
    scans.put("p50Us", Percentiles.micros(s[1]));
    scans.put("p99Us", Percentiles.micros(s[3]));
    scans.put("loopholesFound", engine.loopholes);
    out.put("scans", scans);
    out.put("tradeChecksum", Fnv64.hex(engine.checksum.value()));
    out.put("restingOrders", engine.resting);
    if (p.baseline) {
      warmBaseline(stream);
      Run naive = runBaseline(stream);
      Map<String, Object> b = new LinkedHashMap<>();
      b.put("numOrders", p.numOrders);
      naive.put(b);
      b.put("tradeChecksum", Fnv64.hex(naive.checksum.value()));
      b.put("restingOrders", naive.resting);
      b.put("checksumMatch", naive.checksum.value() == engine.checksum.value());
      b.put("speedup", BigDecimal.valueOf(naive.nanos)
          .divide(BigDecimal.valueOf(Math.max(1, engine.nanos)), 2, RoundingMode.HALF_EVEN));
      out.put("baseline", b);
    } else {
      out.put("baseline", null);
    }
    out.put("finishedAt", Instant.now().toString());
    return out;
  }

  /** What one replay measured. */
  static final class Run {
    final Fnv64 checksum = new Fnv64();
    final int numOrders;
    long trades;
    long rejects;
    long cancels;
    long nanos;
    int resting;
    final long[] submitNs;
    final long[] cancelNs;
    int submitCount;
    int cancelCount;
    long[] scanNs = new long[0];
    int scanCount;
    long loopholes;

    Run(OrderStream s) {
      numOrders = s.size;
      int cancels = 0;
      for (int i = 0; i < s.size; i++) if (s.op[i] == OrderStream.CANCEL) cancels++;
      submitNs = new long[s.size - cancels];
      cancelNs = new long[cancels];
    }

    void put(Map<String, Object> out) {
      out.put("durationMs", BigDecimal.valueOf(nanos / 1_000, 3));
      out.put("ordersPerSec", nanos == 0 ? 0 : numOrders * 1_000_000_000L / nanos);
      out.put("trades", trades);
      out.put("rejects", rejects);
      out.put("cancels", cancels);
      Map<String, Object> latency = new LinkedHashMap<>();
      latency.put("submit", summary(submitNs, submitCount));
      latency.put("cancel", summary(cancelNs, cancelCount));
      out.put("latencyUs", latency);
    }

    private static Map<String, Object> summary(long[] ns, int n) {
      long[] s = Percentiles.summarize(ns, n);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("count", n);
      m.put("p50", Percentiles.micros(s[1]));
      m.put("p90", Percentiles.micros(s[2]));
      m.put("p99", Percentiles.micros(s[3]));
      m.put("max", Percentiles.micros(s[4]));
      return m;
    }
  }

  private Run runEngine(OrderStream s, Params p) {
    Directory directory = new Directory();
    Vault vault = Vault.inMemory();
    OrderGenerator.openAccounts(vault);
    MatchingEngine engine = new MatchingEngine(directory, vault);
    Run run = new Run(s);
    engine.setTradeSink(new TradeSink() {
      @Override
      public void onTrade(Trade t) {
        run.trades++;
        run.checksum.addTrade(t.tradeId(), t.seq(), t.pairId(), t.priceTick(), t.quantity(),
            t.buyOrderId(), t.sellOrderId(), t.buyerId(), t.sellerId(), t.takerSide() == Side.BUY);
      }
    });
    ArbitrageScanner scanner = new ArbitrageScanner(directory, minProfitPercent);
    SyntheticRates rates = new SyntheticRates(scanner, p.seed, p.rateVolatility.doubleValue());
    if (p.scanEvery > 0) run.scanNs = new long[s.size / p.scanEvery];

    long blockStart = System.nanoTime();
    for (int i = 0; i < s.size; i++) {
      if (s.op[i] == OrderStream.SUBMIT) {
        Side side = s.buy[i] ? Side.BUY : Side.SELL;
        OrderType type = s.market[i] ? OrderType.MARKET : OrderType.LIMIT;
        long t0 = System.nanoTime();
        OrderStatus status = engine.submit(s.trader[i], s.pair[i], side, type, s.price[i],
            s.quantity[i]).status();
        run.submitNs[run.submitCount++] = System.nanoTime() - t0;
        if (status == OrderStatus.REJECTED) run.rejects++;
      } else {
        long t0 = System.nanoTime();
        CancelStatus status = engine.cancel(s.target[i]).status();
        run.cancelNs[run.cancelCount++] = System.nanoTime() - t0;
        if (status == CancelStatus.CANCELLED) run.cancels++;
      }
      if (p.scanEvery > 0 && (i + 1) % p.scanEvery == 0) {
        run.nanos += System.nanoTime() - blockStart;
        rates.step();
        long t0 = System.nanoTime();
        boolean found = scanner.scan().hasArbitrage();
        run.scanNs[run.scanCount++] = System.nanoTime() - t0;
        if (found) run.loopholes++;
        blockStart = System.nanoTime();
      }
    }
    run.nanos += System.nanoTime() - blockStart;
    run.resting = engine.restingOrders();
    return run;
  }

  private static NaiveMatcher baseline(Run run, Directory directory) {
    NaiveMatcher m = new NaiveMatcher(directory.pairCount(),
        (tradeId, seq, pairId, price, qty, buyId, sellId, buyer, seller, takerBuys) -> {
          if (run == null) return;
          run.trades++;
          run.checksum.addTrade(tradeId, seq, pairId, price, qty, buyId, sellId, buyer, seller,
              takerBuys);
        });
    for (long t = 1; t <= OrderGenerator.TRADERS; t++) {
      m.open(t, OrderGenerator.TRADER_CASH, OrderGenerator.TRADER_CASH);
    }
    return m;
  }

  private static void warmBaseline(OrderStream s) {
    NaiveMatcher m = baseline(null, new Directory());
    int n = Math.min(s.size, BASELINE_WARMUP);
    for (int i = 0; i < n; i++) {
      if (s.op[i] == OrderStream.SUBMIT) {
        m.submit(s.trader[i], s.pair[i], s.buy[i], s.market[i], s.price[i], s.quantity[i]);
      } else {
        m.cancel(s.target[i]);
      }
    }
  }

  private static Run runBaseline(OrderStream s) {
    Run run = new Run(s);
    NaiveMatcher m = baseline(run, new Directory());
    long start = System.nanoTime();
    for (int i = 0; i < s.size; i++) {
      if (s.op[i] == OrderStream.SUBMIT) {
        long t0 = System.nanoTime();
        int code = m.submit(s.trader[i], s.pair[i], s.buy[i], s.market[i], s.price[i],
            s.quantity[i]);
        run.submitNs[run.submitCount++] = System.nanoTime() - t0;
        if (code != NaiveMatcher.OK) run.rejects++;
      } else {
        long t0 = System.nanoTime();
        long left = m.cancel(s.target[i]);
        run.cancelNs[run.cancelCount++] = System.nanoTime() - t0;
        if (left >= 0) run.cancels++;
      }
    }
    run.nanos = System.nanoTime() - start;
    run.resting = m.restingOrders();
    return run;
  }
}
