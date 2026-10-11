package ads.bench.ladder;

import ads.bench.core.AllocCounter;
import ads.bench.core.BenchResult;
import ads.bench.core.Stats;

import java.util.Map;
import java.util.TreeMap;

/**
 * Phase 2+3 harness: for each candidate -
 * <ol>
 *   <li>replay against the oracle on the frozen workload; compare the accumulated query
 *       checksum AND the final (tick -> volume) state (correctness gate);</li>
 *   <li>warmup replays, discarded (JIT steady state);</li>
 *   <li>timed full-replay reps -> median + min/max spread;</li>
 *   <li>thread-allocation measurement (bytes allocated per operation).</li>
 * </ol>
 *
 * <p>A structure that fails the gate is reported FAIL and never timed.
 */
public final class LadderBench {
  private static final long FNV_OFFSET = 0xcbf29ce484222325L;
  private static final long FNV_PRIME = 0x100000001b3L;

  /** Prevents dead-code elimination of final structure state. */
  private static volatile long blackhole;

  public interface Factory {
    LadderOps create();
  }

  private LadderBench() {
  }

  public static long replay(LadderOps s, LadderWorkload w) {
    long checksum = FNV_OFFSET;
    byte[] op = w.op;
    long[] tick = w.tick;
    long[] vol = w.vol;
    int n = w.n;
    for (int i = 0; i < n; i++) {
      switch (op[i]) {
        case LadderWorkload.PUT:
          s.put(tick[i], vol[i]);
          break;
        case LadderWorkload.REMOVE:
          s.remove(tick[i]);
          break;
        case LadderWorkload.BEST_ASK:
          checksum = mix(checksum, s.bestAsk());
          break;
        case LadderWorkload.BEST_BID:
          checksum = mix(checksum, s.bestBid());
          break;
        case LadderWorkload.WALK_UP:
          checksum = mix(checksum, s.walkUp(tick[i], (int) vol[i]));
          break;
        case LadderWorkload.WALK_DOWN:
          checksum = mix(checksum, s.walkDown(tick[i], (int) vol[i]));
          break;
        default:
          throw new IllegalStateException("bad op " + op[i]);
      }
    }
    blackhole ^= s.size();
    return checksum;
  }

  private static long mix(long c, long v) {
    return (c ^ v) * FNV_PRIME;
  }

  public static TreeMap<Long, Long> snapshot(LadderOps s, int cap) {
    long[] t = new long[cap];
    long[] v = new long[cap];
    int n = s.dump(t, v);
    TreeMap<Long, Long> m = new TreeMap<>();
    for (int i = 0; i < n; i++) {
      m.put(t[i], v[i]);
    }
    return m;
  }

  /** Correctness gate. Returns null if OK, else a human-readable problem description. */
  public static String verify(LadderOps candidate, LadderWorkload w, long oracleChecksum,
      TreeMap<Long, Long> oracleState) {
    long c = replay(candidate, w);
    if (c != oracleChecksum) {
      return "checksum mismatch";
    }
    if (candidate.size() != oracleState.size()) {
      return "size mismatch: " + candidate.size() + " vs " + oracleState.size();
    }
    TreeMap<Long, Long> mine = snapshot(candidate, w.n);
    if (!mine.equals(oracleState)) {
      for (Map.Entry<Long, Long> e : oracleState.entrySet()) {
        Long got = mine.get(e.getKey());
        if (got == null || !got.equals(e.getValue())) {
          return "state mismatch at tick " + e.getKey() + ": got " + got + " want " + e.getValue();
        }
      }
      return "state mismatch (extra entries)";
    }
    return null;
  }

  public static BenchResult timed(String bench, String name, Factory factory, LadderWorkload w,
      int warmup, int reps, AllocCounter alloc, String note) {
    for (int i = 0; i < warmup; i++) {
      replay(factory.create(), w); // JIT warmup, discarded
    }
    long[] times = new long[reps];
    for (int i = 0; i < reps; i++) {
      System.gc();
      LadderOps s = factory.create();
      long t0 = System.nanoTime();
      replay(s, w);
      times[i] = System.nanoTime() - t0;
    }
    long allocDelta = -1;
    if (alloc.isEnabled()) {
      System.gc();
      long a0 = alloc.bytes();
      LadderOps s = factory.create(); // construction included: flat-array's range cost must show
      replay(s, w);
      allocDelta = alloc.bytes() - a0;
    }
    double median = Stats.median(times);
    return new BenchResult(bench, name, w.n, w.seed, reps, true,
        Stats.min(times) / 1e6, median / 1e6, Stats.max(times) / 1e6,
        allocDelta < 0 ? -1.0 : (double) allocDelta / w.n, note);
  }

  public static BenchResult failed(String bench, String name, LadderWorkload w, int reps,
      String why) {
    return new BenchResult(bench, name, w.n, w.seed, reps, false, 0, 0, 0, -1, why);
  }
}
