package ads.api;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serves the built dashboard ({@code serve --static-dir frontend/dist}) so a demo needs one
 * port. Used for every path outside {@code /api}; the API is untouched.
 *
 * <ul>
 *   <li>GET and HEAD only (405 otherwise).</li>
 *   <li>The raw path is decoded once. A path containing a NUL byte, a backslash, or a
 *       {@code .} or {@code ..} segment, however it was encoded, is 400. A doubled encoding
 *       ({@code %252e%252e}) decodes to the literal name {@code %2e%2e}, which is simply not
 *       a file.</li>
 *   <li>The resolved real path (symlinks followed) must lie inside the directory's real path:
 *       a symlink that leaves it is never served.</li>
 *   <li>Anything that is not a regular file inside the directory falls back to
 *       {@code index.html}, so the dashboard's own paths load it.</li>
 *   <li>{@code Cache-Control: no-cache} for {@code index.html} (and every other file);
 *       {@code public, max-age=31536000, immutable} for the hashed files under
 *       {@code assets/}.</li>
 * </ul>
 */
final class StaticFiles {
  private static final Logger LOG = Logger.getLogger(StaticFiles.class.getName());
  static final String IMMUTABLE = "public, max-age=31536000, immutable";
  private static final Map<String, String> TYPES = Map.ofEntries(
      Map.entry("html", "text/html; charset=utf-8"),
      Map.entry("js", "text/javascript; charset=utf-8"),
      Map.entry("mjs", "text/javascript; charset=utf-8"),
      Map.entry("css", "text/css; charset=utf-8"),
      Map.entry("svg", "image/svg+xml"),
      Map.entry("json", "application/json; charset=utf-8"),
      Map.entry("map", "application/json; charset=utf-8"),
      Map.entry("ico", "image/x-icon"),
      Map.entry("png", "image/png"),
      Map.entry("txt", "text/plain; charset=utf-8"),
      Map.entry("woff2", "font/woff2"));

  private final Path root;

  /** {@code dir} must exist and hold an {@code index.html}. */
  StaticFiles(Path dir) throws IOException {
    this.root = dir.toRealPath();
    if (!Files.isRegularFile(root.resolve("index.html"))) {
      throw new IOException(dir + " has no index.html (build the dashboard: cd frontend && npm run build)");
    }
  }

  /** True if this path belongs to the API (and must never be served as a file). */
  static boolean isApiPath(String rawPath) {
    return rawPath.equals("/api") || rawPath.startsWith("/api/");
  }

  void serve(HttpExchange ex) {
    try {
      String method = ex.getRequestMethod();
      if (!method.equals("GET") && !method.equals("HEAD")) {
        ex.getResponseHeaders().set("Allow", "GET, HEAD");
        error(ex, 405, "METHOD_NOT_ALLOWED", method + " is not supported on static files");
        return;
      }
      Path file = resolve(ex.getRequestURI().getRawPath());
      if (file == null) {
        error(ex, 400, "BAD_REQUEST", "malformed path");
        return;
      }
      if (!isServable(file)) file = root.resolve("index.html"); // the dashboard's own routes
      send(ex, file, method.equals("HEAD"));
    } catch (IOException | RuntimeException e) {
      LOG.log(Level.FINE, "static file not served", e);
      try {
        error(ex, 404, "NOT_FOUND", "not found");
      } catch (IOException ignored) {
        // the client went away
      }
    } finally {
      ex.close();
    }
  }

  /**
   * The file a raw request path names inside the root, or null for a malformed path (a NUL,
   * a backslash, a dot segment, or an escape that does not decode). The result may not exist.
   */
  Path resolve(String rawPath) {
    String path = decode(rawPath);
    if (path == null || path.indexOf('\0') >= 0 || path.indexOf('\\') >= 0) return null;
    Path p = root;
    for (String seg : path.split("/")) {
      if (seg.isEmpty()) continue; // leading, doubled or trailing slashes
      if (seg.equals(".") || seg.equals("..")) return null;
      p = p.resolve(seg);
      if (!p.startsWith(root)) return null; // e.g. a drive letter on Windows
    }
    return p.equals(root) ? root.resolve("index.html") : p;
  }

  /** A regular file whose real path (symlinks followed) is inside the root. */
  boolean isServable(Path p) {
    try {
      if (!Files.isRegularFile(p)) return false;
      return p.toRealPath().startsWith(root);
    } catch (IOException e) {
      return false;
    }
  }

  /** Percent-decoding of a path, exactly once; null if an escape is malformed. */
  static String decode(String raw) {
    byte[] out = new byte[raw.length()];
    int n = 0;
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c == '%') {
        if (i + 2 >= raw.length()) return null;
        int hi = Character.digit(raw.charAt(i + 1), 16);
        int lo = Character.digit(raw.charAt(i + 2), 16);
        if (hi < 0 || lo < 0) return null;
        out[n++] = (byte) (hi * 16 + lo);
        i += 2;
      } else if (c > 0x7e || c < 0x20) {
        return null; // a raw request line is ASCII
      } else {
        out[n++] = (byte) c;
      }
    }
    return new String(out, 0, n, StandardCharsets.UTF_8);
  }

  private void send(HttpExchange ex, Path file, boolean head) throws IOException {
    String name = file.getFileName().toString();
    int dot = name.lastIndexOf('.');
    String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    boolean asset = root.relativize(file).startsWith("assets");
    ex.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(ext, "application/octet-stream"));
    ex.getResponseHeaders().set("Cache-Control", asset ? IMMUTABLE : "no-cache");
    ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    byte[] bytes = Files.readAllBytes(file);
    if (head) {
      ex.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
      ex.sendResponseHeaders(200, -1);
      return;
    }
    ex.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void error(HttpExchange ex, int status, String code, String message) throws IOException {
    byte[] bytes = ("{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}")
        .getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(bytes);
    }
  }
}
