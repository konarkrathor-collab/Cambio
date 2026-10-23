package ads.logbook;

import ads.directory.Directory;
import ads.engine.Trade;
import ads.engine.TradeSink;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * The trade logbook (doc 05 §6). Completed trades are buffered as text records, one line
 * per trade:
 *
 * <pre>tradeId,seq,pair,priceTick,quantity,buyOrderId,sellOrderId,buyerId,sellerId,takerSide</pre>
 *
 * <p>and rotated into the archive: each rotation Huffman-encodes the buffered batch and
 * writes it as the next segment file {@code trades-000001.huf}, {@code trades-000002.huf},
 * ... (temp file, fsync, rename, so a segment is never torn). {@link #readArchive}
 * decodes the segments in order back to the exact bytes that were buffered.
 *
 * <p>{@link #onTrade} only appends to memory: it runs inside the matching loop, which must
 * not do file I/O or fail half-way through an order. The caller rotates between commands
 * with {@link #rotateIfDue()} and at shutdown with {@link #rotate()}. No JDK collections.
 */
public final class Logbook implements TradeSink {
  private static final String PREFIX = "trades-";
  private static final String SUFFIX = ".huf";

  private final Path dir;
  private final Directory directory;
  private final int rotateEvery;
  private final HuffmanCodec codec = new HuffmanCodec();
  private final StringBuilder buffer = new StringBuilder(1 << 16);
  private int buffered;
  private int segments;
  private long archivedTrades;

  /**
   * @param dir         archive directory (created if missing); existing segments are kept
   *                    and numbering continues after them
   * @param rotateEvery rotate once this many trades are buffered
   */
  public Logbook(Path dir, Directory directory, int rotateEvery) throws IOException {
    if (rotateEvery < 1) {
      throw new IllegalArgumentException("rotateEvery must be >= 1: " + rotateEvery);
    }
    this.dir = dir;
    this.directory = directory;
    this.rotateEvery = rotateEvery;
    Files.createDirectories(dir);
    this.segments = countSegments(dir);
  }

  @Override
  public void onTrade(Trade t) {
    appendRecord(buffer, t, directory);
    buffered++;
  }

  /** Appends one trade's archive record (including its newline) to {@code sb}. */
  public static void appendRecord(StringBuilder sb, Trade t, Directory directory) {
    sb.append(t.tradeId()).append(',')
        .append(t.seq()).append(',')
        .append(directory.pair(t.pairId())).append(',')
        .append(t.priceTick()).append(',')
        .append(t.quantity()).append(',')
        .append(t.buyOrderId()).append(',')
        .append(t.sellOrderId()).append(',')
        .append(t.buyerId()).append(',')
        .append(t.sellerId()).append(',')
        .append(t.takerSide().name()).append('\n');
  }

  /** Rotates if at least {@code rotateEvery} trades are buffered; returns whether it did. */
  public boolean rotateIfDue() throws IOException {
    if (buffered < rotateEvery) return false;
    rotate();
    return true;
  }

  /** Writes every buffered trade as one new archive segment (no-op if nothing is buffered). */
  public void rotate() throws IOException {
    if (buffered == 0) return;
    byte[] records = buffer.toString().getBytes(StandardCharsets.US_ASCII);
    byte[] encoded = codec.encode(records);
    Path target = dir.resolve(segmentName(segments + 1));
    Path tmp = dir.resolve(segmentName(segments + 1) + ".tmp");
    try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
        StandardOpenOption.SYNC)) {
      out.write(encoded);
    }
    try {
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, target);
    }
    segments++;
    archivedTrades += buffered;
    buffer.setLength(0);
    buffered = 0;
  }

  public int bufferedTrades() {
    return buffered;
  }

  /** Trades written to the archive by this instance. */
  public long archivedTrades() {
    return archivedTrades;
  }

  /** Segment files in the archive (including ones found at startup). */
  public int segments() {
    return segments;
  }

  public Path dir() {
    return dir;
  }

  /**
   * Decodes every segment in {@code dir}, in order, and returns the concatenated records.
   * Fails if a segment is missing from the sequence or does not decode (backend/BUGS.md B2).
   */
  public static byte[] readArchive(Path dir) throws IOException {
    int n = countSegments(dir);
    HuffmanCodec codec = new HuffmanCodec();
    byte[][] parts = new byte[n][];
    long total = 0;
    for (int i = 1; i <= n; i++) {
      Path p = dir.resolve(segmentName(i));
      if (!Files.exists(p)) {
        throw new IOException("archive gap: " + p + " is missing (" + n + " segments found)");
      }
      try {
        parts[i - 1] = codec.decode(Files.readAllBytes(p));
      } catch (IllegalArgumentException e) {
        throw new IOException("corrupt archive segment " + p + ": " + e.getMessage(), e);
      }
      total += parts[i - 1].length;
    }
    if (total > Integer.MAX_VALUE - 8) {
      throw new IOException("archive too large to read in one array: " + total + " bytes");
    }
    byte[] all = new byte[(int) total];
    int off = 0;
    for (byte[] part : parts) {
      System.arraycopy(part, 0, all, off, part.length);
      off += part.length;
    }
    return all;
  }

  private static String segmentName(int i) {
    String digits = Integer.toString(i);
    StringBuilder sb = new StringBuilder(PREFIX);
    for (int pad = digits.length(); pad < 6; pad++) sb.append('0');
    return sb.append(digits).append(SUFFIX).toString();
  }

  private static int countSegments(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) return 0;
    int n = 0;
    try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, PREFIX + "*" + SUFFIX)) {
      for (Path ignored : files) n++;
    }
    return n;
  }
}
