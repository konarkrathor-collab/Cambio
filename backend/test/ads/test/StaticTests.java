package ads.test;

import ads.api.App;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** Stage 7a: serve --static-dir (the built dashboard on the API's port). */
final class StaticTests {
  private StaticTests() {
  }

  private static final String SECRET = "TOP-SECRET-OUTSIDE-THE-DASHBOARD";
  private static final String INDEX = "<!doctype html><title>dashboard index</title>";

  static void run(TestRunner r) {
    r.section("static dashboard: serve --static-dir (Stage 7a)");
    r.check("content types and cache headers: html, js, css, svg, json, ico; index no-cache,"
        + " assets/ immutable", () -> withSite(StaticTests::typesAndCaching));
    r.check("non-/api paths that are not files fall back to index.html; /api stays JSON",
        () -> withSite(StaticTests::fallbackAndApi));
    r.check("HEAD gets the headers only; other methods 405", () -> withSite(StaticTests::methods));
    r.check("path traversal: .., encoded and doubled encodings, absolute paths, backslashes,"
        + " NUL bytes, symlinks out of the directory: never served", () -> withSite(StaticTests::traversal));
    r.check("a static dir without index.html refuses to start", StaticTests::needsIndex);
  }

  private static void check(boolean ok, String what) {
    if (!ok) throw new AssertionError(what);
  }

  /** One raw HTTP/1.1 exchange: the request line exactly as given. */
  static final class Reply {
    int status;
    final Map<String, String> headers = new LinkedHashMap<>();
    String body = "";
  }

  static Reply raw(int port, String method, String rawPath) throws Exception {
    try (Socket s = new Socket("127.0.0.1", port)) {
      s.setSoTimeout(5_000);
      OutputStream out = s.getOutputStream();
      out.write((method + " " + rawPath + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
          .getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      InputStream in = s.getInputStream();
      ByteArrayOutputStream all = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      try {
        while ((n = in.read(buf)) > 0) all.write(buf, 0, n);
      } catch (java.net.SocketException closed) {
        // a reset after the response: what was read is the answer
      }
      String text = all.toString(StandardCharsets.ISO_8859_1);
      Reply r = new Reply();
      int headEnd = text.indexOf("\r\n\r\n");
      String head = headEnd < 0 ? text : text.substring(0, headEnd);
      r.body = headEnd < 0 ? "" : text.substring(headEnd + 4);
      String[] lines = head.split("\r\n");
      r.status = lines.length > 0 && lines[0].length() >= 12 ? Integer.parseInt(lines[0].substring(9, 12)) : 0;
      for (int i = 1; i < lines.length; i++) {
        int c = lines[i].indexOf(':');
        if (c > 0) r.headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
      }
      return r;
    }
  }

  private interface SiteBody {
    TestRunner.Result run(int port, Path site, Path outside) throws Exception;
  }

  private static TestRunner.Result withSite(SiteBody body) throws Exception {
    Path base = Files.createTempDirectory("ads-static-");
    Path site = base.resolve("dist");
    Path outside = base.resolve("secret.txt");
    Files.createDirectories(site.resolve("assets"));
    Files.writeString(site.resolve("index.html"), INDEX);
    Files.writeString(site.resolve("assets/index-Ab12Cd.js"), "console.log('app');");
    Files.writeString(site.resolve("assets/index-Ef34Gh.css"), "body{}");
    Files.writeString(site.resolve("icon.svg"), "<svg/>");
    Files.writeString(site.resolve("data.json"), "{\"a\":1}");
    Files.write(site.resolve("favicon.ico"), new byte[] {0, 0, 1, 0});
    Files.writeString(outside, SECRET);
    Path secretDir = base.resolve("private");
    Files.createDirectories(secretDir);
    Files.writeString(secretDir.resolve("secret.txt"), SECRET);
    Files.createSymbolicLink(site.resolve("link.txt"), outside);
    Files.createSymbolicLink(site.resolve("linkdir"), secretDir);
    App.Config cfg = new App.Config();
    cfg.port = 0;
    cfg.dataDir = base.resolve("data");
    cfg.seedDemoAccounts = true;
    cfg.staticDir = site;
    App app = App.start(cfg);
    try {
      return body.run(app.port(), site, outside);
    } finally {
      app.stop();
      try (Stream<Path> s = Files.walk(base)) {
        s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
      }
    }
  }

  private static TestRunner.Result typesAndCaching(int port, Path site, Path outside) throws Exception {
    String[][] cases = {
        {"/", "text/html; charset=utf-8", "no-cache", INDEX},
        {"/index.html", "text/html; charset=utf-8", "no-cache", INDEX},
        {"/assets/index-Ab12Cd.js", "text/javascript; charset=utf-8", "public, max-age=31536000, immutable", "console.log('app');"},
        {"/assets/index-Ef34Gh.css", "text/css; charset=utf-8", "public, max-age=31536000, immutable", "body{}"},
        {"/icon.svg", "image/svg+xml", "no-cache", "<svg/>"},
        {"/data.json", "application/json; charset=utf-8", "no-cache", "{\"a\":1}"},
        {"/favicon.ico", "image/x-icon", "no-cache", null},
    };
    for (String[] c : cases) {
      Reply r = raw(port, "GET", c[0]);
      check(r.status == 200, c[0] + " -> " + r.status);
      check(c[1].equals(r.headers.get("content-type")), c[0] + " content-type " + r.headers.get("content-type"));
      check(c[2].equals(r.headers.get("cache-control")), c[0] + " cache-control " + r.headers.get("cache-control"));
      check("nosniff".equals(r.headers.get("x-content-type-options")), c[0] + " nosniff");
      if (c[3] != null) check(r.body.equals(c[3]), c[0] + " body " + r.body);
    }
    return TestRunner.Result.pass(cases.length + " files with the right type and caching");
  }

  private static TestRunner.Result fallbackAndApi(int port, Path site, Path outside) throws Exception {
    for (String p : new String[] {"/market", "/arbitrage/graph", "/no/such/file.js", "/assets/missing.js", "/api-docs", "/%61pi/health"}) {
      Reply r = raw(port, "GET", p);
      check(r.status == 200 && r.body.equals(INDEX) && "no-cache".equals(r.headers.get("cache-control")),
          p + " -> " + r.status + " " + r.body);
    }
    Reply health = raw(port, "GET", "/api/health");
    check(health.status == 200 && health.body.contains("\"status\":\"ok\""), "/api/health " + health.body);
    for (String p : new String[] {"/api/nope", "/api", "/api/../index.html", "/api/orderbook/../../index.html"}) {
      Reply r = raw(port, "GET", p);
      check(r.status == 404 && r.body.contains("\"NOT_FOUND\"") && !r.body.contains("dashboard index"),
          p + " -> " + r.status + " " + r.body);
    }
    return TestRunner.Result.pass("6 dashboard paths -> index.html; /api/health works; 4 unknown /api paths -> JSON 404");
  }

  private static TestRunner.Result methods(int port, Path site, Path outside) throws Exception {
    Reply head = raw(port, "HEAD", "/assets/index-Ab12Cd.js");
    check(head.status == 200 && head.body.isEmpty() && "19".equals(head.headers.get("content-length")),
        "HEAD " + head.status + " " + head.headers);
    for (String m : new String[] {"POST", "PUT", "DELETE"}) {
      Reply r = raw(port, m, "/index.html");
      check(r.status == 405 && "GET, HEAD".equals(r.headers.get("allow")), m + " -> " + r.status);
    }
    return TestRunner.Result.pass("HEAD: headers and Content-Length, no body; POST/PUT/DELETE 405");
  }

  private static TestRunner.Result traversal(int port, Path site, Path outside) throws Exception {
    String abs = outside.toAbsolutePath().toString();
    String[] attempts = {
        "/../secret.txt",
        "/assets/../../secret.txt",
        "/./../secret.txt",
        "/%2e%2e/secret.txt",
        "/%2E%2E/secret.txt",
        "/%2e%2e%2fsecret.txt",
        "/..%2fsecret.txt",
        "/assets/%2e%2e/%2e%2e/secret.txt",
        "/%252e%252e/secret.txt",
        "/%252e%252e%252fsecret.txt",
        "/..%5csecret.txt",
        "/..\\secret.txt",
        "/assets\\..\\..\\secret.txt",
        "/%5c..%5csecret.txt",
        "/%00",
        "/index.html%00.js",
        "/secret.txt%00",
        abs,
        "/" + abs,
        "//" + abs,
        "/link.txt",
        "/linkdir/secret.txt",
        "/linkdir/../secret.txt",
        "/%c0%ae%c0%ae/secret.txt",
        "/..;/secret.txt",
    };
    StringBuilder summary = new StringBuilder();
    int bad = 0;
    for (String a : attempts) {
      Reply r = raw(port, "GET", a);
      check(!r.body.contains(SECRET), "LEAKED the outside file via " + a);
      boolean safe = r.status == 400 || r.status == 404 || (r.status == 200 && r.body.equals(INDEX));
      check(safe, a + " -> " + r.status + " " + r.body);
      if (r.status == 400) bad++;
    }
    summary.append(attempts.length).append(" attempts, none served the outside file: ").append(bad)
        .append(" refused 400, the rest got index.html or 404");
    return TestRunner.Result.pass(summary.toString());
  }

  private static TestRunner.Result needsIndex() throws Exception {
    Path base = Files.createTempDirectory("ads-static-empty-");
    App.Config cfg = new App.Config();
    cfg.port = 0;
    cfg.dataDir = base.resolve("data");
    cfg.staticDir = base;
    try {
      App app = App.start(cfg);
      app.stop();
      return TestRunner.Result.fail("started without an index.html");
    } catch (java.io.IOException expected) {
      return TestRunner.Result.pass("refused: " + expected.getMessage());
    } finally {
      try (Stream<Path> s = Files.walk(base)) {
        s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
      }
    }
  }
}
