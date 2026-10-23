package ads;

import ads.api.MarketService;
import ads.directory.Directory;
import ads.scanner.ArbitrageScanner;
import ads.util.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code run.sh threshold-study [FILE]} (doc 08 Stage 2 addendum, item 3): replays a recorded
 * Finnhub capture through the scanner offline, in recorded time, exactly as the server
 * would see it (each live quote usable for 30 s, scans throttled to one per 500 ms), and
 * reports for each candidate threshold the share of scans that report a cycle, plus the
 * distribution of the best-cycle profit found by enumerating every simple cycle of the
 * usable graph. The recommended default is the smallest threshold for which fewer than 1%
 * of scans report a cycle.
 */
final class ThresholdStudy {
  static final double[] THRESHOLDS = { 0.001, 0.005, 0.01, 0.02, 0.05 };
  static final String DEFAULT_CAPTURE = "../feed/samples/finnhub_ticks_2026-10-23.jsonl";

  private ThresholdStudy() {
  }

  static void run(Path capture) throws IOException {
    Directory directory = new Directory();
    ArbitrageScanner[] scanners = new ArbitrageScanner[THRESHOLDS.length];
    for (int i = 0; i < scanners.length; i++) {
      scanners[i] = new ArbitrageScanner(directory, THRESHOLDS[i]);
    }
    ArbitrageScanner rates = new ArbitrageScanner(directory, 0.0); // for the exhaustive search
    int[] reported = new int[THRESHOLDS.length];
    List<Double> best = new ArrayList<>();
    long lastScanTs = Long.MIN_VALUE / 2;
    long ticks = 0;
    long first = -1;
    long last = -1;
    try (BufferedReader in = Files.newBufferedReader(capture, StandardCharsets.UTF_8)) {
      String line;
      while ((line = in.readLine()) != null) {
        if (line.isBlank()) continue;
        Object msg;
        try {
          msg = Json.parse(line);
        } catch (Json.JsonException e) {
          continue;
        }
        Object data = msg instanceof Map ? ((Map<?, ?>) msg).get("data") : null;
        if (!(data instanceof List)) continue;
        for (Object d : (List<?>) data) {
          if (!(d instanceof Map)) continue;
          Map<?, ?> t = (Map<?, ?>) d;
          if (!(t.get("s") instanceof String) || !(t.get("p") instanceof Number)
              || !(t.get("t") instanceof Long)) {
            continue;
          }
          String pair = MarketService.pairOf((String) t.get("s"));
          double price = ((Number) t.get("p")).doubleValue();
          long ts = (Long) t.get("t");
          if (pair == null || !(price > 0)) continue;
          long validUntil = ts + MarketService.STALE_AFTER_MS;
          if (!rates.onTick(pair, price, validUntil)) continue;
          for (ArbitrageScanner s : scanners) s.onTick(pair, price, validUntil);
          ticks++;
          if (first < 0) first = ts;
          last = ts;
          if (ts - lastScanTs >= MarketService.SCAN_THROTTLE_MS) {
            lastScanTs = ts;
            for (int i = 0; i < scanners.length; i++) {
              if (scanners[i].scan(ts).hasArbitrage()) reported[i]++;
            }
            best.add(bestCycleProfitPercent(rates, ts));
          }
        }
      }
    }
    int scans = best.size();
    if (scans == 0) {
      System.out.println("no usable ticks in " + capture);
      return;
    }
    Collections.sort(best);
    System.out.println("capture: " + capture + " (" + ticks + " ticks over "
        + (last - first) / 1000 + " s, " + scans + " throttled scans)");
    System.out.println(String.format(Locale.ROOT,
        "best-cycle profit per scan (exhaustive): p50 %.5f%%  p90 %.5f%%  p99 %.5f%%  max %.5f%%",
        pct(best, 50), pct(best, 90), pct(best, 99), best.get(scans - 1)));
    System.out.println("threshold   scanner reports a cycle      exhaustive: some cycle >= threshold");
    double chosen = Double.NaN;
    for (int i = 0; i < THRESHOLDS.length; i++) {
      double share = 100.0 * reported[i] / scans;
      int exact = 0;
      for (double b : best) {
        if (b >= THRESHOLDS[i]) exact++;
      }
      System.out.println(String.format(Locale.ROOT, "%7.3f%%   %5d / %d  (%6.2f%%)      %5d / %d  (%6.2f%%)",
          THRESHOLDS[i], reported[i], scans, share, exact, scans, 100.0 * exact / scans));
      if (Double.isNaN(chosen) && share < 1.0) chosen = THRESHOLDS[i];
    }
    System.out.println(Double.isNaN(chosen)
        ? "recommended default: none of the candidates keeps reports under 1% of scans"
        : String.format(Locale.ROOT, "recommended default: %.3f%% (smallest threshold with"
            + " fewer than 1%% of scans reporting a cycle)", chosen));
  }

  /** Nearest-rank percentile of a sorted list. */
  private static double pct(List<Double> sorted, int p) {
    int rank = (int) Math.ceil(p / 100.0 * sorted.size());
    return sorted.get(Math.max(0, rank - 1));
  }

  /** The best profit (%) over every simple cycle of the graph usable at {@code now}. */
  static double bestCycleProfitPercent(ArbitrageScanner s, long now) {
    int n = s.currencyCount();
    double[] best = { Double.NEGATIVE_INFINITY };
    boolean[] on = new boolean[n];
    for (int start = 0; start < n; start++) {
      on[start] = true;
      dfs(s, now, n, start, start, 0.0, on, best, 1);
      on[start] = false;
    }
    return best[0] == Double.NEGATIVE_INFINITY ? 0.0 : Math.expm1(best[0]) * 100;
  }

  private static void dfs(ArbitrageScanner s, long now, int n, int start, int at, double lnProduct,
      boolean[] on, double[] best, int len) {
    for (int next = start; next < n; next++) {
      if (!s.usable(at, next, now)) continue;
      double g = lnProduct + Math.log(s.rate(at, next));
      if (next == start) {
        if (len >= 2 && g > best[0]) best[0] = g;
      } else if (!on[next]) {
        on[next] = true;
        dfs(s, now, n, start, next, g, on, best, len + 1);
        on[next] = false;
      }
    }
  }
}
