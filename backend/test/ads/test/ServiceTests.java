package ads.test;

import ads.bench.core.Rng;
import ads.directory.Directory;
import ads.engine.MatchingEngine;
import ads.engine.OrderResult;
import ads.engine.OrderType;
import ads.engine.Side;
import ads.logbook.Logbook;
import ads.scanner.ArbitrageScanner;
import ads.scanner.ScanResult;
import ads.vault.Account;
import ads.vault.Vault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Stage 1 service tests: Directory, Vault, Logbook, Scanner and the demo. */
final class ServiceTests {
  private ServiceTests() {
  }

  static void run(TestRunner r) throws IOException {
    Path tmp = Files.createTempDirectory("ads-stage1-");
    try {
      r.section("directory");
      r.check("132 pairs with dense ids; lookups, prefixes, tick sizes", ServiceTests::directory);

      r.section("vault: accounts in backend/data/accounts.jsonl style files");
      r.check("the loader refuses a file outside the bounds (margin > 10^15, |cash| > 10^18,"
          + " total > 10^18, more than 1,000 accounts) and accepts the edges",
          () -> vaultBounds(tmp.resolve("vbounds")));
      r.check("flush then reopen (in-process restart) restores every account",
          () -> vaultRestart(tmp.resolve("v1")));
      r.check("restart in a NEW JVM: run.sh accounts reads back the balances",
          () -> vaultNewJvm(tmp.resolve("v2")));
      r.check("engine trading through a 2-entry LRU cache: evictions lose nothing,"
          + " balances survive a restart", () -> vaultEngineRestart(tmp.resolve("v3")));
      r.check("flush is atomic (temp file + rename): no temp file left, old file replaced whole",
          () -> vaultAtomic(tmp.resolve("v4")));
      r.check("corrupt account files fail loudly with the line number",
          () -> vaultCorrupt(tmp.resolve("v5")));

      r.section("logbook: Huffman archive");
      r.check("30000 seeded orders, rotation every 97 trades: archive decodes byte-identical",
          () -> logbookRoundTrip(tmp.resolve("l1")));
      r.check("restart continues the segment numbering; archive = old + new records",
          () -> logbookRestart(tmp.resolve("l2")));
      r.check("a truncated segment or a missing segment fails the archive read",
          () -> logbookDamage(tmp.resolve("l3")));

      r.section("scanner: rate store + Bellman-Ford");
      r.check("tick sets both directions; crosses derived through USD; direct never overwritten",
          ServiceTests::scannerRates);
      r.check("Frankfurter snapshot: no arbitrage; injected +0.4% EUR/GBP: cycle, multiplier,"
          + " profit", ServiceTests::scannerSnapshot);
      r.check("0.001% threshold: +0.0005% ignored, +0.002% reported; pure-USD-derived noise"
          + " never reported", ServiceTests::scannerThreshold);
      r.check("5000 graphs with bid/ask noise at the threshold: every triangle >= threshold"
          + " reported (no triangle gap)", ServiceTests::scannerTrianglesNoGap);
      r.check("3000 random rate sets vs brute-force cycle enumeration (exact check + guarantee)",
          ServiceTests::scannerBruteForce);

      r.section("demo");
      r.check("run.sh demo twice in fresh JVMs: identical output, archive and cash checks pass",
          () -> demoReproducible(tmp.resolve("d")));
    } finally {
      deleteTree(tmp);
    }
  }

  // ---------------------------------------------------------------- directory

  private static TestRunner.Result directory() {
    Directory d = new Directory();
    if (d.pairCount() != 132) return TestRunner.Result.fail("pairs " + d.pairCount());
    for (int id = 0; id < 132; id++) {
      String p = d.pair(id);
      if (d.pairId(p) != id || !d.contains(p)) return TestRunner.Result.fail("id " + id);
      if (d.pairId(d.baseCurrency(id), d.quoteCurrency(id)) != id) {
        return TestRunner.Result.fail("currency ids of " + p);
      }
      if (!p.equals(d.currency(d.baseCurrency(id)) + "/" + d.currency(d.quoteCurrency(id)))) {
        return TestRunner.Result.fail("name of " + id);
      }
    }
    String[] out = new String[200];
    if (d.pairId("USD") != -1 || d.pairId("eur/usd") != -1 || d.pairId("EUR/EUR") != -1
        || d.pair(132) != null || d.pair(-1) != null) {
      return TestRunner.Result.fail("non-pairs must not resolve");
    }
    if (d.prefixSearch("USD/", out) != 11 || d.prefixSearch("", out) != 132) {
      return TestRunner.Result.fail("prefix counts");
    }
    if (d.tickDecimals(d.pairId("USD/JPY")) != 3 || d.tickDecimals(d.pairId("EUR/USD")) != 5) {
      return TestRunner.Result.fail("tick decimals");
    }
    return TestRunner.Result.pass("ids 0.." + (d.pairCount() - 1));
  }

  // ---------------------------------------------------------------- vault

  private static TestRunner.Result vaultBounds(Path dir) throws IOException {
    Files.createDirectories(dir);
    long m = Vault.MAX_OPENING_AMOUNT;
    long b = Vault.MAX_BALANCE;
    String[][] bad = {
        {"margin", "{\"traderId\":1,\"cash\":0,\"marginLimit\":" + (m + 1) + "}"},
        {"cash high", "{\"traderId\":1,\"cash\":" + (b + 1) + ",\"marginLimit\":0}"},
        {"cash low", "{\"traderId\":1,\"cash\":" + (-b - 1) + ",\"marginLimit\":0}"},
        {"total", "{\"traderId\":1,\"cash\":" + (b / 2 + 1) + ",\"marginLimit\":0}\n"
            + "{\"traderId\":2,\"cash\":" + (b / 2) + ",\"marginLimit\":0}"},
    };
    StringBuilder problems = new StringBuilder();
    for (String[] c : bad) {
      Path f = dir.resolve(c[0].replace(' ', '_') + ".jsonl");
      Files.writeString(f, c[1] + "\n");
      try {
        new Vault(f, 64);
        problems.append(c[0]).append(" accepted; ");
      } catch (IOException expected) {
        // refused, as it must be
      }
    }
    StringBuilder many = new StringBuilder();
    for (int i = 1; i <= Vault.MAX_ACCOUNTS + 1; i++) {
      many.append("{\"traderId\":").append(i).append(",\"cash\":1,\"marginLimit\":1}\n");
    }
    Path tooMany = dir.resolve("too_many.jsonl");
    Files.writeString(tooMany, many);
    try {
      new Vault(tooMany, 64);
      problems.append("1,001 accounts accepted; ");
    } catch (IOException expected) {
      // refused
    }
    // the edges load: exactly 1,000 accounts, margin 10^15, one balance at +10^18 offset by a
    // negative one (the total stays 10^18 at most)
    StringBuilder edge = new StringBuilder();
    edge.append("{\"traderId\":1,\"cash\":").append(b).append(",\"marginLimit\":").append(m).append("}\n");
    edge.append("{\"traderId\":2,\"cash\":").append(-b + 5).append(",\"marginLimit\":0}\n");
    for (int i = 3; i <= Vault.MAX_ACCOUNTS; i++) {
      edge.append("{\"traderId\":").append(i).append(",\"cash\":1,\"marginLimit\":1}\n");
    }
    Path ok = dir.resolve("edges.jsonl");
    Files.writeString(ok, edge);
    Vault v = new Vault(ok, 64);
    if (v.size() != Vault.MAX_ACCOUNTS) problems.append("edges: ").append(v.size()).append(" loaded; ");
    return problems.length() == 0
        ? TestRunner.Result.pass("5 out-of-bounds files refused; 1,000 accounts at the edges loaded")
        : TestRunner.Result.fail(problems.toString());
  }

  private static TestRunner.Result vaultRestart(Path dir) throws IOException {
    Path file = dir.resolve("accounts.jsonl");
    Vault v = new Vault(file, 4);
    Account a = v.open(101, 1_000_000, 500_000);
    Account b = v.open(102, 2_000_000, 900_000);
    v.open(103, 0, 0);
    v.settle(a, b, 12_345);
    v.flush();
    Vault again = new Vault(file, 4);
    if (again.size() != 3) return TestRunner.Result.fail("size " + again.size());
    long[][] want = { {101, 987_655, 500_000}, {102, 2_012_345, 900_000}, {103, 0, 0} };
    for (int i = 0; i < 3; i++) {
      Account x = again.accountAt(i);
      if (x.traderId() != want[i][0] || x.cash() != want[i][1] || x.marginLimit() != want[i][2]
          || again.account(want[i][0]) != x) {
        return TestRunner.Result.fail("account " + want[i][0] + " after restart");
      }
    }
    try {
      again.open(101, 1, 1);
      return TestRunner.Result.fail("duplicate open accepted");
    } catch (IllegalArgumentException expected) {
      // ok
    }
    if (again.dirty()) return TestRunner.Result.fail("freshly loaded vault is dirty");
    return TestRunner.Result.pass(Files.readAllLines(file).get(0));
  }

  private static TestRunner.Result vaultNewJvm(Path dir) throws Exception {
    Path file = dir.resolve("accounts.jsonl");
    Vault v = new Vault(file, 4);
    Account a = v.open(7, 5_000, 5_000);
    Account b = v.open(8, 0, 100);
    v.settle(a, b, 1_000);
    v.settle(b, a, 1_250); // trading may take cash below zero (Appendix A reserves nothing)
    v.flush();
    String out = runMain(Paths.get("."), "accounts", file.toString());
    String want = "trader 7  cash 5250  marginLimit 5000\ntrader 8  cash -250  marginLimit 100\n"
        + "2 accounts loaded from " + file + ", total cash 5000\n";
    return out.equals(want) ? TestRunner.Result.pass("child JVM printed the flushed balances")
        : TestRunner.Result.fail("child JVM printed:\n" + out);
  }

  private static TestRunner.Result vaultEngineRestart(Path dir) throws IOException {
    Path file = dir.resolve("accounts.jsonl");
    Directory d = new Directory();
    Vault v = new Vault(file, 2); // far fewer cache slots than traders
    for (long t = 1; t <= 20; t++) v.open(t, 1_000_000, 1_000_000);
    MatchingEngine eng = new MatchingEngine(d, v);
    Rng rng = new Rng(5);
    for (int i = 0; i < 20_000; i++) {
      eng.submit(1 + rng.nextInt(20), "EUR/USD", rng.nextInt(2) == 0 ? Side.BUY : Side.SELL,
          OrderType.LIMIT, 995 + rng.nextInt(11), 1 + rng.nextInt(20));
    }
    long[] cash = new long[21];
    long total = 0;
    for (int t = 1; t <= 20; t++) {
      cash[t] = v.account(t).cash();
      total += cash[t];
    }
    if (total != 20_000_000) return TestRunner.Result.fail("cash not conserved: " + total);
    v.flush();
    Vault again = new Vault(file, 2);
    for (int t = 1; t <= 20; t++) {
      if (again.account(t).cash() != cash[t]) {
        return TestRunner.Result.fail("trader " + t + " lost its balance across the restart");
      }
    }
    return TestRunner.Result.pass("20 traders, cache capacity 2, total cash conserved");
  }

  private static TestRunner.Result vaultAtomic(Path dir) throws IOException {
    Path file = dir.resolve("accounts.jsonl");
    Vault v = new Vault(file, 4);
    v.open(1, 10, 10);
    v.flush();
    Files.writeString(dir.resolve("accounts.jsonl.tmp"), "stale partial write from a crash");
    v.open(2, 20, 20);
    v.flush();
    List<String> lines = Files.readAllLines(file);
    if (Files.exists(dir.resolve("accounts.jsonl.tmp"))) {
      return TestRunner.Result.fail("temp file left behind");
    }
    if (!lines.equals(List.of("{\"traderId\":1,\"cash\":10,\"marginLimit\":10}",
        "{\"traderId\":2,\"cash\":20,\"marginLimit\":20}"))) {
      return TestRunner.Result.fail("file content " + lines);
    }
    return TestRunner.Result.pass(null);
  }

  private static TestRunner.Result vaultCorrupt(Path dir) throws IOException {
    Files.createDirectories(dir);
    String good = "{\"traderId\":1,\"cash\":10,\"marginLimit\":10}\n";
    String[][] cases = {
        { good + "{\"traderId\":2,\"cash\":10}\n", ":2: missing field marginLimit" },
        { good + good, ":2: duplicate traderId 1" },
        { good + "{\"traderId\":3,\"cash\":1.5,\"marginLimit\":1}\n", ":2: field cash" },
        { good + "{\"traderId\":4,\"cash\":1,\"marginLimit\":-1}\n", ":2: negative marginLimit" },
        { "{\"traderId\":5,\"cash\":1,\"marginLimit\":1,\"x\":2}\n", ":1: unknown field x" },
        { "{\"traderId\":6,\"cash\":1,\"marginLimit\":1", ":1: not a JSON object" },
    };
    for (String[] c : cases) {
      Path f = dir.resolve("bad.jsonl");
      Files.writeString(f, c[0]);
      try {
        new Vault(f, 4);
        return TestRunner.Result.fail("accepted: " + c[0]);
      } catch (IOException e) {
        if (!e.getMessage().contains(c[1])) {
          return TestRunner.Result.fail("message '" + e.getMessage() + "' lacks '" + c[1] + "'");
        }
      }
    }
    Path f = dir.resolve("neg.jsonl");
    Files.writeString(f, "{\"traderId\":9,\"cash\":-40,\"marginLimit\":0}\n");
    if (new Vault(f, 4).account(9).cash() != -40) {
      return TestRunner.Result.fail("negative cash (allowed by Appendix A) was not loaded");
    }
    return TestRunner.Result.pass(cases.length + " corrupt files rejected");
  }

  // ---------------------------------------------------------------- logbook

  /** Runs seeded crossing orders through an engine wired to the logbook. */
  private static StringBuilder trade(Logbook log, Directory d, long seed, int orders,
      StringBuilder expected) throws IOException {
    Vault v = Vault.inMemory();
    for (long t = 1; t <= 10; t++) v.open(t, 1_000_000_000, 1_000_000_000);
    MatchingEngine eng = new MatchingEngine(d, v);
    eng.setTradeSink(log);
    Rng rng = new Rng(seed);
    String[] pairs = { "EUR/USD", "USD/JPY", "GBP/CHF" };
    for (int i = 0; i < orders; i++) {
      OrderResult r = eng.submit(1 + rng.nextInt(10), pairs[rng.nextInt(3)],
          rng.nextInt(2) == 0 ? Side.BUY : Side.SELL,
          rng.nextInt(5) == 0 ? OrderType.MARKET : OrderType.LIMIT, 995 + rng.nextInt(11),
          1 + rng.nextInt(50));
      for (int k = 0; k < r.tradeCount(); k++) Logbook.appendRecord(expected, r.trade(k), d);
      log.rotateIfDue();
    }
    return expected;
  }

  private static TestRunner.Result logbookRoundTrip(Path dir) throws IOException {
    Directory d = new Directory();
    Logbook log = new Logbook(dir, d, 97);
    StringBuilder expected = trade(log, d, 21, 30_000, new StringBuilder());
    log.rotate();
    byte[] want = expected.toString().getBytes(StandardCharsets.US_ASCII);
    byte[] got = Logbook.readArchive(dir);
    if (!Arrays.equals(got, want)) {
      return TestRunner.Result.fail("archive differs: " + got.length + " vs " + want.length
          + " bytes");
    }
    long onDisk = 0;
    try (Stream<Path> s = Files.list(dir)) {
      for (Path p : (Iterable<Path>) s::iterator) onDisk += Files.size(p);
    }
    return TestRunner.Result.pass(log.archivedTrades() + " trades in " + log.segments()
        + " segments; " + want.length + " record bytes -> " + onDisk + " on disk");
  }

  private static TestRunner.Result logbookRestart(Path dir) throws IOException {
    Directory d = new Directory();
    Logbook first = new Logbook(dir, d, 50);
    StringBuilder expected = trade(first, d, 31, 2_000, new StringBuilder());
    first.rotate();
    int segs = first.segments();
    Logbook second = new Logbook(dir, d, 50);
    if (second.segments() != segs) return TestRunner.Result.fail("restart saw " + second.segments());
    trade(second, d, 32, 2_000, expected);
    second.rotate();
    second.rotate(); // nothing buffered: must not add an empty segment
    byte[] got = Logbook.readArchive(dir);
    if (!Arrays.equals(got, expected.toString().getBytes(StandardCharsets.US_ASCII))) {
      return TestRunner.Result.fail("archive after restart differs");
    }
    return TestRunner.Result.pass(segs + " segments, then " + second.segments() + " after restart");
  }

  private static TestRunner.Result logbookDamage(Path dir) throws IOException {
    Directory d = new Directory();
    Logbook log = new Logbook(dir, d, 40);
    trade(log, d, 41, 1_000, new StringBuilder());
    log.rotate();
    if (log.segments() < 3) return TestRunner.Result.fail("need >= 3 segments");
    Path seg2 = dir.resolve("trades-000002.huf");
    byte[] original = Files.readAllBytes(seg2);
    Files.write(seg2, Arrays.copyOf(original, original.length - 3));
    String truncated = readFailure(dir);
    Files.delete(seg2);
    String gap = readFailure(dir);
    if (truncated == null || !truncated.contains("corrupt archive segment")) {
      return TestRunner.Result.fail("truncated segment read: " + truncated);
    }
    if (gap == null || !gap.contains("archive gap")) {
      return TestRunner.Result.fail("missing segment read: " + gap);
    }
    return TestRunner.Result.pass(null);
  }

  private static String readFailure(Path dir) {
    try {
      Logbook.readArchive(dir);
      return null;
    } catch (IOException e) {
      return e.getMessage();
    }
  }

  // ---------------------------------------------------------------- scanner

  private static final String[] SNAP_CCY = { "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "INR" };
  private static final double[] SNAP_RATE =
      { 0.89238, 0.75641, 158.25, 1.4324, 1.4226, 0.83107, 96.73 };

  private static ArbitrageScanner snapshot(Directory d) {
    return snapshot(d, ArbitrageScanner.DEFAULT_MIN_PROFIT_PERCENT);
  }

  private static ArbitrageScanner snapshot(Directory d, double minProfitPercent) {
    ArbitrageScanner s = new ArbitrageScanner(d, minProfitPercent);
    for (int i = 0; i < SNAP_CCY.length; i++) s.onTick("USD/" + SNAP_CCY[i], SNAP_RATE[i]);
    return s;
  }

  private static TestRunner.Result scannerRates() {
    Directory d = new Directory();
    ArbitrageScanner s = new ArbitrageScanner(d);
    int usd = d.currencyId("USD");
    int eur = d.currencyId("EUR");
    int jpy = d.currencyId("JPY");
    int gbp = d.currencyId("GBP");
    s.onTick("EUR/USD", 1.0852); // Appendix B: 1 EUR = 1.0852 USD
    if (s.rate(eur, usd) != 1.0852 || s.rate(usd, eur) != 1.0 / 1.0852) {
      return TestRunner.Result.fail("tick and reciprocal");
    }
    if (!Double.isNaN(s.rate(eur, jpy))) return TestRunner.Result.fail("JPY leg unknown yet");
    s.onTick("USD/JPY", 158.25);
    double cross = s.rate(usd, jpy) / s.rate(usd, eur); // R(USD,b) / R(USD,a)
    if (s.rate(eur, jpy) != cross || s.isDirect(eur, jpy)) {
      return TestRunner.Result.fail("derived EUR/JPY " + s.rate(eur, jpy) + " vs " + cross);
    }
    s.onTick("EUR/JPY", 172.0);
    s.onTick("USD/GBP", 0.75);
    if (s.rate(eur, jpy) != 172.0 || !s.isDirect(jpy, eur)) {
      return TestRunner.Result.fail("a later USD tick overwrote the direct EUR/JPY");
    }
    if (!s.onTick("EUR/USD", 1.09) || s.onTick("EUR/XXX", 1.0)) {
      return TestRunner.Result.fail("onTick pair resolution");
    }
    for (double bad : new double[] { 0, -1, Double.NaN, Double.POSITIVE_INFINITY }) {
      try {
        s.onTick("EUR/USD", bad);
        return TestRunner.Result.fail("accepted rate " + bad);
      } catch (IllegalArgumentException expected) {
        // ok
      }
    }
    return TestRunner.Result.pass(null);
  }

  private static TestRunner.Result scannerSnapshot() {
    Directory d = new Directory();
    ArbitrageScanner s = snapshot(d);
    ScanResult clean = s.scan();
    if (clean.hasArbitrage() || clean.belowThreshold() || clean.edges() != 56) {
      return TestRunner.Result.fail("snapshot: arbitrage=" + clean.hasArbitrage() + " below="
          + clean.belowThreshold() + " edges=" + clean.edges());
    }
    int eur = d.currencyId("EUR");
    int gbp = d.currencyId("GBP");
    s.onTick("EUR/GBP", s.rate(eur, gbp) * 1.004);
    ScanResult hit = s.scan();
    String shape = cycleProblem(s, d, hit);
    if (shape != null) return TestRunner.Result.fail(shape);
    if (Math.abs(hit.multiplier() - 1.004) > 1e-9 || Math.abs(hit.profitPercent() - 0.4) > 1e-6) {
      return TestRunner.Result.fail("multiplier " + hit.multiplier() + " profit "
          + hit.profitPercent());
    }
    return TestRunner.Result.pass(String.join("->", hit.cycle()) + " x" + hit.multiplier());
  }

  /** The reported cycle closes, uses known rates, contains EUR->GBP or GBP->EUR... */
  private static String cycleProblem(ArbitrageScanner s, Directory d, ScanResult r) {
    if (!r.hasArbitrage()) return "no arbitrage reported";
    String[] c = r.cycle();
    if (c.length < 3 || !c[0].equals(c[c.length - 1])) return "cycle does not close";
    double m = 1.0;
    for (int i = 0; i + 1 < c.length; i++) {
      double rate = s.rate(d.currencyId(c[i]), d.currencyId(c[i + 1]));
      if (!(rate > 0)) return "cycle uses unknown rate " + c[i] + "->" + c[i + 1];
      m *= rate;
    }
    if (m != r.multiplier()) return "multiplier " + r.multiplier() + " is not the rate product " + m;
    if (Math.abs(r.profitPercent() - (m - 1) * 100) > 1e-12) return "profit % inconsistent";
    return null;
  }

  private static TestRunner.Result scannerThreshold() {
    Directory d = new Directory();
    int eur = d.currencyId("EUR");
    int gbp = d.currencyId("GBP");
    ArbitrageScanner below = snapshot(d, 0.001); // the threshold's own semantics, at 0.001%
    below.onTick("EUR/GBP", below.rate(eur, gbp) * 1.000005); // +0.0005%
    ArbitrageScanner above = snapshot(d, 0.001);
    above.onTick("EUR/GBP", above.rate(eur, gbp) * 1.00002); // +0.002%
    ScanResult rb = below.scan();
    ScanResult ra = above.scan();
    if (rb.hasArbitrage()) return TestRunner.Result.fail("+0.0005% reported as arbitrage");
    if (!ra.hasArbitrage() || ra.profitPercent() < 0.001) {
      return TestRunner.Result.fail("+0.002% not reported");
    }
    // eleven USD legs with awkward rates: every cross is derived, so cycles are pure noise
    ArbitrageScanner noise = new ArbitrageScanner(d);
    Rng rng = new Rng(3);
    for (int c = 0; c < d.currencyCount(); c++) {
      if (d.currency(c).equals("USD")) continue;
      noise.onTick("USD/" + d.currency(c), 0.001 + rng.nextInt(1_000_000) / 997.0);
    }
    ScanResult rn = noise.scan();
    if (rn.hasArbitrage() || rn.edges() != 132) {
      return TestRunner.Result.fail("noise: arbitrage=" + rn.hasArbitrage() + " edges="
          + rn.edges());
    }
    return TestRunner.Result.pass("+0.002% -> " + String.join("->", ra.cycle()) + " profit "
        + ra.profitPercent() + "%");
  }

  /**
   * Random graphs: k currencies with USD legs, then up to four direct cross ticks perturbed
   * by up to +/-0.004% or +/-0.4%. A DFS enumerates every simple cycle. Checked:
   * <ul>
   *   <li>any reported cycle is real, its multiplier is its rate product, its profit is at
   *       least the threshold and at most the best cycle's;</li>
   *   <li>the guarantee: if some cycle of k edges earns more than (1+t)^(k/2) - 1, the scan
   *       reports arbitrage;</li>
   *   <li>informational: trials where a cycle earned at least t but the scan stayed silent
   *       (the documented gap between t and the guarantee) are counted, not failed.</li>
   * </ul>
   */
  private static TestRunner.Result scannerBruteForce() {
    Directory d = new Directory();
    Rng rng = new Rng(77);
    double thr = ArbitrageScanner.DEFAULT_MIN_PROFIT_PERCENT;
    double lnGain = Math.log1p(thr / 100.0);
    int reported = 0;
    int aboveThreshold = 0;
    int gap = 0;
    for (int trial = 0; trial < 3_000; trial++) {
      ArbitrageScanner s = new ArbitrageScanner(d);
      int k = 3 + rng.nextInt(4); // non-USD currencies ticked: 3..6
      int[] ccy = new int[k];
      for (int i = 0; i < k; i++) {
        ccy[i] = 1 + i;
        s.onTick(d.currencyId("USD"), ccy[i], 0.2 + rng.nextInt(100_000) / 1_000.0);
      }
      double scale = rng.nextInt(2) == 0 ? 0.00004 : 0.004;
      int crosses = rng.nextInt(5);
      for (int c = 0; c < crosses; c++) {
        int a = ccy[rng.nextInt(k)];
        int b = ccy[rng.nextInt(k)];
        if (a == b) continue;
        double jitter = 1.0 + scale * (rng.nextInt(2_001) - 1_000) / 1_000.0;
        s.onTick(a, b, s.rate(a, b) * jitter);
      }
      double[] stats = cycleStats(s, d.currencyCount(), lnGain);
      double best = stats[0];
      double bestTri = bestTrianglePercent(s, d.currencyCount());
      boolean guaranteed = stats[1] > 1e-9; // some cycle: ln-gain > k/2 * ln(1+t)
      ScanResult r = s.scan();
      if (best >= thr) aboveThreshold++;
      if (r.hasArbitrage()) {
        reported++;
        String p = cycleProblem(s, d, r);
        if (p != null) return TestRunner.Result.fail("trial " + trial + ": " + p);
        if (r.profitPercent() < thr || r.profitPercent() > best + 1e-9) {
          return TestRunner.Result.fail("trial " + trial + ": reported " + r.profitPercent()
              + "% with best possible " + best + "%");
        }
      } else if (guaranteed) {
        return TestRunner.Result.fail("trial " + trial + ": missed a guaranteed cycle (best "
            + best + "%)");
      } else if (best >= thr) {
        gap++; // a cycle of 4+ edges between t and the Bellman-Ford guarantee
      }
      if (bestTri >= thr && (!r.hasArbitrage() || r.profitPercent() < bestTri - 1e-9)) {
        return TestRunner.Result.fail("trial " + trial + ": a " + bestTri + "% triangle was not"
            + " reported (scanner said " + (r.hasArbitrage() ? r.profitPercent() + "%" : "none") + ")");
      }
    }
    return TestRunner.Result.pass(reported + " reported of " + aboveThreshold
        + " trials with a cycle >= " + thr + "%; " + gap + " in the documented gap (cycles of"
        + " 4+ edges; triangles have none)");
  }

  /** Brute force over every ordered triple: the best triangle profit (%) at time 0. */
  static double bestTrianglePercent(ArbitrageScanner s, int n) {
    double best = Double.NEGATIVE_INFINITY;
    for (int a = 0; a < n; a++) {
      for (int b = 0; b < n; b++) {
        for (int c = 0; c < n; c++) {
          if (a == b || b == c || a == c) continue;
          if (!(s.rate(a, b) > 0) || !(s.rate(b, c) > 0) || !(s.rate(c, a) > 0)) continue;
          best = Math.max(best, (s.rate(a, b) * s.rate(b, c) * s.rate(c, a) - 1) * 100);
        }
      }
    }
    return best;
  }

  /**
   * The B8 regime: every cross quoted directly with bid/ask-sized noise around the
   * threshold, so many near-equal triangles straddle it. The scanner must report whenever
   * any triangle reaches the threshold (no gap), at a profit at least that triangle's.
   */
  private static TestRunner.Result scannerTrianglesNoGap() {
    Directory d = new Directory();
    Rng rng = new Rng(2026);
    double thr = ArbitrageScanner.DEFAULT_MIN_PROFIT_PERCENT;
    int withTriangle = 0;
    for (int trial = 0; trial < 5_000; trial++) {
      ArbitrageScanner s = new ArbitrageScanner(d, thr);
      int k = 4 + rng.nextInt(5); // 4..8 non-USD currencies
      int usd = d.currencyId("USD");
      for (int i = 1; i <= k; i++) s.onTick(usd, i, 0.2 + rng.nextInt(100_000) / 1_000.0);
      for (int a = 1; a <= k; a++) {
        for (int b = a + 1; b <= k; b++) {
          if (rng.nextInt(3) == 0) continue; // not every cross is quoted
          double noise = 1.0 + (rng.nextInt(2_001) - 1_000) / 1_000.0 * thr / 100 * 1.5;
          s.onTick(a, b, s.rate(a, b) * noise);
        }
      }
      double bestTri = bestTrianglePercent(s, d.currencyCount());
      ScanResult r = s.scan();
      if (bestTri >= thr) {
        withTriangle++;
        if (!r.hasArbitrage() || r.profitPercent() < bestTri - 1e-9) {
          return TestRunner.Result.fail("trial " + trial + ": best triangle " + bestTri
              + "% but scanner " + (r.hasArbitrage() ? r.profitPercent() + "%" : "none"));
        }
      }
      if (r.hasArbitrage()) {
        String p = cycleProblem(s, d, r);
        if (p != null) return TestRunner.Result.fail("trial " + trial + ": " + p);
      }
    }
    return TestRunner.Result.pass(withTriangle + " of 5000 graphs had a triangle >= "
        + thr + "%; every one reported");
  }

  /**
   * {best profit % over all simple cycles, max over cycles of (ln-gain - k/2 x lnT)}.
   */
  private static double[] cycleStats(ArbitrageScanner s, int n, double lnT) {
    double[] acc = { 0.0, Double.NEGATIVE_INFINITY };
    boolean[] on = new boolean[n];
    for (int start = 0; start < n; start++) {
      on[start] = true;
      dfs(s, n, start, start, 0.0, on, acc, 1, lnT);
      on[start] = false;
    }
    return acc;
  }

  /** Simple cycles whose smallest vertex is {@code start}, accumulating ln of the product. */
  private static void dfs(ArbitrageScanner s, int n, int start, int at, double lnProduct,
      boolean[] on, double[] acc, int len, double lnT) {
    for (int next = start; next < n; next++) {
      double r = s.rate(at, next);
      if (!(r > 0)) continue;
      if (next == start) {
        if (len >= 2) {
          double g = lnProduct + Math.log(r);
          acc[0] = Math.max(acc[0], Math.expm1(g) * 100);
          acc[1] = Math.max(acc[1], g - len / 2.0 * lnT);
        }
      } else if (!on[next]) {
        on[next] = true;
        dfs(s, n, start, next, lnProduct + Math.log(r), on, acc, len + 1, lnT);
        on[next] = false;
      }
    }
  }

  // ---------------------------------------------------------------- demo

  private static TestRunner.Result demoReproducible(Path dir) throws Exception {
    Files.createDirectories(dir);
    String first = runMain(dir, "demo");
    String second = runMain(dir, "demo");
    if (!first.equals(second)) return TestRunner.Result.fail("demo output differs between runs");
    String[] needed = { "conserved=true", "trade records=true", "-> no arbitrage", "ARBITRAGE ",
        "multiplier 1.004000" };
    for (String n : needed) {
      if (!first.contains(n)) return TestRunner.Result.fail("demo output lacks '" + n + "'");
    }
    String trades = first.substring(first.indexOf("\ntrades ") + 1);
    return TestRunner.Result.pass(trades.substring(0, trades.indexOf(',', trades.indexOf("fnv64"))));
  }

  /**
   * Runs {@code ads.Main} in a child JVM with {@code dir} as its working directory and
   * returns its standard output (the JVM's own stderr notices are discarded).
   */
  private static String runMain(Path dir, String... args) throws Exception {
    List<String> cmd = new ArrayList<>();
    cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
    cmd.add("-cp");
    cmd.add(Paths.get("out", "classes").toAbsolutePath().toString());
    cmd.add("ads.Main");
    cmd.addAll(Arrays.asList(args));
    Process p = new ProcessBuilder(cmd).directory(dir.toFile())
        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (InputStream in = p.getInputStream()) {
      in.transferTo(out);
    }
    int code = p.waitFor();
    if (code != 0) throw new IllegalStateException("child JVM exited " + code + ": " + out);
    return out.toString(StandardCharsets.UTF_8);
  }

  private static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root)) return;
    try (Stream<Path> s = Files.walk(root)) {
      for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) {
        Files.delete(p);
      }
    }
  }
}
