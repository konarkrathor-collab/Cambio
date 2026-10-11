package ads.bench.compression;

import ads.bench.core.Rng;

import java.nio.charset.StandardCharsets;

/**
 * DATA (Phase 1): deterministic synthetic trade-log batches — the payload shape doc 05 §6
 * actually compresses. Deliberately NOT random bytes (which would be unfairly hostile to
 * every compressor): these are structured, field-delimited log lines whose fields mix
 * heavy repetition (ticker pairs, trader ids, typical lot sizes, the incrementing ORD-
 * prefix) with genuinely varying content (timestamps, random-walk prices) — the same
 * profile real exchange telemetry has.
 *
 * <p>Line format (ASCII, so byte operations are unambiguous):
 * {@code HH:MM:SS.mmm|AAA/BBB|BUY|150000|1.08523|T101|ORD-0048213|MATCHED}
 *
 * <p>Three batch sizes by default (256 KB, 1 MB, 4 MB) so ratio/throughput can be checked
 * for size sensitivity; the load is generated once per CLI run and replayed identically by
 * every candidate and every rep.
 */
public final class LogWorkload {
  private static final int[] DEFAULT_BATCH_SIZES = {256 * 1024, 1024 * 1024, 4 * 1024 * 1024};
  private static final String[] CURRENCIES =
      {"USD", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "INR", "CNY", "HKD", "SGD", "NZD"};
  private static final int[] LOT_SIZES = {
      1000, 2000, 5000, 10000, 15000, 25000, 50000, 75000,
      100000, 150000, 250000, 500000, 750000, 1000000};
  private static final String[] STATUS = {"MATCHED", "MATCHED", "MATCHED", "MATCHED", "PARTIAL",
      "CANCELLED"};

  public final long seed;
  public final byte[][] batches;
  public final long totalInputBytes;
  public final long totalLines;

  private LogWorkload(long seed, byte[][] batches, long totalInputBytes, long totalLines) {
    this.seed = seed;
    this.batches = batches;
    this.totalInputBytes = totalInputBytes;
    this.totalLines = totalLines;
  }

  public static LogWorkload generate(long seed) {
    return generate(seed, DEFAULT_BATCH_SIZES);
  }

  public static LogWorkload generate(long seed, int[] batchSizes) {
    Rng rng = new Rng(seed);
    byte[][] batches = new byte[batchSizes.length][];
    long total = 0;
    long lines = 0;
    for (int b = 0; b < batchSizes.length; b++) {
      StringBuilder sb = new StringBuilder(batchSizes[b] + 128);
      long baseMs = 9 * 3600_000L; // session starts 09:00:00.000
      double price = 1.08500;
      long orderSeq = 1;
      while (sb.length() < batchSizes[b]) {
        baseMs += 1 + rng.nextInt(40);
        price *= 1.0 + (rng.nextDouble() - 0.5) * 0.0004;
        int ci = rng.nextInt(12);
        int cj = rng.nextInt(11);
        if (cj >= ci) cj++;
        String pair = CURRENCIES[ci] + "/" + CURRENCIES[cj];
        sb.append(pad2((int) (baseMs / 3600_000L % 24))).append(':')
            .append(pad2((int) (baseMs / 60_000L % 60))).append(':')
            .append(pad2((int) (baseMs / 1000L % 60))).append('.')
            .append(pad3((int) (baseMs % 1000L)))
            .append('|').append(pair)
            .append('|').append(rng.nextInt(2) == 0 ? "BUY" : "SELL")
            .append('|').append(LOT_SIZES[rng.nextInt(LOT_SIZES.length)])
            .append('|').append(String.format("%.5f", price))
            .append('|').append('T').append(100 + rng.nextInt(400))
            .append('|').append("ORD-").append(pad7(orderSeq++))
            .append('|').append(STATUS[rng.nextInt(STATUS.length)])
            .append('\n');
        lines++;
      }
      batches[b] = sb.toString().getBytes(StandardCharsets.US_ASCII);
      total += batches[b].length;
    }
    return new LogWorkload(seed, batches, total, lines);
  }

  private static String pad2(int v) {
    return v < 10 ? "0" + v : Integer.toString(v);
  }

  private static String pad3(int v) {
    if (v < 10) return "00" + v;
    if (v < 100) return "0" + v;
    return Integer.toString(v);
  }

  private static String pad7(long v) {
    String s = Long.toString(v);
    StringBuilder b = new StringBuilder();
    for (int i = s.length(); i < 7; i++) b.append('0');
    return b.append(s).toString();
  }

  /** Human-readable summary of the load (for logs). */
  public String describe() {
    StringBuilder sizes = new StringBuilder();
    for (int i = 0; i < batches.length; i++) {
      if (i > 0) sizes.append('+');
      sizes.append(batches[i].length / 1024).append("KB");
    }
    return String.format("seed=%d | batches=%s | total=%d bytes lines=%d",
        seed, sizes, totalInputBytes, totalLines);
  }
}
