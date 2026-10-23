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
 * <p><b>Staleness</b> (doc 06 §5, doc 08 Stage 2 addendum): a live quote may be used for
 * {@link #STALE_AFTER_MS} after it arrives, a fallback snapshot's quotes for
 * {@link #FALLBACK_VALID_MS}. Expired quotes, and crosses derived from them, are left out
 * of the scan (the scan and status report how many edges were excluded), and a sweep
 * re-scans as soon as one expires, so a feed that stops makes its alerts stop.
 *
 * <p><b>Sources never mix.</b> When a payload brings rates from a different source than
 * the ones in the store (live to fallback or back), the store is cleared first.
 */
public final class MarketService {
  public static final long SCAN_THROTTLE_MS = 500;
  public static final long STALE_AFTER_MS = 30_000;
  /** No finnhub-ws payload (ticks or heartbeat) for this long: the feed is not connected. */
  public static final long FEED_SILENT_MS = 15_000;
  /** A fallback snapshot counts as current for two of the feed's hourly polls. */
  public static final long FALLBACK_VALID_MS = 2 * 3_600_000;
  /** Minimum gap between two RATE_TICK events for the same pair. */
  public static final long RATE_TICK_MIN_GAP_MS = 250;
  /** How often expired quotes are checked for (a re-scan runs only if one expired). */
  public static final long SWEEP_MS = 1_000;
  private static final int MAX_ERRORS_REPORTED = 5;
  private static final String OANDA = "OANDA:";

  private final ArbitrageScanner scanner;
  private final Scheduler scheduler;
  private final LongSupplier clock;
  private final EventSink events;
  private final Map<String, Long> lastRateTickEvent = new LinkedHashMap<>(); // pair -> ms

  private final Map<String, Long> quoteValidUntil = new LinkedHashMap<>(); // pair -> ms
  private String ratesSource; // the source of every rate currently in the store
  private long sourceSwitches;
  private long nextExpiry = Long.MAX_VALUE;
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
    this(scanner, scheduler, clock, EventSink.NONE);
  }

  public MarketService(ArbitrageScanner scanner, Scheduler scheduler, LongSupplier clock,
      EventSink events) {
    this.scanner = scanner;
    this.scheduler = scheduler;
    this.clock = clock;
    this.events = events;
    scheduler.schedule(this::sweep, SWEEP_MS);
  }

  /** Applies one POST /api/market/rates body; returns the response body. */
  public Map<String, Object> ingest(Object body) throws ApiException {
    if (!(body instanceof Map)) throw bad("body must be a JSON object");
    Map<?, ?> m = (Map<?, ?>) body;
    Object source = m.get("source");
    long now = clock.getAsLong();
    List<String> errors = new ArrayList<>();
    List<Object[]> quotes = new ArrayList<>(); // {pair, rate, ts or null}, validated first
    int rejected = 0;
    long validFor;
    boolean replay = false;
    String asOf = null;
    if ("finnhub-ws".equals(source)) {
      Object ticks = m.get("ticks");
      if (!(ticks instanceof List)) throw bad("finnhub-ws payload needs a \"ticks\" array");
      Object replayField = m.get("replay");
      if (replayField != null && !(replayField instanceof Boolean)) {
        throw bad("\"replay\" must be a boolean");
      }
      replay = Boolean.TRUE.equals(replayField);
      for (Object t : (List<?>) ticks) {
        String problem = parseTick(t, quotes);
        if (problem != null) {
          rejected++;
          if (errors.size() < MAX_ERRORS_REPORTED) errors.add(problem);
        }
      }
      validFor = STALE_AFTER_MS;
    } else if ("frankfurter-fallback".equals(source)) {
      if (!"USD".equals(m.get("base"))) throw bad("frankfurter-fallback payload needs base \"USD\"");
      Object rates = m.get("rates");
      if (!(rates instanceof Map)) throw bad("frankfurter-fallback payload needs a \"rates\" object");
      Object asOfField = m.get("asOf");
      if (asOfField != null && !(asOfField instanceof String)) throw bad("\"asOf\" must be a string");
      asOf = (String) asOfField;
      for (Map.Entry<?, ?> e : ((Map<?, ?>) rates).entrySet()) {
        String code = String.valueOf(e.getKey());
        if (code.equals("USD") || scanner.currencyId(code) < 0) continue; // outside the universe
        double rate = positiveNumber(e.getValue());
        if (Double.isNaN(rate)) {
          rejected++;
          if (errors.size() < MAX_ERRORS_REPORTED) errors.add("rate for " + code + " is not a positive number");
          continue;
        }
        quotes.add(new Object[] { "USD/" + code, rate, null });
      }
      validFor = FALLBACK_VALID_MS;
    } else {
      throw bad("unknown source " + quoted(source)
          + " (expected \"finnhub-ws\" or \"frankfurter-fallback\")");
    }
    // a different source bringing rates: start the graph afresh, never mix the two
    if (!quotes.isEmpty() && !source.equals(ratesSource)) {
      if (ratesSource != null) sourceSwitches++;
      scanner.clear();
      quoteValidUntil.clear();
      ratesSource = (String) source;
    }
    for (Object[] q : quotes) {
      String pair = (String) q[0];
      scanner.onTick(pair, (Double) q[1], now + validFor);
      touch(pair, now + validFor);
      rateTickEvent(pair, (Double) q[1], (Long) q[2], (String) source, now);
      if (q[2] != null) {
        lastTickReceivedAt = now;
        lastTickTs = Math.max(lastTickTs, (Long) q[2]);
      }
    }
    if ("frankfurter-fallback".equals(source)) {
      fallbackAsOf = asOf;
    }
    lastReplay = replay;
    payloads++;
    lastSource = (String) source;
    lastPayloadAt = now;
    tickCount += quotes.size();
    rejectedTicks += rejected;
    if (!quotes.isEmpty()) requestScan(now);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("accepted", quotes.size());
    out.put("rejected", rejected);
    out.put("errors", errors);
    return out;
  }

  /** Validates one finnhub-ws tick into {@code out}; returns null, or why it was rejected. */
  private String parseTick(Object t, List<Object[]> out) {
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
    if (pair == null || !knownPair(pair)) return sym + ": unknown symbol";
    out.add(new Object[] { pair, price, ts });
    return null;
  }

  /** RATE_TICK, at most one per pair per {@link #RATE_TICK_MIN_GAP_MS} (4 per second). */
  private void rateTickEvent(String pair, double rate, Long ts, String source, long now) {
    Long last = lastRateTickEvent.get(pair);
    if (last != null && now - last < RATE_TICK_MIN_GAP_MS) return;
    lastRateTickEvent.put(pair, now);
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("pair", pair);
    e.put("rate", rate);
    e.put("ts", ts);
    e.put("source", source);
    events.publish("RATE_TICK", e);
  }

  private boolean knownPair(String pair) {
    int a = scanner.currencyId(pair.substring(0, 3));
    int b = scanner.currencyId(pair.substring(4));
    return a >= 0 && b >= 0 && a != b;
  }

  /**
   * Records an update for a currency pair in either orientation: a fallback "USD/EUR" rate
   * refreshes the live "EUR/USD" entry rather than adding a second one, so pairs covered by
   * the fallback are not reported stale (doc 06 §5).
   */
  private void touch(String pair, long validUntil) {
    String reverse = pair.substring(4) + "/" + pair.substring(0, 3);
    quoteValidUntil.put(quoteValidUntil.containsKey(reverse) ? reverse : pair, validUntil);
  }

  /** "OANDA:EUR_USD" -> "EUR/USD", or null if the symbol is not in that form. */
  public static String pairOf(String symbol) {
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
    long now = clock.getAsLong();
    boolean wasActive = lastScan != null && lastScan.hasArbitrage();
    lastScan = scanner.scan(now);
    lastScanAt = now;
    nextExpiry = scanner.nextExpiryAfter(now);
    scanCount++;
    if (lastScan.hasArbitrage() != wasActive) { // newly found or lost: not every scan
      Map<String, Object> e = scanView();
      e.put("active", lastScan.hasArbitrage());
      events.publish("LOOPHOLE_ALERT", e);
    }
  }

  /** The latest scan, as GET /api/arbitrage/scan reports it. */
  public Map<String, Object> scanView() {
    Map<String, Object> out = new LinkedHashMap<>();
    boolean has = lastScan != null && lastScan.hasArbitrage();
    out.put("hasArbitrage", has);
    out.put("cycle", lastScan == null ? new String[0] : lastScan.cycle());
    out.put("multiplier", lastScan == null ? 1.0 : lastScan.multiplier());
    out.put("profitPercent", lastScan == null ? 0.0 : lastScan.profitPercent());
    out.put("minProfitPercent", scanner.minProfitPercent());
    out.put("edges", lastScan == null ? 0 : lastScan.edges());
    out.put("excludedEdges", lastScan == null ? 0 : lastScan.excludedEdges());
    out.put("belowThreshold", lastScan != null && lastScan.belowThreshold());
    out.put("scanCount", scanCount);
    out.put("scannedAt", scanCount == 0 ? null : Instant.ofEpochMilli(lastScanAt).toString());
    return out;
  }

  /**
   * The currency graph, as GET /api/arbitrage/graph reports it: every currency, every known
   * rate with whether it is stale now and whether it was quoted directly, and the current
   * cycle (null if none).
   */
  public Map<String, Object> graphView() {
    long now = clock.getAsLong();
    int n = scanner.currencyCount();
    boolean[] active = new boolean[n];
    List<Object> edges = new ArrayList<>();
    for (int a = 0; a < n; a++) {
      for (int b = 0; b < n; b++) {
        if (a == b || !(scanner.rate(a, b) > 0)) continue;
        active[a] = true;
        active[b] = true;
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("from", scanner.currency(a));
        e.put("to", scanner.currency(b));
        e.put("rate", scanner.rate(a, b));
        e.put("stale", !scanner.usable(a, b, now));
        e.put("direct", scanner.isDirect(a, b));
        edges.add(e);
      }
    }
    List<Object> nodes = new ArrayList<>();
    for (int c = 0; c < n; c++) {
      Map<String, Object> node = new LinkedHashMap<>();
      node.put("id", scanner.currency(c));
      node.put("active", active[c]);
      nodes.add(node);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("source", ratesSource);
    out.put("nodes", nodes);
    out.put("edges", edges);
    out.put("cycle", lastScan != null && lastScan.hasArbitrage() ? lastScan.cycle() : null);
    out.put("scannedAt", scanCount == 0 ? null : Instant.ofEpochMilli(lastScanAt).toString());
    return out;
  }

  /**
   * Every {@link #SWEEP_MS}: once some quote has expired since the last scan, scan again so
   * stale edges leave the graph and an alert from a dead feed goes away.
   */
  private void sweep() {
    if (clock.getAsLong() >= nextExpiry) {
      runScan();
    }
    scheduler.schedule(this::sweep, SWEEP_MS);
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
      source = quoteValidUntil.isEmpty() ? "none" : "cached";
    }
    List<String> stale = new ArrayList<>();
    for (Map.Entry<String, Long> e : quoteValidUntil.entrySet()) {
      if (now > e.getValue()) stale.add(e.getKey());
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
    out.put("pairsSeen", quoteValidUntil.size());
    out.put("staleSymbols", stale);
    out.put("fallbackAsOf", fallbackAsOf);
    out.put("scanCount", scanCount);
    out.put("lastScanAt", scanCount == 0 ? null : Instant.ofEpochMilli(lastScanAt).toString());
    out.put("lastScanFoundCycle", lastScan != null && lastScan.hasArbitrage());
    out.put("excludedEdges", lastScan == null ? 0 : lastScan.excludedEdges());
    out.put("minProfitPercent", scanner.minProfitPercent());
    out.put("ratesSource", ratesSource);
    out.put("sourceSwitches", sourceSwitches);
    if (lastScan == null) {
      out.put("lastScan", null);
    } else {
      Map<String, Object> scan = new LinkedHashMap<>();
      scan.put("hasArbitrage", lastScan.hasArbitrage());
      scan.put("cycle", lastScan.cycle());
      scan.put("multiplier", lastScan.multiplier());
      scan.put("profitPercent", lastScan.profitPercent());
      scan.put("edges", lastScan.edges());
      scan.put("excludedEdges", lastScan.excludedEdges());
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
