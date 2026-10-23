package ads;

import ads.directory.TickerTrie;
import ads.engine.BPlusTreeLadder;
import ads.engine.Order;
import ads.engine.OrderIndex;
import ads.engine.PriceLadder;
import ads.engine.PriceLevel;
import ads.logbook.HuffmanCodec;
import ads.scanner.BellmanFord;
import ads.vault.LruCache;

import java.nio.charset.StandardCharsets;

/**
 * Backend entry point. Stage 0 ships the six data structures only; {@code structures}
 * (the default) exercises each once and prints what it did. The matching demo and the API
 * server arrive in later stages (documentation/08_build_plan_and_acceptance.md).
 */
public final class Main {
  private Main() {
  }

  public static void main(String[] args) {
    String cmd = args.length == 0 ? "structures" : args[0];
    if (!cmd.equals("structures")) {
      System.err.println("usage: run.sh [structures]");
      System.exit(2);
    }
    structures();
  }

  private static void structures() {
    // ladder + order queue + global order index
    PriceLadder asks = new BPlusTreeLadder();
    OrderIndex index = new OrderIndex();
    long[][] resting = { {1, 10_050, 5}, {2, 10_050, 7}, {3, 10_052, 4}, {4, 10_049, 9} };
    for (long[] r : resting) {
      Order o = new Order(r[0], r[2]);
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
