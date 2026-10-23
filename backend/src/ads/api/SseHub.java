package ads.api;

import ads.util.Json;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Server-Sent Events for {@code GET /api/stream} (doc 08 Stage 3).
 *
 * <p>The engine thread calls {@link #publish}: the event is formatted once
 * ({@code id:}, {@code event:}, {@code data:}) and OFFERED to every client's bounded queue,
 * which never blocks. A client whose queue is full (slow, or not reading at all) is
 * dropped: removed at once, its connection cut by a separate reaper thread (B16), so not even
 * closing a stuck socket can stall the engine. Each client's own HTTP thread drains its
 * queue to the socket, sends {@code retry:} first and a comment heartbeat every
 * {@link #HEARTBEAT_MS} when idle.
 */
public final class SseHub implements EventSink {
  public static final int QUEUE_CAPACITY = 256;
  public static final int MAX_CLIENTS = 32;
  public static final long HEARTBEAT_MS = 15_000;
  public static final long RETRY_MS = 3_000;
  private static final Logger LOG = Logger.getLogger(SseHub.class.getName());

  private static final class Client {
    final HttpExchange exchange;
    final BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    volatile boolean dropped;

    Client(HttpExchange exchange) {
      this.exchange = exchange;
    }
  }

  private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();
  private final AtomicLong nextId = new AtomicLong(1);
  private final AtomicLong droppedClients = new AtomicLong();
  private final ExecutorService reaper = Executors.newCachedThreadPool(r -> {
    Thread t = new Thread(r, "sse-reaper");
    t.setDaemon(true);
    return t;
  });
  private volatile boolean closed;

  /** Formats one event and offers it to every client; never blocks. */
  @Override
  public void publish(String type, Map<String, Object> data) {
    if (clients.isEmpty()) {
      nextId.incrementAndGet();
      return;
    }
    String frame = "id: " + nextId.getAndIncrement() + "\nevent: " + type + "\ndata: "
        + Json.write(data) + "\n\n";
    for (Client c : clients) {
      if (!c.queue.offer(frame)) drop(c);
    }
  }

  private void drop(Client c) {
    if (c.dropped) return;
    c.dropped = true;
    clients.remove(c);
    droppedClients.incrementAndGet();
    c.queue.clear();
    reaper.execute(() -> {
      // A client that stopped reading leaves its stream thread blocked in a socket write that
      // never completes, and exchange.close() would block the same way: cut the socket so
      // both return at once (B16). Without access, fall back to close(), off our thread.
      if (!abortConnection(c.exchange)) c.exchange.close();
    });
  }

  private static volatile boolean warnedNoAbort;

  /**
   * Closes the TCP connection under {@code ex} immediately, which makes a write blocked on it
   * fail at once. The JDK's HTTP server has no public way to do this or to time out a write,
   * so this reads its internal fields; the JVM must run with
   * {@code --add-opens jdk.httpserver/sun.net.httpserver=ALL-UNNAMED} (run.sh and test.sh do).
   * Returns false, and warns once, if that is not possible.
   */
  static boolean abortConnection(HttpExchange ex) {
    try {
      Object impl = field(ex, "impl");
      Object connection = field(impl, "connection");
      ((java.nio.channels.Channel) field(connection, "chan")).close();
      return true;
    } catch (NoSuchFieldException e) {
      return false; // not the JDK's exchange (a test double): nothing to cut
    } catch (ReflectiveOperationException | RuntimeException | IOException e) {
      if (!warnedNoAbort) {
        warnedNoAbort = true;
        LOG.log(Level.WARNING, "cannot cut off a stalled event-stream client (" + e
            + "); start the JVM with --add-opens jdk.httpserver/sun.net.httpserver=ALL-UNNAMED");
      }
      return false;
    }
  }

  private static Object field(Object o, String name) throws ReflectiveOperationException {
    for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
      try {
        java.lang.reflect.Field f = k.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
      } catch (NoSuchFieldException e) {
        // look in the superclass
      }
    }
    throw new NoSuchFieldException(name);
  }

  public int clientCount() {
    return clients.size();
  }

  public long droppedClients() {
    return droppedClients.get();
  }

  /** The stream handler: runs on the client's HTTP thread until it disconnects or is dropped. */
  public void serve(HttpExchange ex) throws IOException, ApiException {
    if (closed) throw new ApiException(503, "SHUTTING_DOWN", "the server is stopping");
    if (clients.size() >= MAX_CLIENTS) {
      throw new ApiException(503, "TOO_MANY_STREAMS", "at most " + MAX_CLIENTS
          + " event streams at once");
    }
    Client c = new Client(ex);
    ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
    ex.getResponseHeaders().set("Cache-Control", "no-cache");
    ex.getResponseHeaders().set("X-Accel-Buffering", "no");
    ex.sendResponseHeaders(200, 0); // chunked: open-ended
    OutputStream out = ex.getResponseBody();
    clients.add(c);
    try {
      write(out, "retry: " + RETRY_MS + "\n: connected\n\n");
      while (!c.dropped && !closed) {
        String frame = c.queue.poll(HEARTBEAT_MS, TimeUnit.MILLISECONDS);
        if (c.dropped || closed) break;
        write(out, frame == null ? ": heartbeat\n\n" : frame);
      }
    } catch (IOException | InterruptedException e) {
      LOG.log(Level.FINE, "event stream ended", e); // the client went away
    } finally {
      clients.remove(c);
      try {
        ex.close();
      } catch (RuntimeException ignored) {
        // already closed by the reaper
      }
    }
  }

  private static void write(OutputStream out, String text) throws IOException {
    out.write(text.getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  /** Ends every stream (shutdown). */
  public void close() {
    closed = true;
    for (Client c : clients) drop(c);
    reaper.shutdown();
  }
}
