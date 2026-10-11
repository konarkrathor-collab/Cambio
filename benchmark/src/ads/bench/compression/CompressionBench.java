package ads.bench.compression;

import ads.bench.core.AllocCounter;
import ads.bench.core.Stats;

/**
 * Phase 2+3 harness for the compression bench. Correctness = the round-trip identity
 * {@code decode(encode(x)) == x}, byte-for-byte, for every batch — the gate this module's
 * brief prescribes (stronger and simpler than oracle agreement, and exactly the property
 * audit logs need). Timing is two-phase: warmup rounds, then timed encode reps, then timed
 * decode reps over the SAME encoded outputs, then an allocation pass for the encoder.
 */
public final class CompressionBench {
  /** Prevents dead-code elimination of decode output / encoded sizes. */
  private static volatile long blackhole;

  public interface Factory {
    CompressorOps create();
  }

  private CompressionBench() {
  }

  /** One full round trip over all batches. Returns null if OK, else the first problem. */
  public static String verify(CompressorOps c, LogWorkload w) {
    for (int b = 0; b < w.batches.length; b++) {
      byte[] in = w.batches[b];
      byte[] enc = c.encode(in);
      byte[] dec = c.decode(enc);
      if (dec.length != in.length) {
        return "round-trip length mismatch on batch " + b + ": " + dec.length + " vs "
            + in.length;
      }
      for (int i = 0; i < in.length; i++) {
        if (dec[i] != in[i]) {
          return "round-trip byte mismatch on batch " + b + " at offset " + i;
        }
      }
      blackhole ^= enc.length;
    }
    return null;
  }

  private static long encodeAll(CompressorOps c, LogWorkload w, byte[][] out) {
    long total = 0;
    for (int b = 0; b < w.batches.length; b++) {
      out[b] = c.encode(w.batches[b]);
      total += out[b].length;
    }
    return total;
  }

  private static void decodeAll(CompressorOps c, byte[][] encoded) {
    long sink = 0;
    for (byte[] enc : encoded) {
      byte[] dec = c.decode(enc);
      sink += dec.length + (dec.length > 0 ? dec[dec.length - 1] : 0);
    }
    blackhole ^= sink;
  }

  public static CompressionResult timed(String name, Factory f, LogWorkload w, int warmup,
      int reps, AllocCounter alloc) {
    CompressorOps c = f.create();
    for (int i = 0; i < warmup; i++) {
      byte[][] enc = new byte[w.batches.length][];
      encodeAll(c, w, enc);
      decodeAll(c, enc); // JIT warmup for both phases, discarded
    }
    byte[][] encoded = new byte[w.batches.length][];
    long encodedTotal = 0;
    long[] encTimes = new long[reps];
    for (int i = 0; i < reps; i++) {
      System.gc();
      long t0 = System.nanoTime();
      encodedTotal = encodeAll(c, w, encoded);
      encTimes[i] = System.nanoTime() - t0;
    }
    long[] decTimes = new long[reps];
    for (int i = 0; i < reps; i++) {
      System.gc();
      long t0 = System.nanoTime();
      decodeAll(c, encoded); // same encoded outputs every rep
      decTimes[i] = System.nanoTime() - t0;
    }
    long allocDelta = -1;
    if (alloc.isEnabled()) {
      System.gc();
      byte[][] scratch = new byte[w.batches.length][];
      long a0 = alloc.bytes();
      encodeAll(c, w, scratch);
      allocDelta = alloc.bytes() - a0;
    }
    double encMed = Stats.median(encTimes) / 1e6;
    double decMed = Stats.median(decTimes) / 1e6;
    double spread = Math.max(spread(encTimes), spread(decTimes));
    return new CompressionResult(name, w.totalInputBytes, encodedTotal, encMed, decMed,
        Stats.min(encTimes) / 1e6, Stats.max(encTimes) / 1e6,
        Stats.min(decTimes) / 1e6, Stats.max(decTimes) / 1e6,
        spread, allocDelta < 0 ? -1.0 : (double) allocDelta / Math.max(1, w.totalInputBytes),
        true, "");
  }

  private static double spread(long[] times) {
    double med = Stats.median(times);
    return med > 0 ? (Stats.max(times) - med) / med * 100.0 : Double.NaN;
  }
}
