package ads.test;

import ads.bench.core.Rng;
import ads.bench.core.Stats;
import ads.directory.Directory;
import ads.scanner.ArbitrageScanner;
import ads.scanner.ScanResult;

/**
 * {@code test.sh --scan-timing}: wall time of one {@link ArbitrageScanner#scan()} at V=7 (the
 * currencies the live feed streams, feed/symbols.json), V=12 (the Directory's universe, which
 * the fallback covers) and V=20 (doc 04's stress size). Each size is timed for a graph with
 * no arbitrage (every cross derived through USD), one with a +0.4% cycle, and one with only a
 * sub-threshold inconsistency (+0.0003% on one direct cross), which still needs the shifted
 * passes. Median of 25 timed batches after a warmup.
 */
final class ScannerTiming {
  private static final String[] EXTRA = { "SEK", "NOK", "DKK", "PLN", "MXN", "ZAR", "TRY", "BRL" };
  /** The seven currencies of the 21 pairs the live feed subscribes to (feed/symbols.json). */
  private static final String[] LIVE = { "USD", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF" };

  private ScannerTiming() {
  }

  static void run() {
    System.out.println();
    System.out.println("== scanner timing (median per scan, " + System.getProperty("java.vm.name")
        + " " + System.getProperty("java.runtime.version") + ") ==");
    System.out.println(String.format("%-4s %-16s %8s %12s %s", "V", "case", "edges", "us/scan",
        "result"));
    String[] v12 = universe(0);
    String[] v20 = universe(EXTRA.length);
    for (String[] ccy : new String[][] { LIVE, v12, v20 }) {
      time(ccy, "no-arbitrage", 0.0);
      time(ccy, "arbitrage", 0.004);
      time(ccy, "sub-threshold", 0.000003);
    }
  }

  private static String[] universe(int extra) {
    Directory d = new Directory();
    String[] out = new String[d.currencyCount() + extra];
    for (int i = 0; i < d.currencyCount(); i++) out[i] = d.currency(i);
    System.arraycopy(EXTRA, 0, out, d.currencyCount(), extra);
    return out;
  }

  /** Every non-USD currency gets a USD leg; then EUR/GBP is ticked at (1 + bump) x derived. */
  static ArbitrageScanner graph(String[] ccy, double bump) {
    ArbitrageScanner s = new ArbitrageScanner(ccy, ArbitrageScanner.DEFAULT_MIN_PROFIT_PERCENT);
    Rng rng = new Rng(12);
    int usd = s.currencyId("USD");
    for (int c = 0; c < ccy.length; c++) {
      if (c != usd) s.onTick(usd, c, 0.05 + rng.nextInt(200_000) / 1_000.0);
    }
    if (bump != 0.0) {
      int eur = s.currencyId("EUR");
      int gbp = s.currencyId("GBP");
      s.onTick(eur, gbp, s.rate(eur, gbp) * (1.0 + bump));
    }
    return s;
  }

  private static volatile int sink;

  private static void time(String[] ccy, String label, double bump) {
    ArbitrageScanner s = graph(ccy, bump);
    ScanResult r = s.scan();
    int batch = 200;
    for (int i = 0; i < 20_000; i++) sink ^= s.scan().edges();
    long[] t = new long[25];
    for (int rep = 0; rep < t.length; rep++) {
      long t0 = System.nanoTime();
      for (int i = 0; i < batch; i++) sink ^= s.scan().edges();
      t[rep] = (System.nanoTime() - t0) / batch;
    }
    String result = r.hasArbitrage() ? String.join("->", r.cycle()) + " +"
        + String.format("%.4f", r.profitPercent()) + "%"
        : r.belowThreshold() ? "none (sub-threshold cycle seen)" : "none";
    System.out.println(String.format("%-4d %-16s %8d %12.2f %s", ccy.length, label, r.edges(),
        Stats.median(t) / 1_000.0, result));
  }
}
