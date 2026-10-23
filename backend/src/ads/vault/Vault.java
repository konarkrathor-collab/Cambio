package ads.vault;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Trader accounts. Two layers, as doc 05 §4 describes:
 *
 * <ul>
 *   <li>the ledger: every account, in an own separate-chaining hash table (chains through
 *       {@link Account#ledgerNext}) plus an array in opening order, persisted to a JSON
 *       Lines file ({@code backend/data/accounts.jsonl} by default), one account per line;</li>
 *   <li>the hot path: an {@link LruCache} of active traders in front of the ledger, which
 *       the matching engine's margin checks and settlements go through.</li>
 * </ul>
 *
 * <p>Cached and ledger entries are the same {@link Account} objects, so an eviction never
 * loses a balance. {@link #flush()} rewrites the whole file atomically: it writes a temp
 * file in the same directory, forces it to disk, then renames it over the old file, so a
 * crash leaves either the old file or the new one, never a torn one. A null file means
 * in-memory only (tests). No JDK collections.
 */
public final class Vault {
  public static final int DEFAULT_CACHE_CAPACITY = 1_024;

  private final Path file;
  private final LruCache<Account> cache;
  private Account[] table = new Account[64];
  private int mask = 63;
  private Account[] byOpening = new Account[64];
  private int count;
  private boolean dirty;

  /** Opens the vault, loading every account from {@code file} if it exists. */
  public Vault(Path file, int cacheCapacity) throws IOException {
    this.file = file;
    this.cache = new LruCache<>(cacheCapacity, this::ledgerGet);
    if (file != null && Files.exists(file)) {
      load();
    }
  }

  /** An in-memory vault with no file (tests). */
  public static Vault inMemory() {
    try {
      return new Vault(null, DEFAULT_CACHE_CAPACITY);
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public Path file() {
    return file;
  }

  /** The trader's account, or null if none was opened. Goes through the LRU cache. */
  public Account account(long traderId) {
    return cache.get(traderId);
  }

  /** Opens a new account. Rejects a duplicate id or negative amounts. */
  public Account open(long traderId, long cash, long marginLimit) {
    if (cash < 0 || marginLimit < 0) {
      throw new IllegalArgumentException("cash and marginLimit must be >= 0");
    }
    if (ledgerGet(traderId) != null) {
      throw new IllegalArgumentException("account " + traderId + " already exists");
    }
    Account a = new Account(traderId, cash, marginLimit);
    ledgerPut(a);
    dirty = true;
    return a;
  }

  /** Settles one trade: {@code buyer.cash -= notional}, {@code seller.cash += notional}. */
  public void settle(Account buyer, Account seller, long notional) {
    buyer.cash = Math.subtractExact(buyer.cash, notional);
    seller.cash = Math.addExact(seller.cash, notional);
    dirty = true;
  }

  /** Number of accounts. */
  public int size() {
    return count;
  }

  /** The i-th account in opening order (0-based). */
  public Account accountAt(int i) {
    return byOpening[i];
  }

  /** True if there are changes not yet written by {@link #flush()}. */
  public boolean dirty() {
    return dirty;
  }

  // ---------------------------------------------------------------- ledger

  private static int mix(long id, int mask) {
    long h = id * 0x9E3779B97F4A7C15L;
    h ^= h >>> 32;
    h *= 0xBF58476D1CE4E5B9L;
    h ^= h >>> 29;
    return (int) h & mask;
  }

  private Account ledgerGet(long traderId) {
    for (Account a = table[mix(traderId, mask)]; a != null; a = a.ledgerNext) {
      if (a.traderId == traderId) return a;
    }
    return null;
  }

  private void ledgerPut(Account a) {
    if (count == byOpening.length) {
      Account[] grown = new Account[byOpening.length * 2];
      System.arraycopy(byOpening, 0, grown, 0, count);
      byOpening = grown;
    }
    byOpening[count++] = a;
    int b = mix(a.traderId, mask);
    a.ledgerNext = table[b];
    table[b] = a;
    if (count > table.length * 3 / 4) {
      Account[] nt = new Account[table.length * 2];
      int nm = nt.length - 1;
      for (int i = 0; i < count; i++) {
        Account x = byOpening[i];
        int nb = mix(x.traderId, nm);
        x.ledgerNext = nt[nb];
        nt[nb] = x;
      }
      table = nt;
      mask = nm;
    }
  }

  // ---------------------------------------------------------------- persistence

  /** Writes every account to the file atomically (temp file, fsync, rename). */
  public void flush() throws IOException {
    if (file == null) return;
    Path dir = file.toAbsolutePath().getParent();
    Files.createDirectories(dir);
    Path tmp = dir.resolve(file.getFileName() + ".tmp");
    StringBuilder sb = new StringBuilder(count * 64);
    for (int i = 0; i < count; i++) {
      Account a = byOpening[i];
      sb.append("{\"traderId\":").append(a.traderId)
          .append(",\"cash\":").append(a.cash)
          .append(",\"marginLimit\":").append(a.marginLimit).append("}\n");
    }
    try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
        StandardOpenOption.SYNC)) {
      out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }
    try {
      Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
    dirty = false;
  }

  private void load() throws IOException {
    try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      int lineNo = 0;
      while ((line = in.readLine()) != null) {
        lineNo++;
        if (line.isBlank()) continue;
        long[] f = parseLine(line, lineNo);
        if (ledgerGet(f[0]) != null) {
          throw new IOException(file + ":" + lineNo + ": duplicate traderId " + f[0]);
        }
        // cash may legitimately be negative (Appendix A reserves nothing for resting
        // orders, and checks a market order only at the best tick); a margin limit may not
        if (f[2] < 0) {
          throw new IOException(file + ":" + lineNo + ": negative marginLimit");
        }
        ledgerPut(new Account(f[0], f[1], f[2]));
      }
    }
    dirty = false;
  }

  private static final String[] FIELDS = { "traderId", "cash", "marginLimit" };

  /**
   * Strict reader for exactly the lines {@link #flush()} writes: one flat object with the
   * three integer fields, in any order. Anything else fails loudly with the line number:
   * an account file is never half-read.
   */
  private long[] parseLine(String line, int lineNo) throws IOException {
    String s = line.trim();
    if (!s.startsWith("{") || !s.endsWith("}")) {
      throw bad(lineNo, "not a JSON object");
    }
    String body = s.substring(1, s.length() - 1);
    long[] values = new long[3];
    boolean[] seen = new boolean[3];
    for (String part : body.split(",", -1)) {
      int colon = part.indexOf(':');
      if (colon < 0) throw bad(lineNo, "expected \"key\":value");
      String key = part.substring(0, colon).trim();
      String value = part.substring(colon + 1).trim();
      if (key.length() < 2 || key.charAt(0) != '"' || key.charAt(key.length() - 1) != '"') {
        throw bad(lineNo, "unquoted key " + key);
      }
      key = key.substring(1, key.length() - 1);
      int k = -1;
      for (int i = 0; i < FIELDS.length; i++) {
        if (FIELDS[i].equals(key)) k = i;
      }
      if (k < 0) throw bad(lineNo, "unknown field " + key);
      if (seen[k]) throw bad(lineNo, "duplicate field " + key);
      try {
        values[k] = Long.parseLong(value);
      } catch (NumberFormatException e) {
        throw bad(lineNo, "field " + key + " is not an integer: " + value);
      }
      seen[k] = true;
    }
    for (int i = 0; i < FIELDS.length; i++) {
      if (!seen[i]) throw bad(lineNo, "missing field " + FIELDS[i]);
    }
    return values;
  }

  private IOException bad(int lineNo, String why) {
    return new IOException(file + ":" + lineNo + ": " + why);
  }
}
