package ads.api;

import ads.util.Json;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The backend's HTTP server ({@code com.sun.net.httpserver}, JDK only). Stage 2 registers
 * two routes; Stage 3 grows this same server into the full gateway by adding routes with
 * {@link #route}. A handful of HTTP threads parse and answer requests; anything touching
 * engine or scanner state goes through the single {@link EngineThread}.
 *
 * <p>Every error, including unknown paths, wrong methods, oversized bodies, malformed JSON
 * and unexpected failures, is answered as {@code {"error":{"code":"...","message":"..."}}}
 * with a proper status, and the server keeps serving.
 */
public final class ApiServer {
  /** Request bodies above this are refused with 413. */
  public static final int MAX_BODY_BYTES = 1 << 20;
  private static final Logger LOG = Logger.getLogger(ApiServer.class.getName());

  /** One request, as a handler sees it. */
  public static final class Request {
    final String method;
    final String path;
    final String query;
    final byte[] body;

    Request(String method, String path, String query, byte[] body) {
      this.method = method;
      this.path = path;
      this.query = query;
      this.body = body;
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

    /** The body parsed as JSON; 400 BAD_JSON if it is not valid JSON. */
    public Object json() throws ApiException {
      String text = new String(body, StandardCharsets.UTF_8);
      try {
        return Json.parse(text);
      } catch (Json.JsonException e) {
        throw new ApiException(400, "BAD_JSON", e.getMessage());
      }
    }
  }

  /** Handles one route; returns the JSON response body (status 200). */
  public interface Handler {
    Object handle(Request request) throws Exception;
  }

  private final HttpServer server;
  private final ExecutorService httpThreads;
  private final Map<String, Map<String, Handler>> routes = new LinkedHashMap<>(); // path -> method -> handler

  public ApiServer(InetSocketAddress address) throws IOException {
    this.server = HttpServer.create(address, 64);
    this.httpThreads = Executors.newFixedThreadPool(4, r -> {
      Thread t = new Thread(r, "http");
      t.setDaemon(true);
      return t;
    });
    server.setExecutor(httpThreads);
    server.createContext("/", this::dispatch);
  }

  /** Registers a handler for an exact method and path. Call before {@link #start()}. */
  public synchronized ApiServer route(String method, String path, Handler handler) {
    routes.computeIfAbsent(path, p -> new LinkedHashMap<>()).put(method, handler);
    return this;
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
    int status = 200;
    Object body;
    try {
      body = handle(ex);
    } catch (ApiException e) {
      status = e.status;
      body = error(e.code, e.getMessage());
      if (status == 405) ex.getResponseHeaders().set("Allow", allowed(ex.getRequestURI().getPath()));
    } catch (Exception | StackOverflowError e) {
      status = 500;
      body = error("INTERNAL", "internal error");
      LOG.log(Level.WARNING, "handler failed for " + ex.getRequestMethod() + " "
          + ex.getRequestURI().getPath(), e);
    }
    send(ex, status, body);
  }

  private Object handle(HttpExchange ex) throws Exception {
    String path = ex.getRequestURI().getPath();
    Map<String, Handler> byMethod;
    synchronized (this) {
      byMethod = routes.get(path);
    }
    if (byMethod == null) {
      throw new ApiException(404, "NOT_FOUND", "no such endpoint: " + path);
    }
    Handler h = byMethod.get(ex.getRequestMethod());
    if (h == null) {
      throw new ApiException(405, "METHOD_NOT_ALLOWED", ex.getRequestMethod() + " is not supported on "
          + path);
    }
    byte[] bodyBytes;
    try {
      bodyBytes = readBody(ex.getRequestBody());
    } catch (IOException e) { // the client sent less than it announced, or hung up
      throw new ApiException(400, "INCOMPLETE_BODY", "request body ended early");
    }
    return h.handle(new Request(ex.getRequestMethod(), path, ex.getRequestURI().getRawQuery(),
        bodyBytes));
  }

  private synchronized String allowed(String path) {
    Map<String, Handler> byMethod = routes.get(path);
    return byMethod == null ? "" : String.join(", ", byMethod.keySet());
  }

  private static byte[] readBody(InputStream in) throws IOException, ApiException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) > 0) {
      if (out.size() + n > MAX_BODY_BYTES) {
        throw new ApiException(413, "BODY_TOO_LARGE", "request body exceeds " + MAX_BODY_BYTES
            + " bytes");
      }
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  private static Map<String, Object> error(String code, String message) {
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
