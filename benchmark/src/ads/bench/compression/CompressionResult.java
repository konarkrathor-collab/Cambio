package ads.bench.compression;

/**
 * Purpose-built result row for the compression bench — the documented "parallel
 * lightweight result type" choice from this module's brief. Ratio plus SEPARATE
 * encode/decode timings and allocation-per-input-byte do not map onto BenchResult's
 * op-count columns without misleading repurposing, so the console and RESULTS.md use this
 * type. The raw CSV is still appended through BenchResult adapters (two rows per
 * candidate, phase=encode / phase=decode) so the log keeps its single schema — see
 * Main.runCompression.
 */
public final class CompressionResult {
  public final String structure;
  public final long inputBytes;
  public final long encodedBytes;
  public final double ratio; // encodedBytes / inputBytes
  public final double encodeMedianMs;
  public final double decodeMedianMs;
  public final double encodeMinMs;
  public final double encodeMaxMs;
  public final double decodeMinMs;
  public final double decodeMaxMs;
  public final double encodeMBs;
  public final double decodeMBs;
  public final double spreadPct; // max of the two phases' (max-median)/median spreads
  public final double allocPerInputByte; // encoder allocation churn; -1 if unavailable
  public final boolean valid;
  public final String note;

  public CompressionResult(String structure, long inputBytes, long encodedBytes,
      double encodeMedianMs, double decodeMedianMs, double encodeMinMs, double encodeMaxMs,
      double decodeMinMs, double decodeMaxMs, double spreadPct, double allocPerInputByte,
      boolean valid, String note) {
    this.structure = structure;
    this.inputBytes = inputBytes;
    this.encodedBytes = encodedBytes;
    this.ratio = inputBytes > 0 ? (double) encodedBytes / inputBytes : Double.NaN;
    this.encodeMedianMs = encodeMedianMs;
    this.decodeMedianMs = decodeMedianMs;
    this.encodeMinMs = encodeMinMs;
    this.encodeMaxMs = encodeMaxMs;
    this.decodeMinMs = decodeMinMs;
    this.decodeMaxMs = decodeMaxMs;
    this.encodeMBs = encodeMedianMs > 0 ? inputBytes / (encodeMedianMs * 1000.0) : Double.NaN;
    this.decodeMBs = decodeMedianMs > 0 ? inputBytes / (decodeMedianMs * 1000.0) : Double.NaN;
    this.spreadPct = spreadPct;
    this.allocPerInputByte = allocPerInputByte;
    this.valid = valid;
    this.note = note == null ? "" : note;
  }

  public static CompressionResult failed(String structure, long inputBytes, String why) {
    return new CompressionResult(structure, inputBytes, 0, 0, 0, 0, 0, 0, 0,
        Double.NaN, -1, false, why);
  }

  public static String header() {
    return String.format("%-16s %8s %9s %9s %9s %9s %8s %10s %5s  %s",
        "structure", "ratio", "enc_ms", "dec_ms", "enc_MB/s", "dec_MB/s", "spread%", "alloc/in",
        "valid", "note");
  }

  public String row() {
    if (!valid) {
      return String.format("%-16s %8s %9s %9s %9s %9s %8s %10s %5s  %s",
          structure, "-", "-", "-", "-", "-", "-", "-", "FAIL", note);
    }
    return String.format("%-16s %8.4f %9.2f %9.2f %9.1f %9.1f %8.2f %10.2f %5s  %s",
        structure, ratio, encodeMedianMs, decodeMedianMs, encodeMBs, decodeMBs, spreadPct,
        allocPerInputByte, valid ? "OK" : "FAIL", note);
  }
}
