package ads.bench.ticker;

import ads.bench.core.BenchResult;
import ads.bench.core.Interleaved;

/**
 * Phase 2+3 harness for the ticker bench, mirroring the other modules' discipline:
 * (1) replay against the oracle and compare an accumulated checksum — contains results are
 * mixed directly, and prefix results are mixed as (count, order-INDEPENDENT XOR fold of the
 * returned strings), since candidates may legitimately return matches in different orders;
 * plus a final universe sweep (every roster ticker must be resident) and a size check;
 * (2) warmup reps discarded; (3) timed reps -> median + spread (both
 * interleaved round-robin across candidates — {@link Interleaved}); (4) allocation per op.
 *
 * <p>A candidate that structurally cannot implement part of the contract throws
 * UnsupportedOperationException — the caller (Main) reports that as an explicit N/A row,
 * never a silent exclusion.
 */
public final class TickerBench {
  private static final long FNV_OFFSET = 0xcbf29ce484222325L;
  private static final long FNV_PRIME = 0x100000001b3L;

  /** Prevents dead-code elimination of final structure state. */
  private static volatile long blackhole;

  public interface Factory {
    TickerOps create();
  }

  private TickerBench() {
  }

  public static long replay(TickerOps s, TickerWorkload w) {
    long checksum = FNV_OFFSET;
    byte[] op = w.op;
    String[] arg = w.arg;
    String[] buf = new String[w.universe.length + 8]; // caller-provided result buffer
    for (int i = 0; i < w.n; i++) {
      switch (op[i]) {
        case TickerWorkload.INSERT:
          s.insert(arg[i]);
          break;
        case TickerWorkload.CONTAINS:
          checksum = mix(checksum, s.contains(arg[i]) ? 1L : 0L);
          break;
        default: {
          int k = s.prefixSearch(arg[i], buf);
          checksum = mix(checksum, k);
          long setFold = 0;
          for (int j = 0; j < k; j++) {
            setFold ^= stringHash(buf[j]);
          }
          checksum = mix(checksum, setFold);
          break;
        }
      }
    }
    blackhole ^= s.size();
    return checksum;
  }

  private static long mix(long c, long v) {
    return (c ^ v) * FNV_PRIME;
  }

  private static long stringHash(String s) {
    long h = FNV_OFFSET;
    for (int i = 0; i < s.length(); i++) {
      h = (h ^ s.charAt(i)) * FNV_PRIME;
    }
    return h;
  }

  /** Correctness gate. Returns null if OK, else a human-readable problem description. */
  public static String verify(TickerOps candidate, TickerWorkload w, long oracleChecksum) {
    long c = replay(candidate, w);
    if (c != oracleChecksum) {
      return "checksum mismatch (a query answered differently from the oracle)";
    }
    if (candidate.size() != w.universe.length) {
      return "size mismatch: " + candidate.size() + " vs " + w.universe.length;
    }
    for (String t : w.universe) {
      if (!candidate.contains(t)) {
        return "universe member missing after replay: " + t;
      }
    }
    return null;
  }

  /** Timed unit for {@link Interleaved}: fresh structure (untimed), then one full replay. */
  public static Interleaved.Trial trial(Factory factory, TickerWorkload w) {
    return new Interleaved.Trial() {
      @Override
      public Object setup() {
        return factory.create();
      }

      @Override
      public void run(Object s) {
        replay((TickerOps) s, w);
      }
    };
  }

  public static BenchResult failed(String bench, String name, TickerWorkload w, int reps,
      String why) {
    return new BenchResult(bench, name, w.n, w.seed, reps, false, 0, 0, 0, -1, why);
  }
}
