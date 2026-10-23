package ads.test;

import ads.api.ApiException;
import ads.api.ApiServer;
import ads.api.EngineThread;
import ads.api.MarketService;
import ads.api.Scheduler;
import ads.bench.core.Rng;
import ads.directory.Directory;
import ads.scanner.ArbitrageScanner;
import ads.util.Json;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Stage 2 Java side: the JSON reader/writer, MarketService, and the HTTP server. */
final class ApiTests {
  private ApiTests() {
  }

  static void run(TestRunner r) {
    r.section("util: JSON reader/writer");
    r.check("json: values, nesting, escapes and numbers parse to the documented types",
        ApiTests::jsonValues);
    r.check("json: 26 malformed documents are rejected with an offset", ApiTests::jsonRejects);
    r.check("json: writer escapes and round-trips; NaN refused", ApiTests::jsonWriter);
    r.check("json: 20000 mutated payloads only ever fail with JsonException", ApiTests::jsonFuzz);

    r.section("api: MarketService (fake clock, manual scheduler)");
    r.check("finnhub ticks update the rate store; crosses derive through USD; status is live",
        ApiTests::ingestTicks);
    r.check("bad ticks are counted and reported, never fatal; bad payload shapes are 400",
        ApiTests::ingestRejects);
    r.check("frankfurter-fallback sets the USD legs, ignores other currencies, state fallback",
        ApiTests::ingestFallback);
    r.check("heartbeat-only feed is 'connected, no ticks'; silence > 15 s is 'disconnected'",
        ApiTests::heartbeatAndDisconnect);
    r.check("staleness: a pair is stale after 30 s without a tick and not before; a fallback"
        + " snapshot refreshes it",
        ApiTests::staleness);
    r.check("throttle: 50 ticks in 100 ms scan once in the window, plus one trailing scan of"
        + " the newest rates", ApiTests::throttle);
    r.check("a replayed capture is labelled replay=true in the status", ApiTests::replayFlag);

    r.section("api: HTTP server (real sockets, ephemeral port)");
    r.check("both endpoints answer; status JSON has every documented field", ApiTests::serverHappy);
    r.check("garbage never kills the server: bad JSON, bad shape, 404, 405, 413, raw junk bytes",
        ApiTests::serverGarbage);
    r.check("400 concurrent posts from 8 threads are all applied (one engine thread)",
        ApiTests::serverConcurrent);
  }

  // ---------------------------------------------------------------- JSON

  private static TestRunner.Result jsonValues() throws Exception {
    Object v = Json.parse(" {\"a\":[1,-2,3.5,1e2,true,false,null,\"x\\n\\u00e9\\\"\"],"
        + "\"b\":{\"c\":{}},\"d\":[],\"big\":12345678901234567890,\"z\":-0} ");
    Map<?, ?> m = (Map<?, ?>) v;
    List<?> a = (List<?>) m.get("a");
    boolean ok = a.get(0).equals(1L) && a.get(1).equals(-2L) && a.get(2).equals(3.5)
        && a.get(3).equals(100.0) && a.get(4).equals(true) && a.get(5).equals(false)
        && a.get(6) == null && a.get(7).equals("x\n\u00e9\"")
        && ((Map<?, ?>) ((Map<?, ?>) m.get("b")).get("c")).isEmpty()
        && ((List<?>) m.get("d")).isEmpty() && m.get("big") instanceof Double
        && m.get("z").equals(0L)
        && new ArrayList<>(m.keySet()).equals(List.of("a", "b", "d", "big", "z"));
    StringBuilder deep = new StringBuilder();
    for (int i = 0; i < Json.MAX_DEPTH; i++) deep.append('[');
    for (int i = 0; i < Json.MAX_DEPTH; i++) deep.append(']');
    Json.parse(deep.toString());
    return ok ? TestRunner.Result.pass(null) : TestRunner.Result.fail("parsed " + v);
  }

  private static TestRunner.Result jsonRejects() {
    StringBuilder tooDeep = new StringBuilder();
    for (int i = 0; i <= Json.MAX_DEPTH + 1; i++) tooDeep.append('[');
    String[] bad = { "", " ", "{", "[1,]", "{\"a\":1,}", "{\"a\" 1}", "{a:1}", "[1 2]",
        "01", "1.", ".5", "-", "1e", "+1", "NaN", "Infinity", "1e400", "\"abc", "\"\\x\"",
        "\"\\u12\"", "\"tab\there\"", "{\"a\":1,\"a\":2}", "[1] [2]", "tru", "nul",
        tooDeep.toString() };
    for (String s : bad) {
      try {
        Json.parse(s);
        return TestRunner.Result.fail("accepted: " + s);
      } catch (Json.JsonException e) {
        if (e.offset() < 0 || !e.getMessage().contains("offset")) {
          return TestRunner.Result.fail("no offset for " + s);
        }
      }
    }
    return TestRunner.Result.pass(bad.length + " rejected");
  }

  private static TestRunner.Result jsonWriter() throws Exception {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("s", "q\"b\\n\n\t\u0001\u2028é");
    m.put("n", Arrays.asList(1L, 2.5, 158.0, -0.0, 1e-7, null, true));
    m.put("arr", new String[] { "a", "b" });
    String out = Json.write(m);
    String want = "{\"s\":\"q\\\"b\\\\n\\n\\t\\u0001\\u2028é\",\"n\":[1,2.5,158,0,1.0E-7,null,true],"
        + "\"arr\":[\"a\",\"b\"]}";
    if (!out.equals(want)) return TestRunner.Result.fail(out);
    Object back = Json.parse(out);
    if (!((Map<?, ?>) back).get("s").equals(m.get("s"))) return TestRunner.Result.fail("round trip");
    try {
      Json.write(Double.NaN);
      return TestRunner.Result.fail("NaN written");
    } catch (IllegalArgumentException expected) {
      return TestRunner.Result.pass(null);
    }
  }

  private static TestRunner.Result jsonFuzz() {
    String base = "{\"source\":\"finnhub-ws\",\"ticks\":[{\"symbol\":\"OANDA:EUR_USD\","
        + "\"price\":1.0852,\"ts\":1792783468459},{\"symbol\":\"OANDA:USD_JPY\",\"price\":156.87,"
        + "\"ts\":1792783468507}],\"replay\":false}";
    Rng rng = new Rng(8);
    String alphabet = "{}[]:,\"\\-+.eE0123456789tfnulrsa \u0000\u00ff";
    int parsed = 0;
    for (int i = 0; i < 20_000; i++) {
      StringBuilder sb = new StringBuilder(base);
      int edits = 1 + rng.nextInt(4);
      for (int e = 0; e < edits && sb.length() > 0; e++) {
        int at = rng.nextInt(sb.length());
        switch (rng.nextInt(3)) {
          case 0: sb.setCharAt(at, alphabet.charAt(rng.nextInt(alphabet.length()))); break;
          case 1: sb.deleteCharAt(at); break;
          default: sb.insert(at, alphabet.charAt(rng.nextInt(alphabet.length()))); break;
        }
      }
      try {
        Json.parse(sb.toString());
        parsed++;
      } catch (Json.JsonException expected) {
        // fine
      } catch (RuntimeException | StackOverflowError wrong) {
        return TestRunner.Result.fail("mutation " + i + " threw " + wrong + " on " + sb);
      }
    }
    return TestRunner.Result.pass(parsed + " still valid, the rest rejected cleanly");
  }

  // ---------------------------------------------------------------- MarketService

  /** A manual clock and scheduler: scheduled tasks run when the test advances time. */
  private static final class Harness implements Scheduler {
    long now = 1_792_000_000_000L;
    final List<long[]> dueAt = new ArrayList<>();
    final List<Runnable> tasks = new ArrayList<>();
    final ArbitrageScanner scanner = new ArbitrageScanner(new Directory());
    final MarketService market = new MarketService(scanner, this, () -> now);

    @Override
    public void schedule(Runnable task, long delayMs) {
      dueAt.add(new long[] { now + delayMs });
      tasks.add(task);
    }

    void advance(long ms) {
      now += ms;
      for (int i = 0; i < tasks.size(); i++) {
        if (dueAt.get(i)[0] <= now) {
          Runnable t = tasks.remove(i);
          dueAt.remove(i);
          i--;
          t.run();
        }
      }
    }

    Map<String, Object> ticks(Object... symbolPrice) throws Exception {
      return market.ingest(Json.parse(ticksJson(false, symbolPrice)));
    }

    Map<String, Object> status() {
      return market.status();
    }
  }

  static String ticksJson(boolean replay, Object... symbolPrice) {
    StringBuilder sb = new StringBuilder("{\"source\":\"finnhub-ws\",");
    if (replay) sb.append("\"replay\":true,");
    sb.append("\"ticks\":[");
    for (int i = 0; i < symbolPrice.length; i += 2) {
      if (i > 0) sb.append(',');
      sb.append("{\"symbol\":\"").append(symbolPrice[i]).append("\",\"price\":")
          .append(symbolPrice[i + 1]).append(",\"ts\":").append(1_792_783_468_459L + i).append('}');
    }
    return sb.append("]}").toString();
  }

  private static TestRunner.Result ingestTicks() throws Exception {
    Harness h = new Harness();
    Map<String, Object> resp = h.ticks("OANDA:EUR_USD", 1.0852, "OANDA:USD_JPY", 156.87);
    ArbitrageScanner s = h.scanner;
    int usd = s.currencyId("USD");
    int eur = s.currencyId("EUR");
    int jpy = s.currencyId("JPY");
    if (!resp.get("accepted").equals(2) || s.rate(eur, usd) != 1.0852
        || s.rate(eur, jpy) != s.rate(usd, jpy) / s.rate(usd, eur)) {
      return TestRunner.Result.fail("rates " + resp);
    }
    Map<String, Object> st = h.status();
    if (!"live".equals(st.get("state")) || !"finnhub-ws".equals(st.get("source"))
        || !Boolean.TRUE.equals(st.get("connected")) || !st.get("tickCount").equals(2L)
        || !st.get("scanCount").equals(1L) || !"2026-10-23T19:24:28.461Z".equals(st.get("lastTickAt"))) {
      return TestRunner.Result.fail("status " + st);
    }
    return TestRunner.Result.pass(null);
  }

  private static TestRunner.Result ingestRejects() throws Exception {
    Harness h = new Harness();
    String body = "{\"source\":\"finnhub-ws\",\"ticks\":["
        + "{\"symbol\":\"OANDA:EUR_USD\",\"price\":1.08,\"ts\":5},"
        + "{\"price\":1.08,\"ts\":5},"
        + "{\"symbol\":\"OANDA:EUR_USD\",\"price\":0,\"ts\":5},"
        + "{\"symbol\":\"OANDA:EUR_USD\",\"price\":-1,\"ts\":5},"
        + "{\"symbol\":\"OANDA:EUR_USD\",\"price\":\"1.08\",\"ts\":5},"
        + "{\"symbol\":\"OANDA:EUR_USD\",\"price\":true,\"ts\":5},"
        + "{\"symbol\":\"OANDA:EUR_USD\",\"price\":1.08,\"ts\":1.5},"
        + "{\"symbol\":\"OANDA:EUR_USD\",\"price\":1.08},"
        + "{\"symbol\":\"OANDA:XAU_USD\",\"price\":2400,\"ts\":5},"
        + "{\"symbol\":\"FXCM:EUR/USD\",\"price\":1.08,\"ts\":5},"
        + "{\"symbol\":\"OANDA:EUR_EUR\",\"price\":1,\"ts\":5},"
        + "[1,2], 7, null]}";
    Map<String, Object> resp = h.market.ingest(Json.parse(body));
    if (!resp.get("accepted").equals(1) || !resp.get("rejected").equals(13)
        || ((List<?>) resp.get("errors")).size() != 5) {
      return TestRunner.Result.fail("response " + resp);
    }
    String[] badShapes = { "[]", "7", "{}", "{\"source\":\"bloomberg\",\"ticks\":[]}",
        "{\"source\":\"finnhub-ws\"}", "{\"source\":\"finnhub-ws\",\"ticks\":{}}",
        "{\"source\":\"finnhub-ws\",\"ticks\":[],\"replay\":\"yes\"}",
        "{\"source\":\"frankfurter-fallback\",\"base\":\"EUR\",\"rates\":{}}",
        "{\"source\":\"frankfurter-fallback\",\"base\":\"USD\",\"rates\":[]}",
        "{\"source\":\"frankfurter-fallback\",\"base\":\"USD\",\"rates\":{},\"asOf\":5}" };
    for (String s : badShapes) {
      try {
        h.market.ingest(Json.parse(s));
        return TestRunner.Result.fail("accepted shape " + s);
      } catch (ApiException e) {
        if (e.status() != 400 || !e.code().equals("BAD_PAYLOAD")) {
          return TestRunner.Result.fail(s + " -> " + e.status() + " " + e.code());
        }
      }
    }
    Map<String, Object> st = h.status();
    if (!st.get("tickCount").equals(1L) || !st.get("rejectedTicks").equals(13L)) {
      return TestRunner.Result.fail("counters " + st);
    }
    return TestRunner.Result.pass("13 bad ticks counted, " + badShapes.length + " bad shapes -> 400");
  }

  private static TestRunner.Result ingestFallback() throws Exception {
    Harness h = new Harness();
    Map<String, Object> resp = h.market.ingest(Json.parse("{\"source\":\"frankfurter-fallback\","
        + "\"base\":\"USD\",\"asOf\":\"2026-10-23\",\"rates\":{\"EUR\":0.89238,\"GBP\":0.75641,"
        + "\"JPY\":158.25,\"INR\":96.73,\"BGN\":1.75,\"ISK\":138,\"EUR2\":1,\"CHF\":-1}}"));
    ArbitrageScanner s = h.scanner;
    Map<String, Object> st = h.status();
    boolean ok = resp.get("accepted").equals(4) && resp.get("rejected").equals(1)
        && s.rate(s.currencyId("USD"), s.currencyId("INR")) == 96.73
        && "fallback".equals(st.get("state")) && "frankfurter-fallback".equals(st.get("source"))
        && "2026-10-23".equals(st.get("fallbackAsOf")) && Boolean.FALSE.equals(st.get("connected"))
        && st.get("scanCount").equals(1L) && Boolean.FALSE.equals(st.get("lastScanFoundCycle"));
    return ok ? TestRunner.Result.pass(null)
        : TestRunner.Result.fail("resp " + resp + " status " + st);
  }

  private static TestRunner.Result heartbeatAndDisconnect() throws Exception {
    Harness h = new Harness();
    if (!"waiting".equals(h.status().get("state"))) return TestRunner.Result.fail("initial state");
    h.ticks(); // heartbeat
    Map<String, Object> st = h.status();
    if (!"connected, no ticks".equals(st.get("state")) || !Boolean.TRUE.equals(st.get("connected"))
        || !st.get("scanCount").equals(0L)) {
      return TestRunner.Result.fail("heartbeat " + st);
    }
    h.ticks("OANDA:EUR_USD", 1.08);
    h.advance(MarketService.FEED_SILENT_MS);
    if (!"live".equals(h.status().get("state"))) return TestRunner.Result.fail("15 s exactly");
    h.advance(1);
    st = h.status();
    if (!"disconnected".equals(st.get("state")) || !"cached".equals(st.get("source"))) {
      return TestRunner.Result.fail("silence " + st);
    }
    h.ticks();
    h.advance(MarketService.STALE_AFTER_MS - MarketService.FEED_SILENT_MS);
    h.ticks();
    if (!"connected, no ticks".equals(h.status().get("state"))) {
      return TestRunner.Result.fail("weekend: heartbeats but no ticks for > 30 s " + h.status());
    }
    return TestRunner.Result.pass(null);
  }

  private static TestRunner.Result staleness() throws Exception {
    Harness h = new Harness();
    h.ticks("OANDA:EUR_USD", 1.08, "OANDA:USD_JPY", 156.0);
    h.advance(10_000);
    h.ticks("OANDA:USD_JPY", 156.1);
    h.advance(MarketService.STALE_AFTER_MS - 10_000);
    if (!((List<?>) h.status().get("staleSymbols")).isEmpty()) {
      return TestRunner.Result.fail("stale at exactly 30 s: " + h.status().get("staleSymbols"));
    }
    h.advance(1);
    List<?> stale = (List<?>) h.status().get("staleSymbols");
    if (!stale.equals(List.of("EUR/USD"))) return TestRunner.Result.fail("after 30 s: " + stale);
    h.advance(10_000);
    stale = (List<?>) h.status().get("staleSymbols");
    if (!stale.equals(List.of("EUR/USD", "USD/JPY"))) return TestRunner.Result.fail("later: " + stale);
    h.ticks("OANDA:EUR_USD", 1.081);
    stale = (List<?>) h.status().get("staleSymbols");
    if (!stale.equals(List.of("USD/JPY"))) return TestRunner.Result.fail("refreshed: " + stale);
    // a fallback snapshot refreshes the USD legs in either orientation: nothing stale after it
    h.advance(MarketService.STALE_AFTER_MS + 1);
    h.market.ingest(Json.parse("{\"source\":\"frankfurter-fallback\",\"base\":\"USD\","
        + "\"rates\":{\"EUR\":0.92,\"JPY\":156.5}}"));
    Map<String, Object> st = h.status();
    stale = (List<?>) st.get("staleSymbols");
    return stale.isEmpty() && st.get("pairsSeen").equals(2) ? TestRunner.Result.pass(null)
        : TestRunner.Result.fail("after fallback: stale " + stale + ", pairs " + st.get("pairsSeen"));
  }

  private static TestRunner.Result throttle() throws Exception {
    Harness h = new Harness();
    // a consistent graph first, then 50 ticks in 100 ms; the last one opens an arbitrage
    h.ticks("OANDA:EUR_USD", 1.08, "OANDA:GBP_USD", 1.30, "OANDA:USD_JPY", 156.0);
    h.advance(1_000);
    long before = h.market.scanCount();
    for (int i = 0; i < 49; i++) {
      h.ticks("OANDA:USD_JPY", 156.0 + i * 0.001);
      h.advance(2);
    }
    double derived = 1.08 / 1.30;
    h.ticks("OANDA:EUR_GBP", derived * 1.004);
    long inWindow = h.market.scanCount() - before;
    boolean cycleBefore = Boolean.TRUE.equals(h.status().get("lastScanFoundCycle"));
    h.advance(MarketService.SCAN_THROTTLE_MS);
    long total = h.market.scanCount() - before;
    boolean cycleAfter = Boolean.TRUE.equals(h.status().get("lastScanFoundCycle"));
    if (inWindow != 1 || total != 2 || cycleBefore || !cycleAfter) {
      return TestRunner.Result.fail("scans in window " + inWindow + ", total " + total
          + ", cycle before/after trailing scan " + cycleBefore + "/" + cycleAfter);
    }
    return TestRunner.Result.pass("1 scan in the 100 ms burst + 1 trailing scan that saw the"
        + " last tick");
  }

  private static TestRunner.Result replayFlag() throws Exception {
    Harness h = new Harness();
    h.market.ingest(Json.parse(ticksJson(true, "OANDA:EUR_USD", 1.08)));
    Map<String, Object> st = h.status();
    if (!Boolean.TRUE.equals(st.get("replay")) || !"finnhub-ws".equals(st.get("source"))) {
      return TestRunner.Result.fail("replay " + st);
    }
    h.ticks("OANDA:EUR_USD", 1.081);
    return Boolean.FALSE.equals(h.status().get("replay")) ? TestRunner.Result.pass(null)
        : TestRunner.Result.fail("live after replay still flagged");
  }

  // ---------------------------------------------------------------- HTTP

  private interface ServerBody {
    TestRunner.Result run(String base, ApiServer server) throws Exception;
  }

  private static TestRunner.Result withServer(ServerBody body) throws Exception {
    EngineThread engine = new EngineThread();
    MarketService market = new MarketService(new ArbitrageScanner(new Directory()), engine,
        System::currentTimeMillis);
    ApiServer server = new ApiServer(new InetSocketAddress("127.0.0.1", 0));
    ApiServer.marketRoutes(server, engine, market);
    server.start();
    try {
      return body.run("http://127.0.0.1:" + server.port(), server);
    } finally {
      server.stop();
      engine.shutdown();
    }
  }

  private static final HttpClient HTTP = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1).build();

  private static HttpResponse<String> send(String method, String url, String body)
      throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
    b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body));
    return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static TestRunner.Result serverHappy() throws Exception {
    return withServer((base, server) -> {
      HttpResponse<String> post = send("POST", base + "/api/market/rates",
          ticksJson(false, "OANDA:EUR_USD", 1.0852, "OANDA:GBP_USD", 1.3));
      HttpResponse<String> st = send("GET", base + "/api/market/rates/status", null);
      if (post.statusCode() != 200 || st.statusCode() != 200) {
        return TestRunner.Result.fail(post.statusCode() + " " + post.body() + " / " + st.body());
      }
      if (!st.headers().firstValue("Content-Type").orElse("").startsWith("application/json")) {
        return TestRunner.Result.fail("content type");
      }
      Map<?, ?> m = (Map<?, ?>) Json.parse(st.body());
      List<String> keys = List.of("state", "source", "replay", "connected", "lastTickAt",
          "lastPayloadAt", "tickCount", "rejectedTicks", "payloads", "pairsSeen", "staleSymbols",
          "fallbackAsOf", "scanCount", "lastScanAt", "lastScanFoundCycle", "lastScan");
      if (!new ArrayList<>(m.keySet()).equals(keys) || !m.get("tickCount").equals(2L)) {
        return TestRunner.Result.fail(st.body());
      }
      return TestRunner.Result.pass(st.body().substring(0, 80) + "...");
    });
  }

  private static String errorCode(HttpResponse<String> r) throws Exception {
    Map<?, ?> m = (Map<?, ?>) Json.parse(r.body());
    Map<?, ?> e = (Map<?, ?>) m.get("error");
    return r.statusCode() + " " + e.get("code");
  }

  private static TestRunner.Result serverGarbage() throws Exception {
    return withServer((base, server) -> {
      String rates = base + "/api/market/rates";
      String[][] cases = {
          { "POST", rates, "{not json", "400 BAD_JSON" },
          { "POST", rates, "", "400 BAD_JSON" },
          { "POST", rates, "{\"source\":\"x\"}", "400 BAD_PAYLOAD" },
          { "POST", rates, "[1,2,3]", "400 BAD_PAYLOAD" },
          { "GET", base + "/api/nope", null, "404 NOT_FOUND" },
          { "DELETE", rates, null, "405 METHOD_NOT_ALLOWED" },
          { "GET", rates, null, "405 METHOD_NOT_ALLOWED" },
      };
      for (String[] c : cases) {
        HttpResponse<String> r = send(c[0], c[1], c[2]);
        if (!errorCode(r).equals(c[3])) {
          return TestRunner.Result.fail(c[0] + " " + c[1] + " -> " + r.statusCode() + " " + r.body());
        }
      }
      HttpResponse<String> m405 = send("GET", rates, null);
      if (!m405.headers().firstValue("Allow").orElse("").equals("POST")) {
        return TestRunner.Result.fail("405 without Allow: POST");
      }
      byte[] huge = new byte[ApiServer.MAX_BODY_BYTES + 10];
      Arrays.fill(huge, (byte) ' ');
      HttpResponse<String> big = HTTP.send(HttpRequest.newBuilder(URI.create(rates))
          .POST(HttpRequest.BodyPublishers.ofByteArray(huge)).build(),
          HttpResponse.BodyHandlers.ofString());
      if (!errorCode(big).equals("413 BODY_TOO_LARGE")) return TestRunner.Result.fail("413: " + big.body());
      // raw junk on a socket, and a client that hangs up mid-body
      int port = server.port();
      for (String junk : new String[] { "\u0000\u0001\u0002 hello\r\n\r\n",
          "POST /api/market/rates HTTP/1.1\r\nHost: x\r\nContent-Length: 500\r\n\r\n{\"so" }) {
        try (Socket s = new Socket("127.0.0.1", port)) {
          s.setSoTimeout(2_000);
          OutputStream out = s.getOutputStream();
          out.write(junk.getBytes(StandardCharsets.ISO_8859_1));
          out.flush();
          s.shutdownOutput();
          InputStream in = s.getInputStream();
          in.readAllBytes();
        } catch (java.io.IOException ignored) {
          // the server may simply close: what matters is that it keeps serving
        }
      }
      HttpResponse<String> after = send("GET", base + "/api/market/rates/status", null);
      if (after.statusCode() != 200) return TestRunner.Result.fail("server stopped answering");
      return TestRunner.Result.pass(cases.length + 3 + " bad requests, server still answering");
    });
  }

  private static TestRunner.Result serverConcurrent() throws Exception {
    return withServer((base, server) -> {
      ExecutorService pool = Executors.newFixedThreadPool(8);
      List<Future<Integer>> results = new ArrayList<>();
      for (int t = 0; t < 8; t++) {
        final int thread = t;
        results.add(pool.submit(() -> {
          int ok = 0;
          for (int i = 0; i < 50; i++) {
            HttpResponse<String> r = send("POST", base + "/api/market/rates",
                ticksJson(false, "OANDA:EUR_USD", 1.08 + thread * 0.001 + i * 0.00001));
            if (r.statusCode() == 200) ok++;
          }
          return ok;
        }));
      }
      int ok = 0;
      for (Future<Integer> f : results) ok += f.get(60, TimeUnit.SECONDS);
      pool.shutdown();
      Map<?, ?> st = (Map<?, ?>) Json.parse(send("GET", base + "/api/market/rates/status", null).body());
      if (ok != 400 || !st.get("tickCount").equals(400L) || !st.get("payloads").equals(400L)) {
        return TestRunner.Result.fail(ok + " ok, status " + st);
      }
      return TestRunner.Result.pass("tickCount 400, payloads 400, scans " + st.get("scanCount"));
    });
  }
}
