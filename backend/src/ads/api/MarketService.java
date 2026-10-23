package ads.api;

import ads.scanner.ArbitrageScanner;
import ads.scanner.ScanResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The market-data side of the backend (doc 06, doc 08 Stage 2): applies feed payloads to
 * the scanner's rate store, runs the throttled arbitrage scan, and answers the status
 * endpoint. Confined to the engine thread: every method must be called there.
 *
 * <p><b>Payloads</b> (Appendix B). {@code finnhub-ws}: {@code ticks} of
 * {@code {"symbol":"OANDA:EUR_USD","price":1.0852,"ts":ms}}; an empty list is a heartbeat
 * (the feed is connected but no ticks are flowing, e.g. at weekends). An optional
 * {@code "replay": true} marks a recorded capture, so a replay is never presented as the
 * live feed. {@code frankfurter-fallback}: {@code base} "USD" and {@code rates} per currency;
 * currencies outside the universe are ignored. Bad ticks are counted and reported, never
 * fatal; a payload of the wrong shape is a 400.
 *
 * <p><b>Scans</b> run at most once per {@link #SCAN_THROTTLE_MS}: the first change after a
 * quiet spell scans at once, and changes inside the window schedule one trailing scan at
 * the window's end, so the newest rates are always scanned.
 *
 * <p><b>Staleness</b> (doc 06 §5): a directly ticked pair is stale once it has not been
 * updated for {@link #STALE_AFTER_MS}.
 */
public final class MarketService {
  public static final long SCAN_THROTTLE_MS = 500;
  public static final long STALE_AFTER_MS = 30_000;
  /** No finnhub-ws payload (ticks or heartbeat) for this long: the feed is not connected. */
  public static final long FEED_SILENT_MS = 15_000;
  /** A fallback snapshot counts as current for two of the feed's hourly polls. */
  public static final long FALLBACK_VALID_MS = 2 * 3_600_000;
  private static final int MAX_ERRORS_REPORTED = 5;
  private static final String OANDA = "OANDA:";

  private final ArbitrageScanner scanner;
  private final Scheduler scheduler;
  private final LongSupplier clock;

  private final Map<String, Long> lastUpdate = new LinkedHashMap<>(); // pair -> receive ms
  private String lastSource;
  private boolean lastReplay;
  private long lastPayloadAt;
  private long lastTickReceivedAt = -1;
  private long lastTickTs = -1;
  private String fallbackAsOf;
  private long payloads;
  private long tickCount;
  private long rejectedTicks;
  private long scanCount;
  private long lastScanAt = Long.MIN_VALUE / 2;
  private boolean scanPending;
  private ScanResult lastScan;

  public MarketService(ArbitrageScanner scanner, Scheduler scheduler, LongSupplier clock) {
    this.scanner = scanner;
    this.scheduler = scheduler;
    this.clock = clock;
  }

  /** Applies one POST /api/market/rates body; returns the response body. */
  public Map<String, Object> ingest(Object body) throws ApiException {
    if (!(body instanceof Map)) throw bad("body must be a JSON object");
    Map<?, ?> m = (Map<?, ?>) body;
    Object source = m.get("source");
    long now = clock.getAsLong();
    List<String> errors = new ArrayList<>();
    int accepted;
    int rejected = 0;
    if ("finnhub-ws".equals(source)) {
      Object ticks = m.get("ticks");
      if (!(ticks instanceof List)) throw bad("finnhub-ws payload needs a \"ticks\" array");
      Object replay = m.get("replay");
      if (replay != null && !(replay instanceof Boolean)) throw bad("\"replay\" must be a boolean");
      accepted = 0;
      for (Object t : (List<?>) ticks) {
        String problem = applyTick(t, now);
        if (problem == null) {
          accepted++;
        } else {
          rejected++;
          if (errors.size() < MAX_ERRORS_REPORTED) errors.add(problem);
        }
      }
      lastReplay = Boolean.TRUE.equals(replay);
    } else if ("frankfurter-fallback".equals(source)) {
      if (!"USD".equals(m.get("base"))) throw bad("frankfurter-fallback payload needs base \"USD\"");
      Object rates = m.get("rates");
      if (!(rates instanceof Map)) throw bad("frankfurter-fallback payload needs a \"rates\" object");
      Object asOf = m.get("asOf");
      if (asOf != null && !(asOf instanceof String)) throw bad("\"asOf\" must be a string");
      accepted = 0;
      for (Map.Entry<?, ?> e : ((Map<?, ?>) rates).entrySet()) {
        String code = String.valueOf(e.getKey());
        if (code.equals("USD") || scanner.currencyId(code) < 0) continue; // outside the universe
        double rate = positiveNumber(e.getValue());
        if (Double.isNaN(rate)) {
          rejected++;
          if (errors.size() < MAX_ERRORS_REPORTED) errors.add("rate for " + code + " is not a positive number");
          continue;
        }
        scanner.onTick("USD/" + code, rate);
        touch("USD/" + code, now);
        accepted++;
      }
      fallbackAsOf = (String) asOf;
      lastReplay = false;
    } else {
      throw bad("unknown source " + quoted(source)
          + " (expected \"finnhub-ws\" or \"frankfurter-fallback\")");
    }
    payloads++;
    lastSource = (String) source;
    lastPayloadAt = now;
    tickCount += accepted;
    rejectedTicks += rejected;
    if (accepted > 0) requestScan(now);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("accepted", accepted);
    out.put("rejected", rejected);
    out.put("errors", errors);
    return out;
  }

  /** Applies one finnhub-ws tick; returns null, or why it was rejected. */
  private String applyTick(Object t, long now) {
    if (!(t instanceof Map)) return "tick is not an object";
    Map<?, ?> tick = (Map<?, ?>) t;
    Object symbol = tick.get("symbol");
    if (!(symbol instanceof String)) return "tick has no symbol";
    String sym = (String) symbol;
    double price = positiveNumber(tick.get("price"));
    if (Double.isNaN(price)) return sym + ": price is not a positive number";
    Object ts = tick.get("ts");
    if (!(ts instanceof Long) || (Long) ts <= 0) return sym + ": ts is not a positive integer";
    String pair = pairOf(sym);
    if (pair == null || !scanner.onTick(pair, price)) return sym + ": unknown symbol";
    touch(pair, now);
    lastTickReceivedAt = now;
    lastTickTs = Math.max(lastTickTs, (Long) ts);
    return null;
  }

  /**
   * Records an update for a currency pair in either orientation: a fallback "USD/EUR" rate
   * refreshes the live "EUR/USD" entry rather than adding a second one, so pairs covered by
   * the fallback are not reported stale (doc 06 §5).
   */
  private void touch(String pair, long now) {
    String reverse = pair.substring(4) + "/" + pair.substring(0, 3);
    lastUpdate.put(lastUpdate.containsKey(reverse) ? reverse : pair, now);
  }

  /** "OANDA:EUR_USD" -> "EUR/USD", or null if the symbol is not in that form. */
  static String pairOf(String symbol) {
    if (!symbol.startsWith(OANDA) || symbol.length() != OANDA.length() + 7) return null;
    String p = symbol.substring(OANDA.length());
    if (p.charAt(3) != '_') return null;
    return p.substring(0, 3) + "/" + p.substring(4);
  }

  private static double positiveNumber(Object v) {
    if (!(v instanceof Number)) return Double.NaN;
    double d = ((Number) v).doubleValue();
    return d > 0 && !Double.isInfinite(d) ? d : Double.NaN;
  }

  // ---------------------------------------------------------------- throttled scan

  private void requestScan(long now) {
    if (now - lastScanAt >= SCAN_THROTTLE_MS) {
      runScan();
    } else if (!scanPending) {
      scanPending = true;
      scheduler.schedule(this::runScan, lastScanAt + SCAN_THROTTLE_MS - now);
    }
  }

  /** Runs a scan now (also the scheduled trailing scan). */
  void runScan() {
    scanPending = false;
    lastScan = scanner.scan();
    lastScanAt = clock.getAsLong();
    scanCount++;
  }

  // ---------------------------------------------------------------- status

  /** The GET /api/market/rates/status body. */
  public Map<String, Object> status() {
    long now = clock.getAsLong();
    String state;
    String source;
    boolean connected = false;
    if (lastSource == null) {
      state = "waiting";
      source = "none";
    } else if (lastSource.equals("finnhub-ws") && now - lastPayloadAt <= FEED_SILENT_MS) {
      connected = true;
      source = "finnhub-ws";
      state = lastTickReceivedAt >= 0 && now - lastTickReceivedAt <= STALE_AFTER_MS ? "live"
          : "connected, no ticks";
    } else if (lastSource.equals("frankfurter-fallback")
        && now - lastPayloadAt <= FALLBACK_VALID_MS) {
      state = "fallback";
      source = "frankfurter-fallback";
    } else {
      state = "disconnected";
      source = lastUpdate.isEmpty() ? "none" : "cached";
    }
    List<String> stale = new ArrayList<>();
    for (Map.Entry<String, Long> e : lastUpdate.entrySet()) {
      if (now - e.getValue() > STALE_AFTER_MS) stale.add(e.getKey());
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("state", state);
    out.put("source", source);
    out.put("replay", lastSource != null && lastReplay);
    out.put("connected", connected);
    out.put("lastTickAt", lastTickTs < 0 ? null : Instant.ofEpochMilli(lastTickTs).toString());
    out.put("lastPayloadAt", lastSource == null ? null : Instant.ofEpochMilli(lastPayloadAt).toString());
    out.put("tickCount", tickCount);
    out.put("rejectedTicks", rejectedTicks);
    out.put("payloads", payloads);
    out.put("pairsSeen", lastUpdate.size());
    out.put("staleSymbols", stale);
    out.put("fallbackAsOf", fallbackAsOf);
    out.put("scanCount", scanCount);
    out.put("lastScanAt", scanCount == 0 ? null : Instant.ofEpochMilli(lastScanAt).toString());
    out.put("lastScanFoundCycle", lastScan != null && lastScan.hasArbitrage());
    if (lastScan == null) {
      out.put("lastScan", null);
    } else {
      Map<String, Object> scan = new LinkedHashMap<>();
      scan.put("hasArbitrage", lastScan.hasArbitrage());
      scan.put("cycle", lastScan.cycle());
      scan.put("multiplier", lastScan.multiplier());
      scan.put("profitPercent", lastScan.profitPercent());
      scan.put("edges", lastScan.edges());
      scan.put("belowThreshold", lastScan.belowThreshold());
      out.put("lastScan", scan);
    }
    return out;
  }

  public long scanCount() {
    return scanCount;
  }

  public long tickCount() {
    return tickCount;
  }

  private static ApiException bad(String message) {
    return new ApiException(400, "BAD_PAYLOAD", message);
  }

  private static String quoted(Object v) {
    return v instanceof String ? "\"" + v + "\"" : String.valueOf(v);
  }
}
