package ads.api;

import ads.util.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The engine's three counters (next order id, next trade id, sequence number) in
 * {@code <data-dir>/counters.json}, so ids never repeat across restarts (doc 08 Stage 3
 * addendum, item 2). Written atomically (temp file, fsync, rename) alongside the Vault.
 *
 * <p>While the server runs, each write stores the counters plus the reserve ({@link #RESERVE}
 * by default) and {@code "clean": false}. The App writes once at startup, before the first
 * command, again on every periodic flush, and at once whenever a counter has used half of
 * the reserve on disk. So, as long as no single command uses half the reserve, no id at or
 * past the values on disk has ever been issued, and if
 * the process dies, the next session starts past every id this one issued (ids may skip
 * ahead, never repeat). A graceful
 * shutdown writes the exact counters with {@code "clean": true}.
 */
final class CounterStore {
  /** Headroom written while running; far more ids than the API can issue between flushes. */
  static final long RESERVE = 1_000_000;

  private final Path file;
  private final long reserve;

  CounterStore(Path file, long reserve) {
    if (reserve < 1) throw new IllegalArgumentException("reserve must be >= 1");
    this.file = file;
    this.reserve = reserve;
  }

  long reserve() {
    return reserve;
  }

  /** {nextOrderId, nextTradeId, seq}, or null if there is no file yet. */
  long[] load() throws IOException {
    if (!Files.exists(file)) return null;
    Object v;
    try {
      v = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
    } catch (Json.JsonException e) {
      throw new IOException(file + ": " + e.getMessage(), e);
    }
    if (!(v instanceof Map)) throw new IOException(file + ": not a JSON object");
    Map<?, ?> m = (Map<?, ?>) v;
    long[] out = new long[3];
    String[] keys = { "nextOrderId", "nextTradeId", "seq" };
    for (int i = 0; i < 3; i++) {
      Object x = m.get(keys[i]);
      if (!(x instanceof Long) || (Long) x < (i == 2 ? 0 : 1)) {
        throw new IOException(file + ": " + keys[i] + " missing or invalid");
      }
      out[i] = (Long) x;
    }
    return out;
  }

  /** Writes the counters; {@code clean} stores them exactly, otherwise with the reserve. */
  void write(long nextOrderId, long nextTradeId, long seq, boolean clean) throws IOException {
    long add = clean ? 0 : reserve;
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("nextOrderId", nextOrderId + add);
    m.put("nextTradeId", nextTradeId + add);
    m.put("seq", seq + add);
    m.put("clean", clean);
    Path dir = file.toAbsolutePath().getParent();
    Files.createDirectories(dir);
    Path tmp = dir.resolve(file.getFileName() + ".tmp");
    try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, StandardOpenOption.SYNC)) {
      out.write((Json.write(m) + "\n").getBytes(StandardCharsets.UTF_8));
    }
    try {
      Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
