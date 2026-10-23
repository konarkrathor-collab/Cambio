package ads.bench.queue;

import ads.bench.core.BenchResult;
import ads.bench.core.Interleaved;

import java.util.Map;
import java.util.TreeMap;

/**
 * Phase 2+3 harness for the queue bench, mirroring {@code LadderBench}: (1) replay against
 * the oracle and compare an accumulated query checksum — every dequeue/peek return value
 * and every cancel result is mixed in, which is how FIFO ORDER itself is validated — plus
 * the final (id -> volume) state and size; (2) warmup replays, discarded (JIT steady
 * state); (3) timed full replays, interleaved round-robin with the other candidates
 * ({@link Interleaved}) -> median + min/max spread; (4) thread-allocation
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

  /** Timed unit for {@link Interleaved}: fresh structure (untimed), then one full replay. */
  public static Interleaved.Trial trial(Factory factory, QueueWorkload w) {
    return new Interleaved.Trial() {
      @Override
      public Object setup() {
        return factory.create();
      }

      @Override
      public void run(Object s) {
        replay((QueueOps) s, w);
      }
    };
  }

  public static BenchResult failed(String bench, String name, QueueWorkload w, int reps,
      String why) {
    return new BenchResult(bench, name, w.n, w.seed, reps, false, 0, 0, 0, -1, why);
  }
}
