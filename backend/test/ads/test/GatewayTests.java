package ads.test;

import ads.api.App;
import ads.api.Prices;
import ads.api.SseHub;
import ads.logbook.Logbook;
import ads.util.Json;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Stage 3 HTTP-level tests: the full gateway on an ephemeral port. */
final class GatewayTests {
  private GatewayTests() {
  }

  static void run(TestRunner r) {
    r.section("gateway: prices at the boundary");
    r.check("Prices: exact decimal <-> tick conversion, never through double", GatewayTests::prices);

    r.section("gateway: HTTP (real App, ephemeral port, temp data dir)");
    r.check("a crossing pair of orders trades at the resting price and moves both balances",
        () -> withApp(GatewayTests::crossing));
    r.check("cancel: ORD-<n> and <n>; second cancel 404; malformed id 400",
        () -> withApp(GatewayTests::cancel));
    r.check("every 400 path (bad JSON, missing field, wrong type, unknown enum/field, depth)",
        () -> withApp(GatewayTests::badRequests));
    r.check("404 / 405 / 409 / 501 paths", () -> withApp(GatewayTests::otherErrors));
    r.check("REJECTED with each reason is HTTP 200, precedence kept through the API",
        () -> withApp(GatewayTests::rejections));
    r.check("price conversion over HTTP: exact, trailing zeros, strings, JPY, non-multiples,"
        + " absurd sizes", () -> withApp(GatewayTests::priceConversion));
    r.check("upper-casing and hyphenated paths; tickers carry tickSize",
        () -> withApp(GatewayTests::casing));
    r.check("inspector, graph, scan and health views", () -> withApp(GatewayTests::views));
    r.check("CORS: only the Vite origins; preflight 204 / 403", () -> withApp(GatewayTests::cors));
    r.check("SSE: retry/id fields, then BOOK_UPDATE, TRADE, LOOPHOLE_ALERT in order",
        () -> withApp(GatewayTests::sseOrder));
    r.check("SSE: a client that never reads does not stall 3000 orders; others still served",
        () -> withApp(GatewayTests::sseSlowClient));
    r.check("SSE hub: a client whose socket blocks is dropped after a full queue; publish never"
        + " blocks", GatewayTests::hubDropsBlockedClient);
    r.check("200 concurrent orders from 8 threads equal a serial replay of the same arrival"
        + " order", GatewayTests::concurrentEqualsSerial);
    r.check("accounts and the logbook survive a restart through the API",
        GatewayTests::restart);
  }

  // ---------------------------------------------------------------- harness

  private interface AppBody {
    TestRunner.Result run(Client c) throws Exception;
  }

  static final class Client {
    final String base;
    final App app;
    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    Client(App app) {
      this.app = app;
      this.base = "http://127.0.0.1:" + app.port();
    }

    HttpResponse<String> send(String method, String path, String body, String... headers)
        throws Exception {
      HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
          .timeout(java.time.Duration.ofSeconds(20));
      for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
      b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
          : HttpRequest.BodyPublishers.ofString(body));
      return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    Map<?, ?> json(String method, String path, String body, int wantStatus) throws Exception {
      HttpResponse<String> r = send(method, path, body);
      if (r.statusCode() != wantStatus) {
        throw new AssertionError(method + " " + path + " -> " + r.statusCode() + " " + r.body()
            + " (wanted " + wantStatus + ")");
      }
      return (Map<?, ?>) Json.parseExact(r.body());
    }

    Map<?, ?> order(long trader, String pair, String side, String type, String price, long qty)
        throws Exception {
      return json("POST", "/api/orders", "{\"traderId\":" + trader + ",\"pair\":\"" + pair
          + "\",\"side\":\"" + side + "\",\"type\":\"" + type + "\""
          + (price == null ? "" : ",\"price\":" + price) + ",\"quantity\":" + qty + "}", 200);
    }

    long cash(long trader) throws Exception {
      return (Long) json("GET", "/api/accounts/" + trader, null, 200).get("cash");
    }

    String errorCode(HttpResponse<String> r) throws Exception {
      Map<?, ?> e = (Map<?, ?>) ((Map<?, ?>) Json.parse(r.body())).get("error");
      return r.statusCode() + " " + e.get("code");
    }
  }

  static App startApp(Path dataDir) throws Exception {
    App.Config c = new App.Config();
    c.port = 0;
    c.dataDir = dataDir;
    c.seedDemoAccounts = true;
    return App.start(c);
  }

  private static TestRunner.Result withApp(AppBody body) throws Exception {
    Path dir = Files.createTempDirectory("ads-gw-");
    App app = startApp(dir);
    try {
      return body.run(new Client(app));
    } catch (AssertionError e) {
      return TestRunner.Result.fail(e.getMessage());
    } finally {
      app.stop();
      deleteTree(dir);
    }
  }

  private static void check(boolean ok, String what) {
    if (!ok) throw new AssertionError(what);
  }

  private static BigDecimal dec(Object v) {
    return v instanceof BigDecimal ? (BigDecimal) v : BigDecimal.valueOf((Long) v);
  }

  private static boolean same(Object v, String decimal) {
    return v != null && dec(v).compareTo(new BigDecimal(decimal)) == 0;
  }

  // ---------------------------------------------------------------- tests

  private static TestRunner.Result prices() {
    long max = ads.engine.MatchingEngine.MAX_PRICE_TICK;
    boolean ok = Prices.toTicks(new BigDecimal("1.08505"), 5) == 108505
        && Prices.toTicks(new BigDecimal("1.085"), 5) == 108500
        && Prices.toTicks(new BigDecimal("1.0850500000"), 5) == 108505
        && Prices.toTicks(new BigDecimal("158.255"), 3) == 158255
        && Prices.toTicks(new BigDecimal("1.085051"), 5) == 0
        && Prices.toTicks(new BigDecimal("0"), 5) == 0
        && Prices.toTicks(new BigDecimal("-1.08"), 5) == 0
        && Prices.toTicks(new BigDecimal("1e30"), 5) == max + 1
        && Prices.toTicks(new BigDecimal("1e999999999"), 5) == max + 1
        && Prices.toTicks(new BigDecimal("1e-999999999"), 5) == 0
        && Prices.toTicks(new BigDecimal("10000"), 5) == 1_000_000_000L
        && Prices.toTicks(new BigDecimal("10000.00001"), 5) == max + 1
        && Prices.fromTicks(108500, 5).toPlainString().equals("1.08500")
        && Prices.tickSize(3).toPlainString().equals("0.001");
    return ok ? TestRunner.Result.pass(null) : TestRunner.Result.fail("conversion table");
  }

  private static TestRunner.Result crossing(Client c) throws Exception {
    long s0 = c.cash(1);
    long b0 = c.cash(2);
    Map<?, ?> sell = c.order(1, "EUR/USD", "SELL", "LIMIT", "1.08510", 100);
    Map<?, ?> buy = c.order(2, "EUR/USD", "BUY", "LIMIT", "1.08520", 60);
    check("QUEUED".equals(sell.get("status")) && sell.get("orderId").equals("ORD-1"), "sell " + sell);
    check("FILLED".equals(buy.get("status")) && buy.get("filledQuantity").equals(60L), "buy " + buy);
    List<?> trades = (List<?>) buy.get("trades");
    Map<?, ?> t = (Map<?, ?>) trades.get(0);
    check(trades.size() == 1 && same(t.get("price"), "1.08510") && t.get("quantity").equals(60L)
        && "ORD-2".equals(t.get("buyOrderId")) && "ORD-1".equals(t.get("sellOrderId"))
        && "BUY".equals(t.get("takerSide")), "trade " + t);
    long notional = 108_510L * 60;
    check(c.cash(1) == s0 + notional && c.cash(2) == b0 - notional, "balances");
    Map<?, ?> book = c.json("GET", "/api/orderbook/EUR-USD", null, 200);
    Map<?, ?> ask = (Map<?, ?>) ((List<?>) book.get("asks")).get(0);
    check(ask.get("volume").equals(40L) && same(book.get("lastTradePrice"), "1.08510"), "book " + book);
    return TestRunner.Result.pass("1 trade of 60 @ 1.08510, notional " + notional + " moved");
  }

  private static TestRunner.Result cancel(Client c) throws Exception {
    c.order(1, "EUR/USD", "BUY", "LIMIT", "1.08000", 50);
    c.order(1, "EUR/USD", "BUY", "LIMIT", "1.07990", 20);
    c.order(3, "EUR/USD", "SELL", "LIMIT", "1.08000", 15); // partially fills ORD-1
    Map<?, ?> a = c.json("DELETE", "/api/orders/ORD-1", null, 200);
    Map<?, ?> b = c.json("DELETE", "/api/orders/2", null, 200);
    check("CANCELLED".equals(a.get("status")) && a.get("remainingQuantity").equals(35L)
        && same(a.get("price"), "1.08000") && "BUY".equals(a.get("side")), "cancel 1 " + a);
    check(b.get("remainingQuantity").equals(20L) && "ORD-2".equals(b.get("orderId")), "cancel 2 " + b);
    check(c.errorCode(c.send("DELETE", "/api/orders/ORD-1", null)).equals("404 ORDER_NOT_FOUND"), "again");
    check(c.errorCode(c.send("DELETE", "/api/orders/ORD-3", null)).equals("404 ORDER_NOT_FOUND"), "filled");
    for (String bad : new String[] { "abc", "ORD-", "ORD-0", "-5", "1.5", "ORD-99999999999999999999" }) {
      check(c.errorCode(c.send("DELETE", "/api/orders/" + bad, null)).equals("400 BAD_REQUEST"), bad);
    }
    Map<?, ?> book = c.json("GET", "/api/orderbook/EUR-USD", null, 200);
    check(((List<?>) book.get("bids")).isEmpty(), "bids left " + book);
    return TestRunner.Result.pass(null);
  }

  private static TestRunner.Result badRequests(Client c) throws Exception {
    String ok = "\"traderId\":1,\"pair\":\"EUR/USD\",\"side\":\"BUY\",\"type\":\"LIMIT\","
        + "\"price\":1.085,\"quantity\":10";
    String[][] cases = {
        { "{not json", "400 BAD_JSON" }, { "", "400 BAD_JSON" }, { "[1]", "400 BAD_REQUEST" },
        { "{\"pair\":\"EUR/USD\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":1.085,\"quantity\":10}", "400 BAD_REQUEST" },
        { "{\"traderId\":1,\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":1.085,\"quantity\":10}", "400 BAD_REQUEST" },
        { "{\"traderId\":1,\"pair\":\"EUR/USD\",\"type\":\"LIMIT\",\"price\":1.085,\"quantity\":10}", "400 BAD_REQUEST" },
        { "{\"traderId\":1,\"pair\":\"EUR/USD\",\"side\":\"BUY\",\"price\":1.085,\"quantity\":10}", "400 BAD_REQUEST" },
        { "{\"traderId\":1,\"pair\":\"EUR/USD\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":1.085}", "400 BAD_REQUEST" },
        { "{\"traderId\":1,\"pair\":\"EUR/USD\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":10}", "400 BAD_REQUEST" },
        { "{" + ok.replace("\"traderId\":1", "\"traderId\":\"1\"") + "}", "400 BAD_REQUEST" },
        { "{" + ok.replace("\"quantity\":10", "\"quantity\":1.5") + "}", "400 BAD_REQUEST" },
        { "{" + ok.replace("\"quantity\":10", "\"quantity\":\"10\"") + "}", "400 BAD_REQUEST" },
        { "{" + ok.replace("\"price\":1.085", "\"price\":true") + "}", "400 BAD_REQUEST" },
        { "{" + ok.replace("\"price\":1.085", "\"price\":\"abc\"") + "}", "400 BAD_REQUEST" },
        { "{" + ok.replace("\"pair\":\"EUR/USD\"", "\"pair\":7") + "}", "400 BAD_REQUEST" },
        { "{" + ok.replace("\"BUY\"", "\"HOLD\"") + "}", "400 BAD_REQUEST" },
        { "{" + ok.replace("\"LIMIT\"", "\"STOP\"") + "}", "400 BAD_REQUEST" },
        { "{" + ok + ",\"qty\":5}", "400 BAD_REQUEST" },
    };
    for (String[] cs : cases) {
      String got = c.errorCode(c.send("POST", "/api/orders", cs[0]));
      check(got.equals(cs[1]), cs[0] + " -> " + got);
    }
    for (String q : new String[] { "0", "51", "abc", "-1", "" }) {
      check(c.errorCode(c.send("GET", "/api/orderbook/EUR-USD?depth=" + q, null)).equals("400 BAD_REQUEST"),
          "depth " + q);
    }
    String[] badAccounts = { "{\"traderId\":500,\"cash\":10}", "{\"traderId\":0,\"cash\":1,\"marginLimit\":1}",
        "{\"traderId\":500,\"cash\":-1,\"marginLimit\":1}", "{\"traderId\":500,\"cash\":1.5,\"marginLimit\":1}",
        "{\"traderId\":500,\"cash\":1,\"marginLimit\":1,\"x\":1}" };
    for (String a : badAccounts) {
      check(c.errorCode(c.send("POST", "/api/accounts", a)).equals("400 BAD_REQUEST"), a);
    }
    check(c.errorCode(c.send("GET", "/api/accounts/abc", null)).equals("400 BAD_REQUEST"), "trader id");
    check(c.json("GET", "/api/health", null, 200).get("status").equals("ok"), "still alive");
    return TestRunner.Result.pass((cases.length + 11) + " malformed requests -> 400");
  }

  private static TestRunner.Result otherErrors(Client c) throws Exception {
    check(c.errorCode(c.send("GET", "/api/nope", null)).equals("404 NOT_FOUND"), "route");
    check(c.errorCode(c.send("GET", "/api/accounts/777", null)).equals("404 ACCOUNT_NOT_FOUND"), "acct");
    check(c.errorCode(c.send("GET", "/api/orderbook/EUR-XXX", null)).equals("404 UNKNOWN_PAIR"), "book");
    check(c.errorCode(c.send("GET", "/api/inspector/XXX-YYY", null)).equals("404 UNKNOWN_PAIR"), "insp");
    check(c.errorCode(c.send("DELETE", "/api/orders/424242", null)).equals("404 ORDER_NOT_FOUND"), "cancel");
    HttpResponse<String> m = c.send("PUT", "/api/orders", "{}");
    check(c.errorCode(m).equals("405 METHOD_NOT_ALLOWED")
        && m.headers().firstValue("Allow").orElse("").equals("POST"), "405 " + m.headers().map());
    c.json("POST", "/api/accounts", "{\"traderId\":500,\"cash\":1000,\"marginLimit\":900}", 201);
    check(c.errorCode(c.send("POST", "/api/accounts", "{\"traderId\":500,\"cash\":1,\"marginLimit\":1}"))
        .equals("409 ACCOUNT_EXISTS"), "409");
    check(c.errorCode(c.send("POST", "/api/simulate", "{}")).equals("501 NOT_IMPLEMENTED"), "501");
    return TestRunner.Result.pass(null);
  }

  private static TestRunner.Result rejections(Client c) throws Exception {
    c.order(1, "EUR/USD", "SELL", "LIMIT", "1.09000", 10);
    String[][] cases = { // trader, pair, side, type, price, qty, reason
        { "1", "EUR/XXX", "BUY", "LIMIT", "1.08", "10", "UNKNOWN_PAIR" },
        { "1", "EUR/XXX", "BUY", "LIMIT", "1.085051", "0", "UNKNOWN_PAIR" },
        { "1", "EUR/USD", "BUY", "LIMIT", "1.08", "0", "BAD_QUANTITY" },
        { "1", "EUR/USD", "BUY", "LIMIT", "1.085051", "0", "BAD_QUANTITY" },
        { "1", "EUR/USD", "BUY", "LIMIT", "1.085051", "10", "BAD_PRICE" },
        { "1", "EUR/USD", "BUY", "LIMIT", "0", "10", "BAD_PRICE" },
        { "1", "EUR/USD", "BUY", "LIMIT", "1e30", "10", "BAD_PRICE" },
        { "999", "EUR/USD", "BUY", "LIMIT", "1.085051", "10", "BAD_PRICE" },
        { "1", "EUR/USD", "SELL", "MARKET", null, "10", "NO_LIQUIDITY" },
        { "999", "EUR/USD", "SELL", "MARKET", null, "10", "NO_LIQUIDITY" },
        { "999", "EUR/USD", "BUY", "LIMIT", "1.08", "10", "MARGIN" },
        { "12", "EUR/USD", "BUY", "LIMIT", "1.08", "1000", "MARGIN" },
        { "12", "EUR/USD", "BUY", "MARKET", null, "1000", "MARGIN" },
    };
    for (String[] cs : cases) {
      Map<?, ?> r = c.order(Long.parseLong(cs[0]), cs[1], cs[2], cs[3], cs[4], Long.parseLong(cs[5]));
      check("REJECTED".equals(r.get("status")) && cs[6].equals(r.get("reason"))
          && ((String) r.get("orderId")).startsWith("ORD-"), String.join(",", cs) + " -> " + r);
    }
    return TestRunner.Result.pass(cases.length + " rejections, HTTP 200 each");
  }

  private static TestRunner.Result priceConversion(Client c) throws Exception {
    Map<?, ?> a = c.order(1, "EUR/USD", "SELL", "LIMIT", "1.08505", 10);
    Map<?, ?> b = c.order(1, "EUR/USD", "SELL", "LIMIT", "1.085", 10);
    Map<?, ?> s = c.order(1, "EUR/USD", "SELL", "LIMIT", "\"1.0850700\"", 10);
    Map<?, ?> e = c.order(1, "EUR/USD", "SELL", "LIMIT", "1.0851e0", 10);
    Map<?, ?> j = c.order(1, "USD/JPY", "SELL", "LIMIT", "158.255", 10);
    Map<?, ?> jb = c.order(1, "USD/JPY", "SELL", "LIMIT", "158.2555", 10);
    Map<?, ?> huge = c.order(1, "EUR/USD", "SELL", "LIMIT", "1e999999", 10);
    check(same(a.get("price"), "1.08505") && "QUEUED".equals(a.get("status")), "exact " + a);
    check(b.get("price").toString().equals("1.08500"), "normalised " + b);
    check(same(s.get("price"), "1.08507") && "QUEUED".equals(s.get("status")), "string " + s);
    check(same(e.get("price"), "1.08510"), "exponent " + e);
    check(same(j.get("price"), "158.255") && "QUEUED".equals(j.get("status")), "jpy " + j);
    check("BAD_PRICE".equals(jb.get("reason")) && same(jb.get("price"), "158.2555"), "jpy fraction " + jb);
    check("BAD_PRICE".equals(huge.get("reason")) && huge.get("price") == null, "huge " + huge);
    Map<?, ?> book = c.json("GET", "/api/orderbook/EUR-USD", null, 200);
    List<?> asks = (List<?>) book.get("asks");
    check(asks.size() == 4 && ((Map<?, ?>) asks.get(0)).get("price").toString().equals("1.08500"),
        "asks " + asks);
    return TestRunner.Result.pass("1.085 -> 1.08500, \"1.0850700\" -> 1.08507, 158.2555 on JPY"
        + " rejected, 1e999999 not echoed");
  }

  private static TestRunner.Result casing(Client c) throws Exception {
    Map<?, ?> r1 = c.order(1, "eur/usd", "sell", "limit", "1.09", 5);
    Map<?, ?> r2 = c.order(1, "Eur-Usd", "SELL", "LIMIT", "1.091", 5);
    check("EUR/USD".equals(r1.get("pair")) && "QUEUED".equals(r1.get("status"))
        && "QUEUED".equals(r2.get("status")), r1 + " " + r2);
    for (String p : new String[] { "eur-usd", "EUR-USD", "EUR%2FUSD", "eur%2fusd" }) {
      Map<?, ?> book = c.json("GET", "/api/orderbook/" + p + "?depth=2", null, 200);
      check("EUR/USD".equals(book.get("pair")) && ((List<?>) book.get("asks")).size() == 2, p);
    }
    Map<?, ?> t = c.json("GET", "/api/tickers?prefix=usd-", null, 200);
    Map<?, ?> first = (Map<?, ?>) ((List<?>) t.get("tickers")).get(0);
    check(t.get("count").equals(11L) && "USD/".equals(t.get("prefix")) && first.get("tickSize") != null,
        "tickers " + t);
    Map<?, ?> all = c.json("GET", "/api/tickers", null, 200);
    Map<?, ?> jpy = c.json("GET", "/api/tickers?prefix=USD/JPY", null, 200);
    check(all.get("count").equals(132L)
        && same(((Map<?, ?>) ((List<?>) jpy.get("tickers")).get(0)).get("tickSize"), "0.001"), "all/jpy");
    return TestRunner.Result.pass(null);
  }

  private static TestRunner.Result views(Client c) throws Exception {
    for (int i = 0; i < 40; i++) c.order(1, "EUR/USD", "SELL", "LIMIT", "1.09" + String.format("%03d", i), 1 + i);
    c.order(2, "EUR/USD", "BUY", "LIMIT", "1.08000", 7);
    Map<?, ?> insp = c.json("GET", "/api/inspector/eur-usd", null, 200);
    Map<?, ?> asks = (Map<?, ?>) insp.get("asks");
    Map<?, ?> shape = (Map<?, ?>) asks.get("shape");
    Map<?, ?> lvl0 = (Map<?, ?>) ((List<?>) asks.get("levels")).get(0);
    check(asks.get("levelCount").equals(40L) && shape.get("height").equals(2L)
        && shape.get("leaves").equals(2L) && shape.get("entries").equals(40L)
        && "ORD-1".equals(((Map<?, ?>) lvl0.get("head")).get("orderId")), "inspector " + asks);
    c.json("POST", "/api/market/rates", "{\"source\":\"finnhub-ws\",\"ticks\":["
        + "{\"symbol\":\"OANDA:EUR_USD\",\"price\":1.08,\"ts\":1},"
        + "{\"symbol\":\"OANDA:GBP_USD\",\"price\":1.30,\"ts\":1},"
        + "{\"symbol\":\"OANDA:EUR_GBP\",\"price\":" + (1.08 / 1.30 * 1.004) + ",\"ts\":1}]}", 200);
    Map<?, ?> scan = c.json("GET", "/api/arbitrage/scan", null, 200);
    Map<?, ?> graph = c.json("GET", "/api/arbitrage/graph", null, 200);
    check(Boolean.TRUE.equals(scan.get("hasArbitrage")) && ((List<?>) scan.get("cycle")).size() == 4,
        "scan " + scan);
    check(((List<?>) graph.get("nodes")).size() == 12 && ((List<?>) graph.get("edges")).size() == 6
        && graph.get("cycle") != null, "graph " + graph);
    Map<?, ?> health = c.json("GET", "/api/health", null, 200);
    check(health.get("restingOrders").equals(41L) && health.get("accounts").equals(12L), "health " + health);
    return TestRunner.Result.pass("40 ask levels: B+ tree height 2, 2 leaves");
  }

  private static TestRunner.Result cors(Client c) throws Exception {
    for (String origin : new String[] { "http://localhost:5173", "http://127.0.0.1:5173" }) {
      HttpResponse<String> r = c.send("GET", "/api/health", null, "Origin", origin);
      check(r.headers().firstValue("Access-Control-Allow-Origin").orElse("").equals(origin), origin);
      HttpResponse<String> pre = c.send("OPTIONS", "/api/orders", null, "Origin", origin,
          "Access-Control-Request-Method", "POST");
      check(pre.statusCode() == 204 && pre.headers().firstValue("Access-Control-Allow-Methods")
          .orElse("").contains("POST"), "preflight " + origin);
    }
    HttpResponse<String> evil = c.send("GET", "/api/health", null, "Origin", "http://evil.example");
    check(evil.statusCode() == 200 && evil.headers().firstValue("Access-Control-Allow-Origin").isEmpty(),
        "evil origin got CORS");
    HttpResponse<String> pre = c.send("OPTIONS", "/api/orders", null, "Origin", "http://evil.example");
    check(c.errorCode(pre).equals("403 FORBIDDEN_ORIGIN"), "evil preflight");
    HttpResponse<String> err = c.send("GET", "/api/nope", null, "Origin", "http://localhost:5173");
    check(err.headers().firstValue("Access-Control-Allow-Origin").isPresent(), "CORS on errors");
    return TestRunner.Result.pass(null);
  }

  /** Reads an SSE stream on a background thread into a queue of complete frames. */
  static final class SseReader implements AutoCloseable {
    final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
    final Socket socket;
    final Thread thread;

    SseReader(int port) throws Exception {
      socket = new Socket("127.0.0.1", port);
      OutputStream out = socket.getOutputStream();
      out.write("GET /api/stream HTTP/1.1\r\nHost: x\r\nAccept: text/event-stream\r\n\r\n"
          .getBytes(StandardCharsets.US_ASCII));
      out.flush();
      BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(),
          StandardCharsets.UTF_8));
      thread = new Thread(() -> {
        StringBuilder frame = new StringBuilder();
        try {
          String line;
          while ((line = in.readLine()) != null) {
            if (line.isEmpty()) {
              if (frame.length() > 0) frames.add(frame.toString());
              frame.setLength(0);
            } else if (!line.matches("[0-9a-fA-F]+")) { // skip chunk-size lines
              frame.append(line).append('\n');
            }
          }
        } catch (Exception ignored) {
          // closed
        }
      }, "sse-test-reader");
      thread.setDaemon(true);
      thread.start();
    }

    /** The next frame whose text contains {@code needle}, within the timeout. */
    String await(String needle, long ms) throws InterruptedException {
      long end = System.currentTimeMillis() + ms;
      while (System.currentTimeMillis() < end) {
        String f = frames.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
        if (f != null && f.contains(needle)) return f;
      }
      throw new AssertionError("no SSE frame containing " + needle);
    }

    @Override
    public void close() throws java.io.IOException {
      socket.close();
    }
  }

  private static TestRunner.Result sseOrder(Client c) throws Exception {
    try (SseReader sse = new SseReader(c.app.port())) {
      String hello = sse.await("connected", 5_000);
      check(hello.contains("retry: " + SseHub.RETRY_MS), "no retry field: " + hello);
      c.order(1, "EUR/USD", "SELL", "LIMIT", "1.08510", 100);
      c.order(2, "EUR/USD", "BUY", "LIMIT", "1.08510", 40);
      c.json("POST", "/api/market/rates", "{\"source\":\"finnhub-ws\",\"ticks\":["
          + "{\"symbol\":\"OANDA:EUR_USD\",\"price\":1.08,\"ts\":1},"
          + "{\"symbol\":\"OANDA:GBP_USD\",\"price\":1.30,\"ts\":1},"
          + "{\"symbol\":\"OANDA:EUR_GBP\",\"price\":" + (1.08 / 1.30 * 1.004) + ",\"ts\":1}]}", 200);
      List<String> seen = new ArrayList<>();
      long lastId = 0;
      long end = System.currentTimeMillis() + 5_000;
      while (!seen.contains("LOOPHOLE_ALERT") && System.currentTimeMillis() < end) {
        String f = sse.frames.poll(200, TimeUnit.MILLISECONDS);
        if (f == null || !f.contains("event: ")) continue;
        String type = f.substring(f.indexOf("event: ") + 7, f.indexOf('\n', f.indexOf("event: ")));
        long id = Long.parseLong(f.substring(f.indexOf("id: ") + 4, f.indexOf('\n', f.indexOf("id: "))));
        check(id > lastId, "ids not increasing: " + id + " after " + lastId);
        lastId = id;
        seen.add(type);
        if (type.equals("LOOPHOLE_ALERT")) check(f.contains("\"active\":true"), f);
      }
      int book = seen.indexOf("BOOK_UPDATE");
      int trade = seen.indexOf("TRADE");
      int alert = seen.indexOf("LOOPHOLE_ALERT");
      check(book >= 0 && trade > book && alert > trade && seen.contains("RATE_TICK"),
          "event order " + seen);
      return TestRunner.Result.pass(String.join(", ", seen));
    }
  }

  private static TestRunner.Result sseSlowClient(Client c) throws Exception {
    Socket lazy = new Socket();
    lazy.setReceiveBufferSize(4_096); // and it never reads
    lazy.connect(new InetSocketAddress("127.0.0.1", c.app.port()));
    lazy.getOutputStream().write("GET /api/stream HTTP/1.1\r\nHost: x\r\n\r\n"
        .getBytes(StandardCharsets.US_ASCII));
    try (SseReader good = new SseReader(c.app.port())) {
      good.await("connected", 5_000);
      long deadline = System.currentTimeMillis() + 5_000;
      while ((Long) c.json("GET", "/api/health", null, 200).get("sseClients") < 2) {
        check(System.currentTimeMillis() < deadline, "clients did not connect");
        Thread.sleep(20);
      }
      long t0 = System.nanoTime();
      for (int i = 0; i < 3_000; i++) {
        boolean buy = i % 2 == 0;
        c.order(1 + i % 10, "EUR/USD", buy ? "BUY" : "SELL", "LIMIT",
            "1.08" + String.format("%03d", 500 + (buy ? 1 : -1) * (i % 7)), 1 + i % 9);
      }
      double seconds = (System.nanoTime() - t0) / 1e9;
      Map<?, ?> h = c.json("GET", "/api/health", null, 200);
      check(seconds < 30, "order flow stalled: " + seconds + " s");
      good.await("TRADE", 5_000); // the healthy client kept receiving
      // (whether the lazy client is dropped here depends on kernel socket buffers, which can
      // absorb megabytes; the drop itself is proven deterministically by the next check)
      return TestRunner.Result.pass(String.format(java.util.Locale.ROOT,
          "3000 orders in %.1f s with a non-reading client attached; sseDropped=%s",
          seconds, h.get("sseDropped")));
    } finally {
      lazy.close();
    }
  }

  /** An exchange whose response body blocks on the first write until the exchange closes. */
  static final class BlockedExchange extends com.sun.net.httpserver.HttpExchange {
    final java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
    final com.sun.net.httpserver.Headers requestHeaders = new com.sun.net.httpserver.Headers();
    final com.sun.net.httpserver.Headers responseHeaders = new com.sun.net.httpserver.Headers();
    final OutputStream body = new OutputStream() {
      @Override
      public void write(int b) throws java.io.IOException {
        write(new byte[] { (byte) b }, 0, 1);
      }

      @Override
      public void write(byte[] b, int off, int len) throws java.io.IOException {
        try {
          closed.await(); // a client whose TCP window never opens
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        throw new java.io.IOException("connection closed");
      }
    };

    @Override public com.sun.net.httpserver.Headers getRequestHeaders() { return requestHeaders; }
    @Override public com.sun.net.httpserver.Headers getResponseHeaders() { return responseHeaders; }
    @Override public URI getRequestURI() { return URI.create("/api/stream"); }
    @Override public String getRequestMethod() { return "GET"; }
    @Override public com.sun.net.httpserver.HttpContext getHttpContext() { return null; }
    @Override public void close() { closed.countDown(); }
    @Override public java.io.InputStream getRequestBody() { return java.io.InputStream.nullInputStream(); }
    @Override public OutputStream getResponseBody() { return body; }
    @Override public void sendResponseHeaders(int code, long length) { }
    @Override public InetSocketAddress getRemoteAddress() { return null; }
    @Override public int getResponseCode() { return 200; }
    @Override public InetSocketAddress getLocalAddress() { return null; }
    @Override public String getProtocol() { return "HTTP/1.1"; }
    @Override public Object getAttribute(String name) { return null; }
    @Override public void setAttribute(String name, Object value) { }
    @Override public void setStreams(java.io.InputStream i, OutputStream o) { }
    @Override public com.sun.net.httpserver.HttpPrincipal getPrincipal() { return null; }
  }

  private static TestRunner.Result hubDropsBlockedClient() throws Exception {
    SseHub hub = new SseHub();
    BlockedExchange ex = new BlockedExchange();
    Thread writer = new Thread(() -> {
      try {
        hub.serve(ex);
      } catch (Exception ignored) {
        // ends when the reaper closes the exchange
      }
    }, "blocked-sse-client");
    writer.setDaemon(true);
    writer.start();
    long deadline = System.currentTimeMillis() + 5_000;
    while (hub.clientCount() < 1) {
      if (System.currentTimeMillis() > deadline) return TestRunner.Result.fail("never connected");
      Thread.sleep(5);
    }
    Map<String, Object> data = new java.util.LinkedHashMap<>();
    data.put("pair", "EUR/USD");
    long worstNanos = 0;
    long t0 = System.nanoTime();
    for (int i = 0; i < 2_000; i++) {
      long s = System.nanoTime();
      hub.publish("TRADE", data);
      worstNanos = Math.max(worstNanos, System.nanoTime() - s);
    }
    double totalMs = (System.nanoTime() - t0) / 1e6;
    writer.join(5_000);
    boolean ok = hub.droppedClients() == 1 && hub.clientCount() == 0 && !writer.isAlive()
        && ex.closed.getCount() == 0 && totalMs < 1_000;
    hub.close();
    return ok ? TestRunner.Result.pass(String.format(java.util.Locale.ROOT,
        "dropped after a full %d-event queue; 2000 publishes in %.1f ms (worst %.2f ms);"
            + " writer thread released", SseHub.QUEUE_CAPACITY, totalMs, worstNanos / 1e6))
        : TestRunner.Result.fail("dropped=" + hub.droppedClients() + " clients=" + hub.clientCount()
            + " writerAlive=" + writer.isAlive() + " publishMs=" + totalMs);
  }

  /** Order flow for the concurrency test: thread t's i-th order. */
  private static String orderBody(int t, int i) {
    boolean buy = (t + i) % 2 == 0;
    int tick = 500 + ((t * 7 + i * 3) % 9) - 4;
    return "{\"traderId\":" + (1 + (t + i) % 11) + ",\"pair\":\"EUR/USD\",\"side\":\""
        + (buy ? "BUY" : "SELL") + "\",\"type\":\"" + (i % 5 == 0 ? "MARKET" : "LIMIT")
        + "\",\"price\":1.08" + tick + ",\"quantity\":" + (1 + (t * 3 + i) % 17) + "}";
  }

  private static TestRunner.Result concurrentEqualsSerial() throws Exception {
    Path d1 = Files.createTempDirectory("ads-gw-conc-");
    Path d2 = Files.createTempDirectory("ads-gw-serial-");
    App concurrent = startApp(d1);
    App serial = startApp(d2);
    try {
      Client c1 = new Client(concurrent);
      ExecutorService pool = Executors.newFixedThreadPool(8);
      List<Future<List<String[]>>> futures = new ArrayList<>();
      for (int t = 0; t < 8; t++) {
        final int thread = t;
        futures.add(pool.submit(() -> {
          List<String[]> mine = new ArrayList<>();
          for (int i = 0; i < 25; i++) {
            String body = orderBody(thread, i);
            HttpResponse<String> r = c1.send("POST", "/api/orders", body);
            mine.add(new String[] { body, r.body() });
          }
          return mine;
        }));
      }
      List<String[]> all = new ArrayList<>();
      for (Future<List<String[]>> f : futures) all.addAll(f.get(120, TimeUnit.SECONDS));
      pool.shutdown();
      // the engine's arrival order is the order of the ids it assigned
      all.sort(Comparator.comparingLong(x -> Long.parseLong(x[1].replaceAll(
          "^\\{\"orderId\":\"ORD-([0-9]+)\".*$", "$1"))));
      Client c2 = new Client(serial);
      for (String[] x : all) {
        HttpResponse<String> r = c2.send("POST", "/api/orders", x[0]);
        if (!r.body().equals(x[1])) {
          return TestRunner.Result.fail("serial replay differs:\n  " + x[1] + "\n  " + r.body());
        }
      }
      for (String path : new String[] { "/api/orderbook/EUR-USD?depth=50", "/api/accounts" }) {
        String a = c1.send("GET", path, null).body();
        String b = c2.send("GET", path, null).body();
        if (!a.equals(b)) return TestRunner.Result.fail(path + " differs");
      }
      return TestRunner.Result.pass("200 responses, final book and all 12 balances identical");
    } finally {
      concurrent.stop();
      serial.stop();
      deleteTree(d1);
      deleteTree(d2);
    }
  }

  private static TestRunner.Result restart() throws Exception {
    Path dir = Files.createTempDirectory("ads-gw-restart-");
    try {
      App first = startApp(dir);
      Client c = new Client(first);
      c.json("POST", "/api/accounts", "{\"traderId\":501,\"cash\":10000000,\"marginLimit\":9000000}", 201);
      c.order(501, "EUR/USD", "SELL", "LIMIT", "1.08500", 30);
      c.order(1, "EUR/USD", "BUY", "LIMIT", "1.08500", 30);
      long cash501 = c.cash(501);
      long cash1 = c.cash(1);
      first.stop();
      App second = startApp(dir);
      try {
        Client c2 = new Client(second);
        check(c2.cash(501) == cash501 && c2.cash(1) == cash1, "balances after restart");
        check(c2.json("GET", "/api/accounts", null, 200).get("count").equals(13L), "13 accounts");
        c2.order(501, "EUR/USD", "SELL", "LIMIT", "1.08600", 5);
        c2.order(2, "EUR/USD", "BUY", "MARKET", null, 5);
      } finally {
        second.stop();
      }
      String archive = new String(Logbook.readArchive(dir.resolve("logbook")), StandardCharsets.US_ASCII);
      String[] lines = archive.split("\n");
      check(lines.length == 2 && lines[0].contains(",EUR/USD,108500,30,") && lines[1].contains(",EUR/USD,108600,5,"),
          "archive " + archive);
      return TestRunner.Result.pass("cash 501=" + cash501 + " kept; archive holds both sessions' trades");
    } finally {
      deleteTree(dir);
    }
  }

  static void deleteTree(Path root) throws Exception {
    if (!Files.exists(root)) return;
    try (Stream<Path> s = Files.walk(root)) {
      for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) Files.delete(p);
    }
  }
}
