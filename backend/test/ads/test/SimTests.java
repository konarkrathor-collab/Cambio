package ads.test;

import ads.api.App;
import ads.sim.Fnv64;
import ads.sim.Percentiles;
import ads.sim.Simulator;
import ads.util.Json;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Stage 4: the simulator, its naive baseline, and POST /api/simulate. */
final class SimTests {
  private SimTests() {
  }

  static void run(TestRunner r) {
    r.section("simulator (doc 08 Stage 4)");
    r.check("percentiles: nearest rank on known arrays; microseconds exact", SimTests::percentiles);
    r.check("FNV-64: the published offset basis and test vector", SimTests::fnv);
    r.check("same seed, same tradeChecksum (and the whole result); another seed differs",
        SimTests::sameSeed);
    r.check("engine and naive baseline agree (trades, rejects, cancels, resting, every trader's final cash)"
        + " over seeds and sizes", SimTests::baselineAgrees);
    r.check("deep profile: deterministic, engine = baseline (trades and cash), thousands resting;"
        + " shallow stream unchanged since Stage 4", SimTests::deepProfile);
    r.check("order mix: ~20% cancels (all of live orders), 10-16% rejects, orders cross",
        SimTests::mix);
    r.check("POST /api/simulate leaves the live book, accounts, trades, seq and stream untouched",
        SimTests::isolated);
    r.check("a second simulation while one runs gets 409 SIMULATION_RUNNING; GET last; health.hasSimulation",
        SimTests::concurrent);
    r.check("bad parameters get 400; an empty body is all defaults", SimTests::badParams);
  }

  private static void check(boolean ok, String what) {
    if (!ok) throw new AssertionError(what);
  }

  private static TestRunner.Result percentiles() {
    long[] a = new long[100];
    for (int i = 0; i < 100; i++) a[i] = 100 - i; // 100..1, unsorted on purpose
    long[] s = Percentiles.summarize(a, 100);
    check(s[0] == 100 && s[1] == 50 && s[2] == 90 && s[3] == 99 && s[4] == 100,
        "1..100: " + java.util.Arrays.toString(s));
    long[] ten = {7, 3, 9, 1, 5, 10, 2, 8, 4, 6};
    s = Percentiles.summarize(ten, 10);
    // ranks ceil(5)=5, ceil(9)=9, ceil(9.9)=10
    check(s[1] == 5 && s[2] == 9 && s[3] == 10 && s[4] == 10, "1..10: " + java.util.Arrays.toString(s));
    long[] one = {42};
    s = Percentiles.summarize(one, 1);
    check(s[1] == 42 && s[2] == 42 && s[3] == 42 && s[4] == 42, "one sample");
    s = Percentiles.summarize(new long[0], 0);
    check(s[0] == 0 && s[1] == 0 && s[4] == 0, "no samples");
    long[] partial = {5, 1, 999, 999}; // only the first two count
    s = Percentiles.summarize(partial, 2);
    check(s[1] == 1 && s[3] == 5 && s[4] == 5, "prefix only");
    check(Percentiles.micros(1234).equals(new BigDecimal("1.234"))
        && Percentiles.micros(7).toPlainString().equals("0.007"), "micros");
    return TestRunner.Result.pass("p50/p90/p99/max of 1..100 = 50/90/99/100; of 1..10 = 5/9/10/10");
  }

  private static TestRunner.Result fnv() {
    Fnv64 h = new Fnv64();
    check(h.value() == 0xcbf29ce484222325L, "offset basis");
    // FNV-1a 64 of the single byte 'a' is af63dc4c8601ec8c; add() folds 8 bytes, so check
    // the per-byte step on a value whose upper 7 bytes are zero against a hand fold
    long manual = 0xcbf29ce484222325L;
    long v = 'a';
    for (int i = 0; i < 8; i++) {
      manual ^= (v >>> (8 * i)) & 0xff;
      manual *= 0x100000001b3L;
    }
    h.add('a');
    check(h.value() == manual, "8-byte fold");
    long single = (0xcbf29ce484222325L ^ 'a') * 0x100000001b3L;
    check(Fnv64.hex(single).equals("af63dc4c8601ec8c"), "published vector: " + Fnv64.hex(single));
    check(Fnv64.hex(1).equals("0000000000000001"), "hex padding");
    return TestRunner.Result.pass(null);
  }

  private static Simulator.Params params(int n, long seed, boolean baseline, String profile) {
    Simulator.Params p = params(n, seed, baseline);
    p.profile = profile;
    return p;
  }

  private static Simulator.Params params(int n, long seed, boolean baseline) {
    Simulator.Params p = new Simulator.Params();
    p.numOrders = n;
    p.seed = seed;
    p.baseline = baseline;
    return p;
  }

  /** The result without the fields that are timings or wall-clock. */
  private static String deterministic(Map<String, Object> m) {
    return "" + m.get("numOrders") + m.get("seed") + m.get("trades") + m.get("rejects")
        + m.get("cancels") + m.get("tradeChecksum") + m.get("restingOrders")
        + ((Map<?, ?>) m.get("scans")).get("count") + ((Map<?, ?>) m.get("scans")).get("loopholesFound")
        + count(m, "submit") + count(m, "cancel");
  }

  private static Object count(Map<?, ?> m, String op) {
    return ((Map<?, ?>) ((Map<?, ?>) m.get("latencyUs")).get(op)).get("count");
  }

  private static TestRunner.Result sameSeed() {
    Simulator sim = new Simulator(0.02);
    Map<String, Object> a = sim.run(params(30_000, 7, false));
    Map<String, Object> b = sim.run(params(30_000, 7, false));
    Map<String, Object> c = sim.run(params(30_000, 8, false));
    check(a.get("tradeChecksum").equals(b.get("tradeChecksum")), "same seed, different checksum");
    check(deterministic(a).equals(deterministic(b)), "same seed, different counts");
    check(!a.get("tradeChecksum").equals(c.get("tradeChecksum")), "seeds 7 and 8 collide");
    check(a.get("baseline") == null, "baseline:false gives baseline null");
    return TestRunner.Result.pass("seed 7: " + a.get("tradeChecksum") + " twice; seed 8: "
        + c.get("tradeChecksum"));
  }

  private static TestRunner.Result baselineAgrees() {
    Simulator sim = new Simulator(0.02);
    long[] seeds = {1, 2, 42, 7777, -5};
    int[] sizes = {1, 10, 1_000, 25_000};
    int runs = 0;
    long trades = 0;
    for (String profile : new String[] {Simulator.SHALLOW, Simulator.DEEP}) {
    for (long seed : seeds) {
      for (int n : sizes) {
        Map<String, Object> e = sim.run(params(n, seed, true, profile));
        Map<?, ?> b = (Map<?, ?>) e.get("baseline");
        String where = profile + " seed " + seed + " n " + n;
        assertAgree(e, b, where);
        runs++;
        trades += (Long) e.get("trades");
      }
    }
    }
    // one large run too
    Map<String, Object> big = sim.run(params(200_000, 99, true));
    assertAgree(big, (Map<?, ?>) big.get("baseline"), "shallow 200k");
    return TestRunner.Result.pass(runs + " runs, both profiles (" + trades + " trades) plus"
        + " 200,000 shallow orders: trades and every trader's final cash agree");
  }

  /** Engine and baseline must agree on trades, counts and every trader's final cash. */
  private static void assertAgree(Map<?, ?> e, Map<?, ?> b, String where) {
    check(b.get("tradeChecksum").equals(e.get("tradeChecksum")), where + ": trade checksum");
    for (String f : new String[] {"trades", "rejects", "cancels", "restingOrders", "numOrders",
        "finalCash", "finalCashTotal"}) {
      check(b.get(f).equals(e.get(f)), where + ": " + f + " " + e.get(f) + " vs " + b.get(f));
    }
    check(count(b, "submit").equals(count(e, "submit")) && count(b, "cancel").equals(count(e, "cancel")),
        where + ": op counts");
    check(Boolean.TRUE.equals(b.get("tradesMatch")) && Boolean.TRUE.equals(b.get("cashMatch"))
        && Boolean.TRUE.equals(b.get("checksumMatch")), where + ": match flags " + b);
    long total = 0;
    for (Object c : (List<?>) e.get("finalCash")) total += (Long) c;
    check(total == (Long) e.get("finalCashTotal")
        && total == OrderGeneratorFacts.TRADERS * OrderGeneratorFacts.CASH, where
        + ": cash is conserved (every trade moves it between two simulation traders)");
  }

  /** The simulation accounts, restated (doc: contract section 3.12). */
  private static final class OrderGeneratorFacts {
    static final long TRADERS = 12;
    static final long CASH = 1_000_000_000_000_000L;
  }

  private static TestRunner.Result deepProfile() {
    Simulator sim = new Simulator(0.02);
    Map<String, Object> a = sim.run(params(60_000, 11, false, Simulator.DEEP));
    Map<String, Object> b = sim.run(params(60_000, 11, false, Simulator.DEEP));
    check(a.get("profile").equals("deep"), "profile echoed");
    check(deterministic(a).equals(deterministic(b)) && a.get("finalCash").equals(b.get("finalCash")),
        "deep: same seed, different result");
    long resting = ((Integer) a.get("restingOrders")).longValue();
    check(resting > 6 * 3_000, "deep should leave thousands resting per pair: " + resting);
    Map<String, Object> agree = sim.run(params(30_000, 4, true, Simulator.DEEP));
    assertAgree(agree, (Map<?, ?>) agree.get("baseline"), "deep 30k");
    Map<String, Object> shallow = sim.run(params(60_000, 11, false, Simulator.SHALLOW));
    long shallowResting = ((Integer) shallow.get("restingOrders")).longValue();
    check(shallowResting < 1_000, "shallow stays shallow: " + shallowResting);
    // the Stage 4 default, captured in the contract before the profile existed
    Map<String, Object> pinned = sim.run(params(5_000, 42, true));
    check(pinned.get("tradeChecksum").equals("cf084b63dbf339ae") && pinned.get("profile").equals("shallow")
        && ((Long) pinned.get("trades")) == 2291, "shallow stream changed: " + pinned.get("tradeChecksum"));
    return TestRunner.Result.pass("deep 60,000: " + resting + " resting (shallow: " + shallowResting
        + "); speedup at 30,000 deep: " + ((Map<?, ?>) agree.get("baseline")).get("speedup")
        + "x; shallow seed 42 still cf084b63dbf339ae");
  }

  private static TestRunner.Result mix() {
    int n = 100_000;
    Map<String, Object> m = new Simulator(0.02).run(params(n, 42, false));
    long cancelOps = ((Number) count(m, "cancel")).longValue();
    long cancelled = (Long) m.get("cancels");
    long rejects = (Long) m.get("rejects");
    long trades = (Long) m.get("trades");
    check(cancelOps > 0.19 * n && cancelOps < 0.21 * n, "cancel share " + cancelOps);
    check(cancelled > 0.99 * cancelOps, "cancels of live orders " + cancelled + "/" + cancelOps);
    // 10% invalid, plus market orders on an empty side (NO_LIQUIDITY)
    check(rejects > 0.095 * n && rejects < 0.16 * n, "rejects " + rejects);
    check(trades > 0.3 * n, "orders should cross constantly: " + trades + " trades");
    return TestRunner.Result.pass(n + " ops: " + cancelOps + " cancels (" + cancelled + " hit), "
        + rejects + " rejects, " + trades + " trades");
  }

  // ---------------------------------------------------------------- over HTTP

  private interface Body {
    TestRunner.Result run(GatewayTests.Client c) throws Exception;
  }

  private static TestRunner.Result withApp(SimulatorHook hook, Body body) throws Exception {
    Path dir = Files.createTempDirectory("ads-sim-");
    App.Config cfg = new App.Config();
    cfg.port = 0;
    cfg.dataDir = dir;
    cfg.seedDemoAccounts = true;
    if (hook != null) cfg.simulationTask = hook::run;
    App app = App.start(cfg);
    try {
      return body.run(new GatewayTests.Client(app));
    } finally {
      app.stop();
      try (Stream<Path> s = Files.walk(dir)) {
        s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
      }
    }
  }

  private interface SimulatorHook {
    Map<String, Object> run(Simulator.Params p) throws Exception;
  }

  private static TestRunner.Result isolated() throws Exception {
    return withApp(null, c -> {
      c.order(1, "EUR/USD", "SELL", "LIMIT", "1.08510", 100);
      c.order(2, "EUR/USD", "BUY", "LIMIT", "1.08510", 40);
      c.order(3, "USD/JPY", "BUY", "LIMIT", "156.870", 7);
      String[] views = {"/api/orderbook/EUR-USD?depth=50", "/api/orderbook/USD-JPY?depth=50",
          "/api/orderbook/GBP-USD?depth=50", "/api/accounts", "/api/trades?limit=500",
          "/api/inspector/EUR-USD", "/api/arbitrage/scan"};
      String[] before = new String[views.length];
      for (int i = 0; i < views.length; i++) before[i] = c.send("GET", views[i], null).body();
      Object seqBefore = c.json("GET", "/api/health", null, 200).get("seq");
      try (GatewayTests.SseReader sse = new GatewayTests.SseReader(c.app.port())) {
        sse.await("retry:", 3_000);
        Map<?, ?> r = c.json("POST", "/api/simulate", "{\"numOrders\":50000,\"seed\":3}", 200);
        check((Long) r.get("trades") > 0, "the simulation traded");
        check(Boolean.TRUE.equals(((Map<?, ?>) r.get("baseline")).get("checksumMatch")), "match");
        c.order(4, "AUD/USD", "BUY", "LIMIT", "0.66000", 1); // a marker after the simulation
        String first = sse.await("event:", 3_000);
        check(first.contains("BOOK_UPDATE") && first.contains("AUD/USD"),
            "the first event after the simulation must be our own marker, got " + first);
      }
      for (int i = 0; i < views.length; i++) {
        String now = c.send("GET", views[i], null).body();
        String expect = before[i];
        if (views[i].startsWith("/api/orderbook") || views[i].startsWith("/api/inspector")
            || views[i].startsWith("/api/accounts") || views[i].startsWith("/api/trades")) {
          // the AUD/USD marker moved seq by one; nothing else may differ
          now = now.replaceAll("\"seq\":\\d+", "");
          expect = expect.replaceAll("\"seq\":\\d+", "");
        }
        check(now.equals(expect), views[i] + " changed:\n  before " + expect + "\n  after  " + now);
      }
      Object seqAfter = c.json("GET", "/api/health", null, 200).get("seq");
      check(((Long) seqAfter) == ((Long) seqBefore) + 1, "seq " + seqBefore + " -> " + seqAfter
          + " (only the marker order may count)");
      return TestRunner.Result.pass("50,000 simulated orders; live views identical; seq "
          + seqBefore + " -> " + seqAfter + " (the marker); stream saw only the marker");
    });
  }

  private static TestRunner.Result concurrent() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    SimulatorHook hook = p -> {
      entered.countDown();
      if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
      return new Simulator(0.02).run(p);
    };
    return withApp(hook, c -> {
      check(c.errorCode(c.send("GET", "/api/simulate/last", null)).equals("404 NO_SIMULATION"),
          "last before any");
      check(Boolean.FALSE.equals(c.json("GET", "/api/health", null, 200).get("hasSimulation")),
          "health.hasSimulation before any");
      ExecutorService pool = Executors.newSingleThreadExecutor();
      try {
        Future<HttpResponse<String>> first =
            pool.submit(() -> c.send("POST", "/api/simulate", "{\"numOrders\":2000,\"seed\":5}"));
        check(entered.await(10, TimeUnit.SECONDS), "first simulation never started");
        HttpResponse<String> second = c.send("POST", "/api/simulate", "{\"numOrders\":10}");
        check(c.errorCode(second).equals("409 SIMULATION_RUNNING"), "second: " + second.body());
        // the live engine keeps serving while the simulation runs
        c.order(1, "EUR/USD", "SELL", "LIMIT", "1.09000", 1);
        check(c.send("GET", "/api/simulate/last", null).statusCode() == 404, "last while running");
        release.countDown();
        HttpResponse<String> done = first.get(30, TimeUnit.SECONDS);
        check(done.statusCode() == 200, "first: " + done.statusCode() + " " + done.body());
        check(Boolean.TRUE.equals(c.json("GET", "/api/health", null, 200).get("hasSimulation")),
            "health.hasSimulation after the first result");
        HttpResponse<String> last = c.send("GET", "/api/simulate/last", null);
        check(last.statusCode() == 200 && last.body().equals(done.body()), "last != result");
        // and the next one is accepted again
        check(c.send("POST", "/api/simulate", "{\"numOrders\":10,\"baseline\":false}").statusCode() == 200,
            "after the first finished");
        Map<?, ?> l = (Map<?, ?>) Json.parseExact(c.send("GET", "/api/simulate/last", null).body());
        check(((Long) l.get("numOrders")) == 10, "last is the newest");
      } finally {
        release.countDown();
        pool.shutdownNow();
      }
      return TestRunner.Result.pass("409 while running; live orders still served; last = result");
    });
  }

  private static TestRunner.Result badParams() throws Exception {
    return withApp(null, c -> {
      String[] bad = {
          "{\"numOrders\":0}", "{\"numOrders\":1000001}", "{\"numOrders\":\"5\"}",
          "{\"numOrders\":1.5}", "{\"numOrders\":-3}", "{\"seed\":\"x\"}", "{\"seed\":1.5}",
          "{\"seed\":99999999999999999999}", "{\"baseline\":\"yes\"}", "{\"baseline\":1}",
          "{\"scanEvery\":-1}", "{\"scanEvery\":1000001}", "{\"rateVolatility\":1.01}",
          "{\"rateVolatility\":-0.1}", "{\"rateVolatility\":\"0.1\"}", "{\"orders\":5}", "[]",
          "{\"profile\":\"medium\"}", "{\"profile\":5}", "{\"profile\":null}",
          "{\"profile\":\"deep\",\"numOrders\":200001}",
          "{\"profile\":\"deep\",\"baseline\":true,\"numOrders\":1000000}",
      };
      for (String b : bad) {
        String code = c.errorCode(c.send("POST", "/api/simulate", b));
        check(code.equals("400 BAD_REQUEST"), b + " -> " + code);
      }
      check(c.errorCode(c.send("POST", "/api/simulate", "{\"numOrders\":")).equals("400 BAD_JSON"), "json");
      Map<?, ?> d = c.json("POST", "/api/simulate", "", 200);
      check(((Long) d.get("numOrders")) == 5000 && ((Long) d.get("seed")) == 42
          && ((Long) d.get("scanEvery")) == 100
          && new BigDecimal("0.01").compareTo((BigDecimal) d.get("rateVolatility")) == 0
          && d.get("baseline") instanceof Map, "defaults: " + d);
      Map<?, ?> edge = c.json("POST", "/api/simulate",
          "{\"numOrders\":1,\"seed\":-9,\"baseline\":false,\"scanEvery\":0,\"rateVolatility\":0}", 200);
      check(((Map<?, ?>) edge.get("scans")).get("count").equals(0L) && edge.get("baseline") == null,
          "edge values: " + edge);
      Map<?, ?> vol1 = c.json("POST", "/api/simulate", "{\"numOrders\":300,\"rateVolatility\":1}", 200);
      check(((Map<?, ?>) vol1.get("scans")).get("count").equals(3L), "scans at 300/100");
      Map<?, ?> deepNoBaseline = c.json("POST", "/api/simulate",
          "{\"profile\":\"DEEP\",\"baseline\":false,\"numOrders\":200001,\"scanEvery\":0}", 200);
      check("deep".equals(deepNoBaseline.get("profile")) && deepNoBaseline.get("baseline") == null,
          "the cap applies only with the baseline: " + deepNoBaseline.get("profile"));
      check("shallow".equals(d.get("profile")), "default profile");
      return TestRunner.Result.pass(bad.length + " bad bodies -> 400 (incl. the deep cap);"
          + " empty body -> defaults; deep without baseline is not capped");
    });
  }
}
