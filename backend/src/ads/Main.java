package ads;

import ads.api.ApiServer;
import ads.api.EngineThread;
import ads.api.MarketService;
import ads.directory.Directory;
import ads.directory.TickerTrie;
import ads.engine.BPlusTreeLadder;
import ads.engine.Order;
import ads.engine.OrderIndex;
import ads.engine.PriceLadder;
import ads.engine.PriceLevel;
import ads.engine.Side;
import ads.logbook.HuffmanCodec;
import ads.logbook.Logbook;
import ads.scanner.ArbitrageScanner;
import ads.scanner.BellmanFord;
import ads.vault.Account;
import ads.vault.LruCache;
import ads.vault.Vault;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;

/**
 * Backend entry point (documentation/08_build_plan_and_acceptance.md).
 *
 * <pre>
 *   run.sh demo [--all-trades]   Stage 1 demo: 1,000 seeded orders matched, then an
 *                                artificial triangular arbitrage detected
 *   run.sh accounts [FILE]       load an accounts file in a fresh JVM and print it
 *                                (default data/accounts.jsonl)
 *   run.sh archive [DIR]         decode a Logbook archive and summarise it
 *                                (default data/logbook)
 *   run.sh structures            Stage 0 smoke run of the six structures
 *   run.sh serve [--port N] [--host H] [--min-profit PERCENT]
 *                                Stage 2 HTTP server (default 127.0.0.1:8080):
 *                                POST /api/market/rates, GET /api/market/rates/status;
 *                                the threshold also reads MIN_PROFIT_PERCENT
 *   run.sh threshold-study [FILE]
 *                                replay a capture offline and tabulate thresholds
 * </pre>
 */
public final class Main {
  private Main() {
  }

  public static void main(String[] args) throws IOException {
    String cmd = args.length == 0 ? "" : args[0];
    switch (cmd) {
      case "demo":
        boolean all = args.length > 1 && args[1].equals("--all-trades");
        if (args.length > (all ? 2 : 1)) usage();
        Demo.run(all);
        break;
      case "accounts":
        accounts(Paths.get(args.length > 1 ? args[1] : "data/accounts.jsonl"));
        break;
      case "archive":
        archive(Paths.get(args.length > 1 ? args[1] : "data/logbook"));
        break;
      case "structures":
        structures();
        break;
      case "serve":
        serve(args);
        break;
      case "threshold-study":
        ThresholdStudy.run(Paths.get(args.length > 1 ? args[1] : ThresholdStudy.DEFAULT_CAPTURE));
        break;
      default:
        usage();
    }
  }

  private static void usage() {
    System.err.println("usage: run.sh demo [--all-trades] | accounts [FILE] | archive [DIR]"
        + " | structures | serve [--port N] [--host H] [--min-profit PERCENT]"
        + " | threshold-study [FILE]");
    System.exit(2);
  }

  /** Starts the HTTP server and blocks until the process is stopped. */
  private static void serve(String[] args) throws IOException {
    String host = "127.0.0.1";
    int port = 8080;
    double minProfit = minProfitFromEnv();
    for (int i = 1; i < args.length; i++) {
      if (args[i].equals("--min-profit") && i + 1 < args.length) {
        minProfit = parseMinProfit(args[++i], "--min-profit");
      } else if (args[i].equals("--port") && i + 1 < args.length) {
        port = Integer.parseInt(args[++i]);
      } else if (args[i].equals("--host") && i + 1 < args.length) {
        host = args[++i];
      } else {
        usage();
      }
    }
    EngineThread engine = new EngineThread();
    MarketService market = new MarketService(new ArbitrageScanner(new Directory(), minProfit),
        engine, System::currentTimeMillis);
    ApiServer server = new ApiServer(new InetSocketAddress(host, port));
    ApiServer.marketRoutes(server, engine, market);
    server.start();
    System.out.println("listening on http://" + host + ":" + server.port()
        + "  (POST /api/market/rates, GET /api/market/rates/status)");
    CountDownLatch stopped = new CountDownLatch(1);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      server.stop();
      engine.shutdown();
      stopped.countDown();
    }));
    try {
      stopped.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** MIN_PROFIT_PERCENT from the environment, or the scanner's data-driven default. */
  static double minProfitFromEnv() {
    String env = System.getenv("MIN_PROFIT_PERCENT");
    return env == null || env.isBlank() ? ArbitrageScanner.DEFAULT_MIN_PROFIT_PERCENT
        : parseMinProfit(env, "MIN_PROFIT_PERCENT");
  }

  static double parseMinProfit(String text, String what) {
    try {
      double v = Double.parseDouble(text.trim());
      if (v >= 0 && v < 100) return v;
    } catch (NumberFormatException e) {
      // reported below
    }
    System.err.println(what + " must be a percentage in [0, 100), got: " + text);
    System.exit(2);
    return 0;
  }

  /** Loads the accounts file through a fresh Vault (the restart path) and prints it. */
  private static void accounts(Path file) throws IOException {
    if (!Files.exists(file)) {
      System.err.println("no accounts file at " + file);
      System.exit(1);
    }
    Vault vault = new Vault(file, Vault.DEFAULT_CACHE_CAPACITY);
    long total = 0;
    for (int i = 0; i < vault.size(); i++) {
      Account a = vault.accountAt(i);
      System.out.println("trader " + a.traderId() + "  cash " + a.cash() + "  marginLimit "
          + a.marginLimit());
      total += a.cash();
    }
    System.out.println(vault.size() + " accounts loaded from " + file + ", total cash " + total);
  }

  /** Decodes every archive segment and prints a summary. */
  private static void archive(Path dir) throws IOException {
    byte[] records = Logbook.readArchive(dir);
    int lines = 0;
    for (byte b : records) {
      if (b == '\n') lines++;
    }
    System.out.println(dir + ": " + lines + " trade records, " + records.length
        + " bytes decoded, fnv64=" + Demo.hex(Demo.fnv(records)));
    String text = new String(records, StandardCharsets.US_ASCII);
    int shown = 0;
    for (int i = 0, from = 0; i < text.length() && shown < 3; i++) {
      if (text.charAt(i) == '\n') {
        System.out.println("  " + text.substring(from, i));
        from = i + 1;
        shown++;
      }
    }
  }

  private static void structures() {
    // ladder + order queue + global order index
    PriceLadder asks = new BPlusTreeLadder();
    OrderIndex index = new OrderIndex();
    long[][] resting = { {1, 10_050, 5}, {2, 10_050, 7}, {3, 10_052, 4}, {4, 10_049, 9} };
    for (long[] r : resting) {
      Order o = new Order(r[0], 0, Side.SELL, 1, r[1], r[2], r[0]);
      asks.getOrCreate(r[1]).append(o);
      index.put(o);
    }
    Order cancelled = index.remove(2);
    PriceLevel at = cancelled.level();
    at.unlink(cancelled);
    System.out.println("ladder:  " + asks.size() + " ask levels, best ask " + asks.lowest().price()
        + "; cancelled order 2 at " + at.price() + ", level volume now " + at.totalVolume()
        + ", index holds " + index.size() + " orders");
    PriceLevel[] depth = new PriceLevel[3];
    int k = asks.walkUp(Long.MIN_VALUE, 3, depth);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < k; i++) {
      sb.append(i == 0 ? "" : ", ").append(depth[i].price()).append('x')
          .append(depth[i].totalVolume());
    }
    System.out.println("depth:   " + sb);

    // scanner: USD -> EUR -> GBP -> USD with a mispriced leg
    double[] rates = { 0.90, 0.86, 1.30 }; // product 1.0062 > 1
    int[] from = { 0, 1, 2 };
    int[] to = { 1, 2, 0 };
    double[] w = new double[3];
    for (int i = 0; i < 3; i++) w[i] = -Math.log(rates[i]);
    BellmanFord bf = new BellmanFord();
    bf.build(3, from, to, w);
    boolean found = bf.hasNegativeCycle();
    int[] cycle = bf.reconstructCycle();
    StringBuilder cs = new StringBuilder();
    String[] names = { "USD", "EUR", "GBP" };
    for (int i = 0; i < cycle.length; i++) cs.append(i == 0 ? "" : " -> ").append(names[cycle[i]]);
    System.out.println("scanner: negative cycle " + found + ": " + cs);

    // vault cache
    LruCache<String> cache = new LruCache<>(2, id -> "account-" + id);
    cache.get(1);
    cache.get(2);
    cache.get(1);
    cache.get(3); // evicts 2, the least recently used
    System.out.println("vault:   resident 1=" + cache.contains(1) + " 2=" + cache.contains(2)
        + " 3=" + cache.contains(3));

    // directory trie
    TickerTrie trie = new TickerTrie();
    for (String t : new String[] { "USD/EUR", "USD/JPY", "EUR/USD", "USD/INR" }) trie.insert(t);
    String[] hits = new String[8];
    int n = trie.prefixSearch("USD/", hits);
    sb.setLength(0);
    for (int i = 0; i < n; i++) sb.append(i == 0 ? "" : ", ").append(hits[i]);
    System.out.println("directory: prefix USD/ -> " + sb);

    // logbook codec
    byte[] log = "T1,USD/EUR,10050,5\nT2,USD/EUR,10050,7\nT3,USD/EUR,10052,4\n"
        .getBytes(StandardCharsets.US_ASCII);
    HuffmanCodec codec = new HuffmanCodec();
    byte[] enc = codec.encode(log);
    byte[] dec = codec.decode(enc);
    System.out.println("logbook: " + log.length + " bytes -> " + enc.length
        + " encoded (incl. 260-byte header), round trip identical="
        + new String(dec, StandardCharsets.US_ASCII).equals(
            new String(log, StandardCharsets.US_ASCII)));
  }
}
