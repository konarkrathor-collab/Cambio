package ads.bench.queue;

import ads.bench.core.AllocCounter;
import ads.bench.core.BenchResult;
import ads.bench.core.Stats;

import java.util.Map;
import java.util.TreeMap;

/**
 * Phase 2+3 harness for the queue bench, mirroring {@code LadderBench}: (1) replay against
 * the oracle and compare an accumulated query checksum — every dequeue/peek return value
 * and every cancel result is mixed in, which is how FIFO ORDER itself is validated — plus
 * the final (id -> volume) state and size; (2) warmup replays, discarded (JIT steady
 * state); (3) timed full replays -> median + min/max spread; (4) thread-allocation
 * measurement. A candidate failing the gate is reported FAIL and never timed.
 */
public final class QueueBench {
  private static final long FNV_OFFSET = 0xcbf29ce484222325L;
  private static final long FNV_PRIME = 0x100000001b3L;

  /** Prevents dead-code elimination of final structure state. */
  private static volatile long blackhole;

  public interface Factory {
    QueueOps create();
  }

  private QueueBench() {
  }

  public static long replay(QueueOps s, QueueWorkload w) {
    long checksum = FNV_OFFSET;
    byte[] op = w.op;
    long[] orderId = w.orderId;
    long[] volume = w.volume;
    for (int i = 0; i < w.n; i++) {
      switch (op[i]) {
        case QueueWorkload.ENQUEUE:
          s.enqueue(orderId[i], volume[i]);
          break;
        case QueueWorkload.DEQUEUE:
          checksum = mix(checksum, s.dequeueHead());
          break;
        case QueueWorkload.CANCEL:
          checksum = mix(checksum, s.cancel(orderId[i]) ? 1L : 0L);
          break;
        default:
          checksum = mix(checksum, s.peekHead());
          break;
      }
    }
    blackhole ^= s.size();
    return checksum;
  }

  private static long mix(long c, long v) {
    return (c ^ v) * FNV_PRIME;
  }

  public static TreeMap<Long, Long> snapshot(QueueOps s, int cap) {
    long[] ids = new long[cap];
    long[] vols = new long[cap];
    int n = s.dump(ids, vols);
    TreeMap<Long, Long> m = new TreeMap<>();
    for (int i = 0; i < n; i++) {
      m.put(ids[i], vols[i]);
    }
    return m;
  }

  /** Correctness gate. Returns null if OK, else a human-readable problem description. */
  public static String verify(QueueOps candidate, QueueWorkload w, long oracleChecksum,
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
          return "state mismatch at orderId " + e.getKey() + ": got " + got + " want "
              + e.getValue();
        }
      }
      return "state mismatch (extra entries)";
    }
    return null;
  }

  public static BenchResult timed(String bench, String name, Factory factory, QueueWorkload w,
      int warmup, int reps, AllocCounter alloc, String note) {
    for (int i = 0; i < warmup; i++) {
      replay(factory.create(), w); // JIT warmup, discarded
    }
    long[] times = new long[reps];
    for (int i = 0; i < reps; i++) {
      System.gc();
      QueueOps s = factory.create();
      long t0 = System.nanoTime();
      replay(s, w);
      times[i] = System.nanoTime() - t0;
    }
    long allocDelta = -1;
    if (alloc.isEnabled()) {
      System.gc();
      long a0 = alloc.bytes();
      QueueOps s = factory.create(); // construction included (table pre-allocation must show)
      replay(s, w);
      allocDelta = alloc.bytes() - a0;
    }
    double median = Stats.median(times);
    return new BenchResult(bench, name, w.n, w.seed, reps, true,
        Stats.min(times) / 1e6, median / 1e6, Stats.max(times) / 1e6,
        allocDelta < 0 ? -1.0 : (double) allocDelta / w.n, note);
  }

  public static BenchResult failed(String bench, String name, QueueWorkload w, int reps,
      String why) {
    return new BenchResult(bench, name, w.n, w.seed, reps, false, 0, 0, 0, -1, why);
  }
}
