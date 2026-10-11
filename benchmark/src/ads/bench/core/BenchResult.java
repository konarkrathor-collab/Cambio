package ads.bench.core;

/** One row of benchmark evidence: timing distribution + allocation + validity. */
public final class BenchResult {
  public final String bench;
  public final String structure;
  public final int n;
  public final long seed;
  public final int reps;
  public final boolean valid;
  public final double minMs;
  public final double medianMs;
  public final double maxMs;
  public final double opsPerSec;
  public final double spreadPct;   // (max - median) / median * 100
  public final double allocBytesPerOp; // -1 if unavailable
  public final String note;

  public BenchResult(String bench, String structure, int n, long seed, int reps, boolean valid,
                     double minMs, double medianMs, double maxMs, double allocBytesPerOp, String note) {
    this.bench = bench;
    this.structure = structure;
    this.n = n;
    this.seed = seed;
    this.reps = reps;
    this.valid = valid;
    this.minMs = minMs;
    this.medianMs = medianMs;
    this.maxMs = maxMs;
    this.opsPerSec = medianMs > 0 ? (n * 1000.0 / medianMs) : Double.NaN;
    this.spreadPct = medianMs > 0 ? (maxMs - medianMs) / medianMs * 100.0 : Double.NaN;
    this.allocBytesPerOp = allocBytesPerOp;
    this.note = note == null ? "" : note;
  }

  public static String header() {
    return String.format("%-16s %10s %12s %12s %9s %10s %6s  %s",
        "structure", "median_ms", "ops/sec", "alloc_B/op", "min_ms", "spread%", "valid", "note");
  }

  public String row() {
    return String.format("%-16s %10.2f %12.0f %12s %9.2f %10.2f %6s  %s",
        structure, medianMs, opsPerSec,
        allocBytesPerOp < 0 ? "n/a" : String.format("%.1f", allocBytesPerOp),
        minMs, spreadPct, valid ? "OK" : "FAIL", note);
  }

  public static String csvHeader() {
    return "bench,structure,n,seed,reps,valid,min_ms,median_ms,max_ms,ops_per_sec,spread_pct,alloc_bytes_per_op,note";
  }

  public String csv() {
    return String.format("%s,%s,%d,%d,%d,%s,%.3f,%.3f,%.3f,%.0f,%.2f,%.1f,%s",
        bench, structure, n, seed, reps, valid ? "ok" : "FAIL",
        minMs, medianMs, maxMs, opsPerSec, spreadPct, allocBytesPerOp, note);
  }
}
