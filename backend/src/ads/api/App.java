package ads.api;

import ads.Demo;
import ads.directory.Directory;
import ads.engine.BPlusTreeLadder;
import ads.engine.CancelResult;
import ads.engine.CancelStatus;
import ads.engine.LadderShape;
import ads.engine.MatchingEngine;
import ads.engine.Order;
import ads.engine.OrderBook;
import ads.engine.OrderResult;
import ads.engine.OrderStatus;
import ads.engine.OrderType;
import ads.engine.PriceLadder;
import ads.engine.PriceLevel;
import ads.engine.Side;
import ads.engine.Trade;
import ads.logbook.Logbook;
import ads.scanner.ArbitrageScanner;
import ads.sim.Simulator;
import ads.vault.Account;
import ads.vault.Vault;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The API gateway (doc 08 Stage 3): wires the Directory, Vault, MatchingEngine, Logbook,
 * ArbitrageScanner and MarketService onto one {@link ApiServer} and one
 * {@link EngineThread}.
 *
 * <p>HTTP threads only parse and validate requests (a malformed request is a 400 there);
 * every command and every read of engine state then runs on the engine thread, in arrival
 * order. After each command the engine thread rotates the Logbook if due; every
 * {@link #LOGBOOK_FLUSH_MS} it writes any buffered trades as a segment; every
 * {@link #VAULT_FLUSH_MS} it writes the Vault if anything changed; on shutdown it flushes
 * the Vault and writes the final Logbook segment. The one exception to the engine thread is
 * POST /api/simulate: it runs an isolated engine on a {@link SimulationRunner} worker and
 * never touches the live state.
 *
 * <p>The API speaks decimals and pair names ({@code EUR/USD} in bodies, {@code EUR-USD} in
 * paths, both upper-cased); the engine speaks integer ticks and pair ids. Conversion is
 * exact ({@link Prices}). A well-formed order the engine refuses is HTTP 200 with
 * {@code status: REJECTED} and a reason; order ids are shown as {@code ORD-<n>}.
 */
public final class App {
  public static final long VAULT_FLUSH_MS = 2_000;
  public static final int LOGBOOK_ROTATE_EVERY = 500;
  /** A non-empty Logbook buffer is written at least this often: the crash-loss window. */
  public static final long LOGBOOK_FLUSH_MS = 5_000;
  public static final int DEFAULT_DEPTH = 10;
  public static final int MAX_DEPTH = 50;
  /** Recent trades kept for GET /api/trades. */
  public static final int TRADE_RING = 500;
  public static final int DEFAULT_TRADES = 50;
  private static final Logger LOG = Logger.getLogger(App.class.getName());
  private static final Set<String> ORDER_FIELDS =
      Set.of("traderId", "pair", "side", "type", "price", "quantity");
  private static final Set<String> ACCOUNT_FIELDS = Set.of("traderId", "cash", "marginLimit");
  private static final Set<String> SIMULATE_FIELDS =
      Set.of("numOrders", "seed", "baseline", "scanEvery", "rateVolatility", "profile");

  /** Startup options ({@code run.sh serve ...}). */
  public static final class Config {
    public String host = "127.0.0.1";
    public int port = 8080;
    public Path dataDir = Paths.get("data");
    public boolean seedDemoAccounts;
    public double minProfitPercent = ArbitrageScanner.DEFAULT_MIN_PROFIT_PERCENT;
    /** The built dashboard to serve on non-/api paths (serve --static-dir), or null. */
    public Path staticDir;
    /** How often the Vault and the counters are written while running. */
    public long flushIntervalMs = VAULT_FLUSH_MS;
    /** How often a non-empty Logbook buffer is written as a segment (Stage 6, drill 1). */
    public long logbookFlushMs = LOGBOOK_FLUSH_MS;
    /** Ids reserved on disk ahead of the counters while running (tests shrink it). */
    public long counterReserve = CounterStore.RESERVE;
    /** What POST /api/simulate runs; null for the real {@link Simulator} (tests replace it). */
    public SimulationRunner.Task simulationTask;
  }

  private final Directory directory = new Directory();
  private final EngineThread thread = new EngineThread();
  private final SseHub hub = new SseHub();
  private final Vault vault;
  private final MatchingEngine engine;
  private final Logbook logbook;
  private final MarketService market;
  private final ApiServer server;
  private final SimulationRunner simulations;
  private final long[] lastTradeTick;
  private final CounterStore counters;
  private long countersWrittenAtSeq = -1;
  /** The counter values on disk while running: no id at or past these has been issued. */
  private long reservedOrderId;
  private long reservedTradeId;
  private long reservedSeq;
  private final long flushIntervalMs;
  private final long logbookFlushMs;
  private final Trade[] recentTrades = new Trade[TRADE_RING];
  private int recentNext;
  private int recentCount;
  private final long startedAt = System.currentTimeMillis();

  private App(Config config) throws IOException {
    vault = new Vault(config.dataDir.resolve("accounts.jsonl"), Vault.DEFAULT_CACHE_CAPACITY);
    if (config.seedDemoAccounts && vault.size() == 0) {
      Demo.seedAccounts(vault);
      vault.flush();
    }
    logbook = new Logbook(config.dataDir.resolve("logbook"), directory, LOGBOOK_ROTATE_EVERY);
    engine = new MatchingEngine(directory, vault);
    engine.setTradeSink(logbook);
    counters = new CounterStore(config.dataDir.resolve("counters.json"), config.counterReserve);
    long[] saved = counters.load();
    if (saved != null) {
      engine.restoreCounters(saved[0], saved[1], saved[2]);
    }
    // Reserve before the first command: a crash before the first periodic flush must not
    // let the next session reissue this session's ids.
    writeReservedCounters();
    lastTradeTick = new long[directory.pairCount()];
    Arrays.fill(lastTradeTick, -1);
    market = new MarketService(new ArbitrageScanner(directory, config.minProfitPercent), thread,
        System::currentTimeMillis, hub);
    server = new ApiServer(new InetSocketAddress(config.host, config.port));
    if (config.staticDir != null) server.serveStatic(config.staticDir);
    Simulator simulator = new Simulator(config.minProfitPercent);
    simulations = new SimulationRunner(
        config.simulationTask != null ? config.simulationTask : simulator::run);
    routes();
    flushIntervalMs = config.flushIntervalMs;
    thread.schedule(this::flushVault, flushIntervalMs);
    logbookFlushMs = config.logbookFlushMs;
    thread.schedule(this::flushLogbook, logbookFlushMs);
  }

  /** Builds the gateway and starts serving. */
  public static App start(Config config) throws IOException {
    App app = new App(config);
    app.server.start();
    return app;
  }

  public int port() {
    return server.port();
  }

  /** Stops serving, then flushes the Vault and writes the last Logbook segment. */
  public void stop() {
    server.stop();
    hub.close();
    simulations.shutdown();
    try {
      thread.call(() -> {
        // the same order as every flush (B13): trades, then balances; if the trades cannot be
        // written the balances are not either, so the files on disk stay consistent
        logbook.rotate();
        vault.flush();
        counters.write(engine.nextOrderId(), engine.nextTradeId(), engine.sequence(), true);
        return null;
      });
    } catch (Exception e) {
      LOG.log(Level.WARNING, "final flush failed", e);
    }
    thread.shutdown();
  }

  private void flushVault() {
    try {
      if (vault.dirty()) {
        // trades first: a balance on disk must never include a trade the archive lacks (B13)
        if (logbook.bufferedTrades() > 0) logbook.rotate();
        vault.flush();
      }
      if (engine.sequence() != countersWrittenAtSeq) writeReservedCounters();
    } catch (IOException e) {
      LOG.log(Level.WARNING, "vault/counter flush failed; will retry", e);
    }
    thread.schedule(this::flushVault, flushIntervalMs);
  }

  /**
   * Writes the buffered trades as a segment if there are any, so a crash loses at most the
   * trades of the last {@link Config#logbookFlushMs} (engine thread). Before Stage 6 the
   * buffer was written only every 500 trades, and a quiet session could lose all of them.
   */
  private void flushLogbook() {
    try {
      if (logbook.bufferedTrades() > 0) logbook.rotate();
    } catch (IOException e) {
      LOG.log(Level.WARNING, "logbook flush failed; will retry", e);
    }
    thread.schedule(this::flushLogbook, logbookFlushMs);
  }

  /** Writes the counters plus the reserve (engine thread, or the constructor). */
  private void writeReservedCounters() throws IOException {
    counters.write(engine.nextOrderId(), engine.nextTradeId(), engine.sequence(), false);
    countersWrittenAtSeq = engine.sequence();
    reservedOrderId = engine.nextOrderId() + counters.reserve();
    reservedTradeId = engine.nextTradeId() + counters.reserve();
    reservedSeq = engine.sequence() + counters.reserve();
  }

  /**
   * After each command: if any counter has used half of the reserve on disk, write a new
   * reserve now instead of waiting for the periodic flush, so the reserve is never outrun.
   */
  private void keepReserve() throws IOException {
    long half = counters.reserve() / 2;
    if (engine.nextOrderId() > reservedOrderId - half || engine.nextTradeId() > reservedTradeId - half
        || engine.sequence() > reservedSeq - half) {
      writeReservedCounters();
    }
  }

  // ---------------------------------------------------------------- routes

  private void routes() {
    server.route("POST", "/api/orders", req -> {
      OrderRequest o = OrderRequest.parse(req.jsonExact());
      return thread.call(() -> placeOrder(o));
    });
    server.route("DELETE", "/api/orders/{id}", req -> {
      long id = parseOrderId(req.param("id"));
      return thread.call(() -> cancel(id));
    });
    server.route("GET", "/api/orderbook/{pair}", req -> {
      String pair = normalizePair(req.param("pair"));
      int depth = parseDepth(req.queryParam("depth"));
      return thread.call(() -> book(pair, depth));
    });
    server.route("GET", "/api/arbitrage/scan", req -> thread.call(market::scanView));
    server.route("GET", "/api/arbitrage/graph", req -> thread.call(market::graphView));
    server.route("POST", "/api/market/rates", req -> {
      Object payload = req.json(); // rates are doubles by design; orders never are
      return thread.call(() -> market.ingest(payload));
    });
    server.route("GET", "/api/market/rates/status", req -> thread.call(market::status));
    server.route("GET", "/api/tickers", req -> {
      String prefix = req.queryParam("prefix");
      String p = prefix == null ? "" : normalizePair(prefix);
      return thread.call(() -> tickers(p));
    });
    server.route("GET", "/api/accounts", req -> thread.call(this::accounts));
    server.route("GET", "/api/accounts/{traderId}", req -> {
      long trader = parseTraderId(req.param("traderId"));
      return thread.call(() -> account(trader));
    });
    server.route("POST", "/api/accounts", req -> {
      long[] a = parseAccount(req.jsonExact());
      return thread.call(() -> createAccount(a));
    });
    // isolated engine on its own worker thread: never thread.call, never the live state
    server.route("POST", "/api/simulate", req -> simulations.run(parseSimulate(req)));
    server.route("GET", "/api/simulate/last", req -> simulations.last());
    server.route("GET", "/api/inspector/{pair}", req -> {
      String pair = normalizePair(req.param("pair"));
      return thread.call(() -> inspector(pair));
    });
    server.routeStream("GET", "/api/stream", (ex, req) -> hub.serve(ex));
    server.route("GET", "/api/health", req -> thread.call(this::health));
    server.route("GET", "/api/trades", req -> {
      String pairParam = req.queryParam("pair");
      String pair = pairParam == null ? null : normalizePair(pairParam);
      int limit = parseLimit(req.queryParam("limit"));
      return thread.call(() -> recentTrades(pair, limit));
    });
  }

  // ---------------------------------------------------------------- request parsing

  /** A validated POST /api/orders body (HTTP thread); the engine decides the rest. */
  static final class OrderRequest {
    long traderId;
    String pair;
    Side side;
    OrderType type;
    BigDecimal price; // null for a market order without one
    long quantity;

    static OrderRequest parse(Object body) throws ApiException {
      Map<?, ?> m = object(body, ORDER_FIELDS);
      OrderRequest o = new OrderRequest();
      o.traderId = requiredLong(m, "traderId");
      o.pair = normalizePair(requiredString(m, "pair"));
      o.side = enumValue(Side.class, requiredString(m, "side"), "side", "BUY or SELL");
      o.type = enumValue(OrderType.class, requiredString(m, "type"), "type", "LIMIT or MARKET");
      o.quantity = quantity(m.get("quantity"));
      Object price = m.get("price");
      if (o.type == OrderType.LIMIT && price == null) {
        throw badRequest("price is required for a LIMIT order");
      }
      o.price = price == null ? null : decimal(price, "price");
      return o;
    }
  }

  private static Map<?, ?> object(Object body, Set<String> allowed) throws ApiException {
    if (!(body instanceof Map)) throw badRequest("body must be a JSON object");
    Map<?, ?> m = (Map<?, ?>) body;
    for (Object k : m.keySet()) {
      if (!allowed.contains(k)) throw badRequest("unknown field \"" + k + "\"");
    }
    return m;
  }

  private static long requiredLong(Map<?, ?> m, String field) throws ApiException {
    Object v = m.get(field);
    if (v == null) throw badRequest(field + " is required");
    if (!(v instanceof Long)) throw badRequest(field + " must be an integer");
    return (Long) v;
  }

  private static String requiredString(Map<?, ?> m, String field) throws ApiException {
    Object v = m.get(field);
    if (v == null) throw badRequest(field + " is required");
    if (!(v instanceof String)) throw badRequest(field + " must be a string");
    return (String) v;
  }

  private static <E extends Enum<E>> E enumValue(Class<E> type, String text, String field,
      String expected) throws ApiException {
    try {
      return Enum.valueOf(type, text.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw badRequest(field + " must be " + expected + ", got \"" + text + "\"");
    }
  }

  /** An integer quantity; one too large for a long becomes MAX+1 (engine: BAD_QUANTITY). */
  private static long quantity(Object v) throws ApiException {
    if (v == null) throw badRequest("quantity is required");
    if (v instanceof Long) return (Long) v;
    if (v instanceof BigDecimal) {
      BigDecimal d = (BigDecimal) v;
      if (d.signum() > 0 && d.stripTrailingZeros().scale() <= 0) {
        return MatchingEngine.MAX_QUANTITY + 1; // a whole number beyond a long
      }
    }
    throw badRequest("quantity must be an integer");
  }

  /** A price given as a JSON number or a numeric string, read exactly. */
  private static BigDecimal decimal(Object v, String field) throws ApiException {
    if (v instanceof Long) return BigDecimal.valueOf((Long) v);
    if (v instanceof BigDecimal) return (BigDecimal) v;
    if (v instanceof String) {
      String s = ((String) v).trim();
      if (s.length() <= 64) {
        try {
          return new BigDecimal(s);
        } catch (NumberFormatException e) {
          // reported below
        }
      }
    }
    throw badRequest(field + " must be a decimal number (e.g. 1.08505 or \"1.08505\")");
  }

  /** Upper-cases and turns the path form EUR-USD into EUR/USD. */
  static String normalizePair(String pair) {
    return pair.trim().toUpperCase(Locale.ROOT).replace('-', '/');
  }

  /** "ORD-12", "ord-12" or "12". */
  static long parseOrderId(String text) throws ApiException {
    String t = text.trim();
    if (t.regionMatches(true, 0, "ORD-", 0, 4)) t = t.substring(4);
    long id = positiveLong(t);
    if (id < 0) throw badRequest("order id must be ORD-<n> or <n>, got \"" + text + "\"");
    return id;
  }

  private static long parseTraderId(String text) throws ApiException {
    long id = positiveLong(text.trim());
    if (id < 0) throw badRequest("traderId must be a positive integer, got \"" + text + "\"");
    return id;
  }

  /** A positive decimal integer that fits a long, or -1. */
  private static long positiveLong(String t) {
    if (t.isEmpty() || t.length() > 18) return -1;
    for (int i = 0; i < t.length(); i++) {
      if (t.charAt(i) < '0' || t.charAt(i) > '9') return -1;
    }
    long v = Long.parseLong(t);
    return v > 0 ? v : -1;
  }

  private static int parseDepth(String text) throws ApiException {
    if (text == null) return DEFAULT_DEPTH;
    long d = positiveLong(text.trim());
    if (d < 1 || d > MAX_DEPTH) {
      throw badRequest("depth must be an integer from 1 to " + MAX_DEPTH + ", got \"" + text + "\"");
    }
    return (int) d;
  }

  private static int parseLimit(String text) throws ApiException {
    if (text == null) return DEFAULT_TRADES;
    long n = positiveLong(text.trim());
    if (n < 1 || n > TRADE_RING) {
      throw badRequest("limit must be an integer from 1 to " + TRADE_RING + ", got \"" + text + "\"");
    }
    return (int) n;
  }

  private static long[] parseAccount(Object body) throws ApiException {
    Map<?, ?> m = object(body, ACCOUNT_FIELDS);
    long trader = requiredLong(m, "traderId");
    long cash = requiredLong(m, "cash");
    long margin = requiredLong(m, "marginLimit");
    if (trader <= 0) throw badRequest("traderId must be positive");
    if (cash < 0 || margin < 0) throw badRequest("cash and marginLimit must be >= 0");
    if (cash > Vault.MAX_OPENING_AMOUNT || margin > Vault.MAX_OPENING_AMOUNT) {
      throw badRequest("cash and marginLimit must be at most 1000000000000000 (10^15)");
    }
    return new long[] { trader, cash, margin };
  }

  /** POST /api/simulate: every field optional (an empty body is all defaults). */
  private static Simulator.Params parseSimulate(ApiServer.Request req) throws ApiException {
    Simulator.Params p = new Simulator.Params();
    if (req.bodyIsBlank()) return p;
    Map<?, ?> m = object(req.jsonExact(), SIMULATE_FIELDS);
    if (m.containsKey("numOrders")) {
      p.numOrders = (int) intInRange(m, "numOrders", 1, Simulator.MAX_ORDERS);
    }
    if (m.containsKey("seed")) p.seed = requiredLong(m, "seed");
    if (m.containsKey("baseline")) {
      Object b = m.get("baseline");
      if (!(b instanceof Boolean)) throw badRequest("baseline must be true or false");
      p.baseline = (Boolean) b;
    }
    if (m.containsKey("scanEvery")) {
      p.scanEvery = (int) intInRange(m, "scanEvery", 0, Simulator.MAX_ORDERS);
    }
    if (m.containsKey("rateVolatility")) {
      Object v = m.get("rateVolatility");
      BigDecimal d = v instanceof Long ? BigDecimal.valueOf((Long) v)
          : v instanceof BigDecimal ? (BigDecimal) v : null;
      if (d == null || d.signum() < 0 || d.compareTo(Simulator.MAX_VOLATILITY) > 0) {
        throw badRequest("rateVolatility must be a number from 0 to 1 (percent per step)");
      }
      p.rateVolatility = d;
    }
    if (m.containsKey("profile")) {
      Object v = m.get("profile");
      String prof = v instanceof String ? ((String) v).trim().toLowerCase(Locale.ROOT) : null;
      if (!Simulator.SHALLOW.equals(prof) && !Simulator.DEEP.equals(prof)) {
        throw badRequest("profile must be \"shallow\" or \"deep\"");
      }
      p.profile = prof;
    }
    if (Simulator.DEEP.equals(p.profile) && p.baseline
        && p.numOrders > Simulator.DEEP_BASELINE_MAX_ORDERS) {
      throw badRequest("a deep run with the baseline is capped at "
          + Simulator.DEEP_BASELINE_MAX_ORDERS + " orders (the naive matcher costs O(resting"
          + " orders) per operation); lower numOrders or set baseline to false");
    }
    return p;
  }

  private static long intInRange(Map<?, ?> m, String field, long min, long max)
      throws ApiException {
    Object v = m.get(field);
    if (!(v instanceof Long) || (Long) v < min || (Long) v > max) {
      throw badRequest(field + " must be an integer from " + min + " to " + max);
    }
    return (Long) v;
  }

  private static ApiException badRequest(String message) {
    return new ApiException(400, "BAD_REQUEST", message);
  }

  // ---------------------------------------------------------------- commands (engine thread)

  private Map<String, Object> placeOrder(OrderRequest o) throws IOException {
    int pairId = directory.pairId(o.pair);
    long ticks = 0;
    if (o.type == OrderType.LIMIT && pairId >= 0) {
      ticks = Prices.toTicks(o.price, directory.tickDecimals(pairId));
    }
    OrderResult r = engine.submit(o.traderId, pairId, o.side, o.type, ticks, o.quantity);
    for (int i = 0; i < r.tradeCount(); i++) {
      Trade t = r.trade(i);
      lastTradeTick[t.pairId()] = t.priceTick();
      recentTrades[recentNext] = t;
      recentNext = (recentNext + 1) % TRADE_RING;
      if (recentCount < TRADE_RING) recentCount++;
      hub.publish("TRADE", trade(t));
    }
    if (r.tradeCount() > 0 || r.restingQuantity() > 0) {
      hub.publish("BOOK_UPDATE", book(pairId, DEFAULT_DEPTH));
    }
    logbook.rotateIfDue();
    keepReserve();

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("orderId", orderId(r.orderId()));
    out.put("status", r.status().name());
    out.put("reason", r.rejectReason() == null ? null : r.rejectReason().name());
    out.put("pair", o.pair);
    out.put("side", o.side.name());
    out.put("type", o.type.name());
    Object price = null;
    if (o.type == OrderType.LIMIT) {
      if (pairId >= 0 && ticks > 0 && ticks <= MatchingEngine.MAX_PRICE_TICK) {
        price = Prices.fromTicks(ticks, directory.tickDecimals(pairId));
      } else if (Prices.echoable(o.price)) {
        price = o.price; // as given, for a rejection the client can match up
      }
    }
    out.put("price", price);
    out.put("quantity", o.quantity);
    out.put("filledQuantity", r.filledQuantity());
    out.put("restingQuantity", r.restingQuantity());
    List<Object> trades = new ArrayList<>();
    for (int i = 0; i < r.tradeCount(); i++) trades.add(trade(r.trade(i)));
    out.put("trades", trades);
    out.put("seq", engine.sequence());
    return out;
  }

  private Map<String, Object> cancel(long id) throws ApiException, IOException {
    Order o = engine.order(id);
    CancelResult c = engine.cancel(id);
    if (c.status() == CancelStatus.NOT_FOUND) {
      throw new ApiException(404, "ORDER_NOT_FOUND", orderId(id)
          + " is not resting (unknown, filled or already cancelled)");
    }
    hub.publish("BOOK_UPDATE", book(o.pairId(), DEFAULT_DEPTH));
    logbook.rotateIfDue();
    keepReserve();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("orderId", orderId(id));
    out.put("status", "CANCELLED");
    out.put("pair", directory.pair(o.pairId()));
    out.put("side", o.side().name());
    out.put("price", Prices.fromTicks(o.priceTick(), directory.tickDecimals(o.pairId())));
    out.put("remainingQuantity", c.remainingQuantity());
    out.put("seq", engine.sequence());
    return out;
  }

  private Map<String, Object> book(String pair, int depth) throws ApiException {
    int pairId = directory.pairId(pair);
    if (pairId < 0) throw unknownPair(pair);
    return book(pairId, depth);
  }

  /** The order book snapshot: both sides best-first with cumulative volume. */
  private Map<String, Object> book(int pairId, int depth) {
    OrderBook b = engine.book(pairId);
    int decimals = directory.tickDecimals(pairId);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("pair", directory.pair(pairId));
    out.put("tickSize", Prices.tickSize(decimals));
    out.put("seq", engine.sequence());
    out.put("bids", levels(b, Side.BUY, depth, decimals));
    out.put("asks", levels(b, Side.SELL, depth, decimals));
    out.put("lastTradePrice", lastTradeTick[pairId] < 0 ? null
        : Prices.fromTicks(lastTradeTick[pairId], decimals));
    return out;
  }

  private static List<Object> levels(OrderBook b, Side side, int depth, int decimals) {
    PriceLevel[] buf = new PriceLevel[depth];
    int n = b.depth(side, depth, buf);
    List<Object> out = new ArrayList<>();
    long cumulative = 0;
    for (int i = 0; i < n; i++) {
      cumulative += buf[i].totalVolume();
      Map<String, Object> l = new LinkedHashMap<>();
      l.put("price", Prices.fromTicks(buf[i].price(), decimals));
      l.put("volume", buf[i].totalVolume());
      l.put("orders", buf[i].orderCount());
      l.put("cumulative", cumulative);
      out.add(l);
    }
    return out;
  }

  private Map<String, Object> trade(Trade t) {
    int decimals = directory.tickDecimals(t.pairId());
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("tradeId", t.tradeId());
    m.put("pair", directory.pair(t.pairId()));
    m.put("price", Prices.fromTicks(t.priceTick(), decimals));
    m.put("quantity", t.quantity());
    m.put("buyOrderId", orderId(t.buyOrderId()));
    m.put("sellOrderId", orderId(t.sellOrderId()));
    m.put("buyerId", t.buyerId());
    m.put("sellerId", t.sellerId());
    m.put("takerSide", t.takerSide().name());
    m.put("seq", t.seq());
    return m;
  }

  /** The most recent trades first, optionally for one pair, from the in-memory ring. */
  private Map<String, Object> recentTrades(String pair, int limit) throws ApiException {
    int pairId = -1;
    if (pair != null) {
      pairId = directory.pairId(pair);
      if (pairId < 0) throw unknownPair(pair);
    }
    List<Object> out = new ArrayList<>();
    for (int i = 0; i < recentCount && out.size() < limit; i++) {
      Trade t = recentTrades[(recentNext - 1 - i + TRADE_RING) % TRADE_RING];
      if (pairId < 0 || t.pairId() == pairId) out.add(trade(t));
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("pair", pair == null ? null : directory.pair(pairId));
    m.put("limit", limit);
    m.put("count", out.size());
    m.put("trades", out);
    return m;
  }

  private Map<String, Object> tickers(String prefix) {
    String[] found = new String[directory.pairCount()];
    int n = directory.prefixSearch(prefix, found);
    List<Object> list = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      int id = directory.pairId(found[i]);
      Map<String, Object> t = new LinkedHashMap<>();
      t.put("pair", found[i]);
      t.put("base", directory.currency(directory.baseCurrency(id)));
      t.put("quote", directory.currency(directory.quoteCurrency(id)));
      t.put("tickSize", Prices.tickSize(directory.tickDecimals(id)));
      list.add(t);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("prefix", prefix);
    out.put("count", n);
    out.put("tickers", list);
    return out;
  }

  private Map<String, Object> accounts() {
    List<Object> list = new ArrayList<>();
    for (int i = 0; i < vault.size(); i++) list.add(account(vault.accountAt(i)));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("count", vault.size());
    out.put("accounts", list);
    return out;
  }

  private Map<String, Object> account(long trader) throws ApiException {
    Account a = vault.account(trader);
    if (a == null) throw new ApiException(404, "ACCOUNT_NOT_FOUND", "no account " + trader);
    return account(a);
  }

  private static Map<String, Object> account(Account a) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("traderId", a.traderId());
    m.put("cash", a.cash());
    m.put("marginLimit", a.marginLimit());
    return m;
  }

  private ApiServer.Response createAccount(long[] a) throws ApiException, IOException {
    if (vault.account(a[0]) != null) {
      throw new ApiException(409, "ACCOUNT_EXISTS", "account " + a[0] + " already exists");
    }
    if (vault.size() >= Vault.MAX_ACCOUNTS) {
      throw new ApiException(400, "BAD_REQUEST", "at most " + Vault.MAX_ACCOUNTS + " accounts");
    }
    Account created = vault.open(a[0], a[1], a[2]);
    vault.flush(); // accounts survive a restart from the moment they exist
    return new ApiServer.Response(201, account(created));
  }

  private Map<String, Object> inspector(String pair) throws ApiException {
    int pairId = directory.pairId(pair);
    if (pairId < 0) throw unknownPair(pair);
    OrderBook b = engine.book(pairId);
    int decimals = directory.tickDecimals(pairId);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("pair", directory.pair(pairId));
    out.put("seq", engine.sequence());
    out.put("bids", side(b, Side.BUY, decimals));
    out.put("asks", side(b, Side.SELL, decimals));
    return out;
  }

  /** One side for the inspector: every level (up to MAX_DEPTH) and the ladder's shape. */
  private static Map<String, Object> side(OrderBook b, Side side, int decimals) {
    PriceLadder ladder = b.ladder(side);
    PriceLevel[] buf = new PriceLevel[MAX_DEPTH];
    int n = b.depth(side, MAX_DEPTH, buf);
    List<Object> levels = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      PriceLevel l = buf[i];
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("price", Prices.fromTicks(l.price(), decimals));
      m.put("volume", l.totalVolume());
      m.put("orders", l.orderCount());
      Order head = l.head();
      Map<String, Object> h = new LinkedHashMap<>();
      h.put("orderId", orderId(head.orderId()));
      h.put("traderId", head.traderId());
      h.put("remaining", head.remaining());
      h.put("seq", head.seq());
      m.put("head", h);
      levels.add(m);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("levelCount", ladder.size());
    out.put("levels", levels);
    out.put("truncated", ladder.size() > n);
    if (ladder instanceof BPlusTreeLadder) {
      LadderShape shape = ((BPlusTreeLadder) ladder).describe();
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("structure", "B+ tree");
      s.put("order", shape.order());
      s.put("height", shape.height());
      s.put("internalNodes", shape.internalNodes());
      s.put("leaves", shape.leaves());
      s.put("entries", shape.entries());
      out.put("shape", s);
    } else {
      out.put("shape", null);
    }
    return out;
  }

  private Map<String, Object> health() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("status", "ok");
    out.put("seq", engine.sequence());
    out.put("restingOrders", engine.restingOrders());
    out.put("accounts", vault.size());
    out.put("sseClients", hub.clientCount());
    out.put("sseDropped", hub.droppedClients());
    out.put("uptimeMs", System.currentTimeMillis() - startedAt);
    out.put("hasSimulation", simulations.hasResult()); // additive, Stage 6
    return out;
  }

  private static ApiException unknownPair(String pair) {
    return new ApiException(404, "UNKNOWN_PAIR", "no such pair: " + pair);
  }

  static String orderId(long id) {
    return "ORD-" + id;
  }
}
