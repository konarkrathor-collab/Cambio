package ads.api;

import ads.util.Json;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The backend's HTTP server ({@code com.sun.net.httpserver}, JDK only). Routes are exact
 * paths or patterns with {@code {param}} segments ({@code /api/orders/{id}}); a handler
 * returns a JSON body (status 200) or a {@link Response} with another status. Streaming
 * routes ({@link #routeStream}) take over the exchange, for Server-Sent Events. HTTP
 * threads parse and answer requests; anything touching engine or scanner state goes
 * through the single {@link EngineThread}.
 *
 * <p>Every error, including unknown paths, wrong methods, oversized bodies, malformed JSON
 * and unexpected failures, is answered as {@code {"error":{"code":"...","message":"..."}}}
 * with a proper status, and the server keeps serving.
 *
 * <p>CORS (doc 08 Stage 3): only {@link #ALLOWED_ORIGINS} get
 * {@code Access-Control-Allow-Origin}; a preflight {@code OPTIONS} from them is answered
 * 204, from anyone else 403.
 */
public final class ApiServer {
  /** Request bodies above this are refused with 413. */
  public static final int MAX_BODY_BYTES = 1 << 20;
  /** An over-size body is read and discarded up to this many bytes before the 413 (B15). */
  public static final int MAX_DRAIN_BYTES = 16 << 20;
  public static final List<String> ALLOWED_ORIGINS =
      Collections.unmodifiableList(List.of("http://localhost:5173", "http://127.0.0.1:5173"));
  private static final Logger LOG = Logger.getLogger(ApiServer.class.getName());

  /** One request, as a handler sees it. */
  public static final class Request {
    final String method;
    final String path;
    final String query;
    final byte[] body;
    final Map<String, String> params;

    Request(String method, String path, String query, byte[] body, Map<String, String> params) {
      this.method = method;
      this.path = path;
      this.query = query;
      this.body = body;
      this.params = params;
    }

    public String method() {
      return method;
    }

    public String path() {
      return path;
    }

    /** The raw query string, or null. */
    public String query() {
      return query;
    }

    /** A {@code {name}} segment of the route pattern, URL-decoded. */
    public String param(String name) {
      return params.get(name);
    }

    /** A query parameter, URL-decoded, or null if absent (first value wins). */
    public String queryParam(String name) {
      if (query == null || query.isEmpty()) return null;
      for (String part : query.split("&")) {
        int eq = part.indexOf('=');
        String k = decode(eq < 0 ? part : part.substring(0, eq));
        if (k.equals(name)) return eq < 0 ? "" : decode(part.substring(eq + 1));
      }
      return null;
    }

    /** True if the body is empty or only whitespace. */
    public boolean bodyIsBlank() {
      for (byte b : body) {
        if (b != ' ' && b != '\t' && b != '\r' && b != '\n') return false;
      }
      return true;
    }

    /** The body parsed as JSON (numbers as Long/Double); 400 BAD_JSON if invalid. */
    public Object json() throws ApiException {
      return parse(false);
    }

    /** The body parsed as JSON with exact decimals (BigDecimal); 400 BAD_JSON if invalid. */
    public Object jsonExact() throws ApiException {
      return parse(true);
    }

    private Object parse(boolean exact) throws ApiException {
      String text = new String(body, StandardCharsets.UTF_8);
      try {
        return exact ? Json.parseExact(text) : Json.parse(text);
      } catch (Json.JsonException e) {
        throw new ApiException(400, "BAD_JSON", e.getMessage());
      }
    }
  }

  /** A response with an explicit status (handlers may also return a plain body: 200). */
  public static final class Response {
    final int status;
    final Object body;

    public Response(int status, Object body) {
      this.status = status;
      this.body = body;
    }
  }

  /** Handles one route; returns the JSON response body or a {@link Response}. */
  public interface Handler {
    Object handle(Request request) throws Exception;
  }

  /** Handles a streaming route: owns the exchange (headers, body, close) from here on. */
  public interface StreamHandler {
    void handle(HttpExchange exchange, Request request) throws Exception;
  }

  private static final class Route {
    final String method;
    final String[] segments; // "{x}" segments capture
    final Handler handler;
    final StreamHandler stream;

    Route(String method, String pattern, Handler handler, StreamHandler stream) {
      this.method = method;
      this.segments = pattern.split("/", -1);
      this.handler = handler;
      this.stream = stream;
    }

    /** Captured params if the path matches, else null. */
    Map<String, String> match(String[] path) {
      if (path.length != segments.length) return null;
      Map<String, String> params = new LinkedHashMap<>();
      for (int i = 0; i < path.length; i++) {
        String seg = segments[i];
        if (seg.startsWith("{") && seg.endsWith("}")) {
          if (path[i].isEmpty()) return null;
          params.put(seg.substring(1, seg.length() - 1), decode(path[i]));
        } else if (!seg.equals(path[i])) {
          return null;
        }
      }
      return params;
    }
  }

  static {
    // The JDK server writes headers and body separately; without TCP_NODELAY every
    // response waits out Nagle + delayed ACK, about 40 ms (backend/BUGS.md B9). Must be set
    // before the server classes initialise.
    if (System.getProperty("sun.net.httpserver.nodelay") == null) {
      System.setProperty("sun.net.httpserver.nodelay", "true");
    }
  }

  private final HttpServer server;
  private final ExecutorService httpThreads;
  private final List<Route> routes = new ArrayList<>();
  private volatile StaticFiles staticFiles;

  public ApiServer(InetSocketAddress address) throws IOException {
    this.server = HttpServer.create(address, 64);
    // cached: an SSE stream holds its thread for as long as the client stays connected
    this.httpThreads = Executors.newCachedThreadPool(r -> {
      Thread t = new Thread(r, "http");
      t.setDaemon(true);
      return t;
    });
    server.setExecutor(httpThreads);
    server.createContext("/", this::dispatch);
  }

  /** Registers a handler for a method and a path pattern. Call before {@link #start()}. */
  public synchronized ApiServer route(String method, String pattern, Handler handler) {
    routes.add(new Route(method, pattern, handler, null));
    return this;
  }

  /** Registers a streaming handler (e.g. Server-Sent Events) for a method and pattern. */
  public synchronized ApiServer routeStream(String method, String pattern, StreamHandler handler) {
    routes.add(new Route(method, pattern, null, handler));
    return this;
  }

  /**
   * Serves {@code dir} (the built dashboard) for every path outside {@code /api}; see
   * {@link StaticFiles}. Fails if the directory has no index.html.
   */
  public void serveStatic(java.nio.file.Path dir) throws IOException {
    staticFiles = new StaticFiles(dir);
  }

  public void start() {
    server.start();
  }

  public void stop() {
    server.stop(0);
    httpThreads.shutdownNow();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  private void dispatch(HttpExchange ex) {
    StaticFiles files = staticFiles;
    if (files != null && !StaticFiles.isApiPath(ex.getRequestURI().getRawPath())) {
      files.serve(ex); // the dashboard; /api/... never reaches it
      return;
    }
    String origin = ex.getRequestHeaders().getFirst("Origin");
    boolean allowedOrigin = origin != null && ALLOWED_ORIGINS.contains(origin);
    if (allowedOrigin) {
      ex.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
      ex.getResponseHeaders().set("Vary", "Origin");
    }
    if (ex.getRequestMethod().equals("OPTIONS")) {
      preflight(ex, origin, allowedOrigin);
      return;
    }
    int status = 200;
    Object body;
    try {
      String path = ex.getRequestURI().getRawPath();
      Route route = null;
      Map<String, String> params = null;
      Set<String> allowed = new LinkedHashSet<>();
      String[] parts = path.split("/", -1);
      synchronized (this) {
        for (Route r : routes) {
          Map<String, String> p = r.match(parts);
          if (p == null) continue;
          allowed.add(r.method);
          if (route == null && r.method.equals(ex.getRequestMethod())) {
            route = r;
            params = p;
          }
        }
      }
      if (route == null) {
        if (allowed.isEmpty()) {
          throw new ApiException(404, "NOT_FOUND", "no such endpoint: " + path);
        }
        ex.getResponseHeaders().set("Allow", String.join(", ", allowed));
        throw new ApiException(405, "METHOD_NOT_ALLOWED", ex.getRequestMethod()
            + " is not supported on " + path);
      }
      byte[] bodyBytes;
      try {
        bodyBytes = readBody(ex.getRequestBody());
      } catch (IOException e) { // the client sent less than it announced, or hung up
        throw new ApiException(400, "INCOMPLETE_BODY", "request body ended early");
      }
      Request req = new Request(ex.getRequestMethod(), path, ex.getRequestURI().getRawQuery(),
          bodyBytes, params);
      if (route.stream != null) {
        route.stream.handle(ex, req); // owns the exchange from here
        return;
      }
      Object result = route.handler.handle(req);
      if (result instanceof Response) {
        status = ((Response) result).status;
        body = ((Response) result).body;
      } else {
        body = result;
      }
    } catch (ApiException e) {
      status = e.status;
      body = error(e.code, e.getMessage());
    } catch (Exception | StackOverflowError e) {
      status = 500;
      body = error("INTERNAL", "internal error");
      LOG.log(Level.WARNING, "handler failed for " + ex.getRequestMethod() + " "
          + ex.getRequestURI().getPath(), e);
    }
    send(ex, status, body);
  }

  private static void preflight(HttpExchange ex, String origin, boolean allowedOrigin) {
    if (!allowedOrigin) {
      send(ex, 403, error("FORBIDDEN_ORIGIN", "origin not allowed: " + origin));
      return;
    }
    ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
    ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    ex.getResponseHeaders().set("Access-Control-Max-Age", "600");
    try {
      ex.sendResponseHeaders(204, -1);
    } catch (IOException e) {
      LOG.log(Level.FINE, "client went away", e);
    } finally {
      ex.close();
    }
  }

  private static String decode(String s) {
    try {
      return URLDecoder.decode(s, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return s; // a malformed %-escape: keep it raw, the handler will reject it
    }
  }

  /**
   * The request body, at most {@link #MAX_BODY_BYTES}. A larger body is refused with 413, but
   * only after the rest of it (up to {@link #MAX_DRAIN_BYTES}) has been read and discarded:
   * closing a connection with unread data resets it, and a client still uploading then sees
   * "connection reset" instead of the 413 (B15). Beyond the drain limit the connection is
   * cut, so an endless body cannot hold a thread.
   */
  private static byte[] readBody(InputStream in) throws IOException, ApiException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    long seen = 0;
    while ((n = in.read(buf)) > 0) {
      seen += n;
      if (seen > MAX_BODY_BYTES) {
        while (seen <= MAX_DRAIN_BYTES && (n = in.read(buf)) > 0) seen += n;
        throw new ApiException(413, "BODY_TOO_LARGE", "request body exceeds " + MAX_BODY_BYTES
            + " bytes");
      }
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  /** The API's one error shape. */
  public static Map<String, Object> error(String code, String message) {
    Map<String, Object> inner = new LinkedHashMap<>();
    inner.put("code", code);
    inner.put("message", message);
    Map<String, Object> outer = new LinkedHashMap<>();
    outer.put("error", inner);
    return outer;
  }

  private static void send(HttpExchange ex, int status, Object body) {
    try {
      byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
      ex.sendResponseHeaders(status, bytes.length);
      try (OutputStream out = ex.getResponseBody()) {
        out.write(bytes);
      }
    } catch (IOException | RuntimeException e) {
      LOG.log(Level.FINE, "client went away", e); // the client hung up: nothing to answer
    } finally {
      ex.close();
    }
  }

  // ---------------------------------------------------------------- wiring

  /** Registers the Stage 2 market-data routes, backed by {@code market} on {@code engine}. */
  public static void marketRoutes(ApiServer server, EngineThread engine, MarketService market) {
    server.route("POST", "/api/market/rates", req -> {
      Object payload = req.json(); // parsed on the HTTP thread
      return engine.call(() -> market.ingest(payload));
    });
    server.route("GET", "/api/market/rates/status", req -> engine.call(market::status));
  }
}
