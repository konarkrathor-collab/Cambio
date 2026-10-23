package ads;

import ads.directory.Directory;
import ads.engine.CancelResult;
import ads.engine.CancelStatus;
import ads.engine.MatchingEngine;
import ads.engine.OrderBook;
import ads.engine.OrderResult;
import ads.engine.OrderStatus;
import ads.engine.OrderType;
import ads.engine.PriceLevel;
import ads.engine.RejectReason;
import ads.engine.Side;
import ads.engine.Trade;
import ads.logbook.Logbook;
import ads.scanner.ArbitrageScanner;
import ads.scanner.ScanResult;
import ads.util.SplitMix64;
import ads.vault.Vault;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * {@code run.sh demo} (doc 08 Stage 1). Matches 1,000 seeded orders on EUR/USD and USD/JPY
 * through the engine, Vault and Logbook, then loads the captured Frankfurter snapshot into
 * the scanner, injects one mispriced cross rate and prints the detected cycle.
 *
 * <p>Reproducible: the order stream comes from a fixed seed, nothing reads the clock, and
 * the demo's own state under {@code data/demo/} is reset at the start of every run, so two
 * runs print identical output. It never touches {@code data/accounts.jsonl}.
 */
final class Demo {
  private static final long SEED = 42;
  private static final int ORDERS = 1_000;
  private static final int TRADERS = 12;
  private static final String[] PAIRS = { "EUR/USD", "USD/JPY" };
  private static final long[] MID = { 108_500, 158_250 }; // 1.08500 and 158.250 in ticks
  private static final int BAND = 10;                    // limit prices within mid +/- 10 ticks
  private static final int ROTATE_EVERY = 200;

  /** USD-based Frankfurter rates captured 2026-10-09 (benchmark/data). */
  private static final String[] SNAPSHOT_CCY = { "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "INR" };
  private static final double[] SNAPSHOT_RATE =
      { 0.89238, 0.75641, 158.25, 1.4324, 1.4226, 0.83107, 96.73 };

  private Demo() {
  }

  static void run(boolean allTrades) throws IOException {
    Path root = Paths.get("data", "demo");
    Path accountsFile = root.resolve("accounts.jsonl");
    Path logDir = root.resolve("logbook");
    reset(accountsFile, logDir);

    Directory directory = new Directory();
    Vault vault = new Vault(accountsFile, Vault.DEFAULT_CACHE_CAPACITY);
    for (long t = 1; t <= TRADERS; t++) {
      // trader 12 is small on purpose, so the margin check visibly rejects some orders
      if (t == TRADERS) vault.open(t, 2_000_000L, 2_000_000L);
      else vault.open(t, 5_000_000_000L, 3_000_000_000L);
    }
    long cashBefore = totalCash(vault);
    Logbook logbook = new Logbook(logDir, directory, ROTATE_EVERY);
    MatchingEngine engine = new MatchingEngine(directory, vault);
    engine.setTradeSink(logbook);

    System.out.println("== Stage 1 demo: " + ORDERS + " seeded orders (seed " + SEED + ") on "
        + PAIRS[0] + " and " + PAIRS[1] + ", " + TRADERS + " traders ==");
    SplitMix64 rng = new SplitMix64(SEED);
    StringBuilder records = new StringBuilder();
    int[] statusCount = new int[OrderStatus.values().length];
    int[] rejectCount = new int[RejectReason.values().length];
    int cancelled = 0;
    int cancelMissed = 0;
    int trades = 0;
    long lastOrderId = 0;
    long[] recentResting = new long[32]; // ring of recently rested order ids: cancel targets
    int restingSeen = 0;
    for (int i = 0; i < ORDERS; i++) {
      int roll = rng.nextInt(100);
      if (roll < 12 && lastOrderId > 0) {
        // mostly a recently rested order (often still live), sometimes any id ever issued
        long target = roll < 9 && restingSeen > 0
            ? recentResting[rng.nextInt(Math.min(restingSeen, recentResting.length))]
            : 1 + rng.nextInt((int) lastOrderId);
        CancelResult c = engine.cancel(target);
        if (c.status() == CancelStatus.CANCELLED) cancelled++;
        else cancelMissed++;
        continue;
      }
      OrderResult r;
      long trader = 1 + rng.nextInt(TRADERS);
      Side side = rng.nextInt(2) == 0 ? Side.BUY : Side.SELL;
      if (roll < 14) { // malformed input, one of three kinds
        int kind = rng.nextInt(3);
        r = kind == 0 ? engine.submit(trader, "EUR/XYZ", side, OrderType.LIMIT, MID[0], 10)
            : kind == 1 ? engine.submit(trader, PAIRS[0], side, OrderType.LIMIT, MID[0], 0)
            : engine.submit(trader, PAIRS[0], side, OrderType.LIMIT, 0, 10);
      } else {
        int p = rng.nextInt(PAIRS.length);
        OrderType type = roll < 28 ? OrderType.MARKET : OrderType.LIMIT;
        long price = MID[p] + rng.nextInt(2 * BAND + 1) - BAND;
        long qty = 1 + rng.nextInt(50);
        r = engine.submit(trader, PAIRS[p], side, type, price, qty);
      }
      lastOrderId = r.orderId();
      if (r.restingQuantity() > 0) {
        recentResting[restingSeen++ % recentResting.length] = r.orderId();
      }
      statusCount[r.status().ordinal()]++;
      if (r.rejectReason() != null) rejectCount[r.rejectReason().ordinal()]++;
      for (int k = 0; k < r.tradeCount(); k++) {
        Trade t = r.trade(k);
        trades++;
        int before = records.length();
        Logbook.appendRecord(records, t, directory);
        if (allTrades || trades <= 15) {
          System.out.print("  trade " + records.substring(before));
        } else if (trades == 16) {
          System.out.println("  ... (run.sh demo --all-trades prints every trade)");
        }
      }
      logbook.rotateIfDue(); // file I/O between commands, never inside a match
    }
    logbook.rotate();
    vault.flush();
    System.out.println("  record format: tradeId,seq,pair,priceTick,qty,buyOrderId,"
        + "sellOrderId,buyerId,sellerId,takerSide");

    System.out.println();
    System.out.println("orders submitted " + lastOrderId + ", cancels " + (cancelled + cancelMissed)
        + " (" + cancelled + " CANCELLED, " + cancelMissed + " NOT_FOUND)");
    StringBuilder sb = new StringBuilder("status:");
    for (OrderStatus s : OrderStatus.values()) {
      sb.append(' ').append(s.name()).append('=').append(statusCount[s.ordinal()]);
    }
    System.out.println(sb);
    sb.setLength(0);
    sb.append("rejects:");
    for (RejectReason s : RejectReason.values()) {
      sb.append(' ').append(s.name()).append('=').append(rejectCount[s.ordinal()]);
    }
    System.out.println(sb);
    byte[] expected = records.toString().getBytes(StandardCharsets.US_ASCII);
    System.out.println("trades " + trades + ", trade-list fnv64=" + hex(fnv(expected))
        + ", resting orders " + engine.restingOrders() + ", engine seq " + engine.sequence());
    for (int p = 0; p < PAIRS.length; p++) {
      printBook(PAIRS[p], engine.book(directory.pairId(PAIRS[p])));
    }

    long cashAfter = totalCash(vault);
    System.out.println("vault: " + vault.size() + " accounts written to " + accountsFile
        + "; total cash " + cashAfter + " (before " + cashBefore + ", conserved="
        + (cashAfter == cashBefore) + ")");
    byte[] archived = Logbook.readArchive(logDir);
    System.out.println("logbook: " + logbook.segments() + " Huffman segments in " + logDir
        + ", " + archived.length + " bytes decoded, byte-identical to the " + trades
        + " trade records=" + sameBytes(archived, expected));

    System.out.println();
    System.out.println("== arbitrage scan ==");
    ArbitrageScanner scanner = new ArbitrageScanner(directory);
    for (int i = 0; i < SNAPSHOT_CCY.length; i++) {
      scanner.onTick("USD/" + SNAPSHOT_CCY[i], SNAPSHOT_RATE[i]);
    }
    ScanResult clean = scanner.scan();
    System.out.println("Frankfurter 2026-10-09 snapshot: " + SNAPSHOT_CCY.length
        + " USD legs ticked, crosses derived through USD, " + clean.edges() + " edges -> "
        + describe(clean));
    int eur = directory.currencyId("EUR");
    int gbp = directory.currencyId("GBP");
    double derived = scanner.rate(eur, gbp);
    double injected = derived * 1.004;
    scanner.onTick("EUR/GBP", injected);
    System.out.println("inject EUR/GBP tick " + fixed(injected, 6) + " (derived cross was "
        + fixed(derived, 6) + ", +0.4%)");
    System.out.println("scan -> " + describe(scanner.scan()));
  }

  private static String describe(ScanResult r) {
    if (!r.hasArbitrage()) {
      return "no arbitrage" + (r.belowThreshold() ? " (a cycle below the 0.001% threshold)" : "");
    }
    StringBuilder sb = new StringBuilder("ARBITRAGE ");
    String[] c = r.cycle();
    for (int i = 0; i < c.length; i++) sb.append(i == 0 ? "" : " -> ").append(c[i]);
    return sb.append("  multiplier ").append(fixed(r.multiplier(), 6))
        .append("  profit ").append(fixed(r.profitPercent(), 4)).append('%').toString();
  }

  private static void printBook(String pair, OrderBook book) {
    PriceLevel[] levels = new PriceLevel[3];
    StringBuilder sb = new StringBuilder("book " + pair + "  bids:");
    int n = book.depth(Side.BUY, 3, levels);
    for (int i = 0; i < n; i++) {
      sb.append(' ').append(levels[i].price()).append('x').append(levels[i].totalVolume());
    }
    sb.append("  asks:");
    n = book.depth(Side.SELL, 3, levels);
    for (int i = 0; i < n; i++) {
      sb.append(' ').append(levels[i].price()).append('x').append(levels[i].totalVolume());
    }
    System.out.println(sb);
  }

  /** Deletes only the demo's own files, so every run starts from the same state. */
  private static void reset(Path accountsFile, Path logDir) throws IOException {
    Files.deleteIfExists(accountsFile);
    if (Files.isDirectory(logDir)) {
      try (DirectoryStream<Path> files = Files.newDirectoryStream(logDir, "trades-*")) {
        for (Path p : files) Files.delete(p);
      }
    }
  }

  private static long totalCash(Vault vault) {
    long total = 0;
    for (int i = 0; i < vault.size(); i++) total += vault.accountAt(i).cash();
    return total;
  }

  private static boolean sameBytes(byte[] a, byte[] b) {
    if (a.length != b.length) return false;
    for (int i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
  }

  /** Locale-independent fixed-point formatting, so output is identical on every machine. */
  private static String fixed(double v, int places) {
    return new BigDecimal(v).setScale(places, RoundingMode.HALF_EVEN).toPlainString();
  }

  static long fnv(byte[] data) {
    long h = 0xcbf29ce484222325L;
    for (byte b : data) h = (h ^ (b & 0xFF)) * 0x100000001b3L;
    return h;
  }

  static String hex(long v) {
    return "0x" + Long.toHexString(v);
  }
}
