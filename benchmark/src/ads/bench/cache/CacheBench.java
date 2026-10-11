package ads.bench.cache;

import ads.bench.core.AllocCounter;
import ads.bench.core.BenchResult;
import ads.bench.core.Stats;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 2+3 harness for the cache bench. Correctness has TWO parts, per this module's
 * brief: (a) every access returns the correct balance — the replay checksum, for ALL
 * candidates; (b) residency — verified exactly, not sampled:
 * <ul>
 *   <li>unbounded candidates (chaining, open addressing, splay): for every id in
 *       1..universe, resident iff the id appears anywhere in the stream; final size must
 *       equal the stream's distinct-touched count;</li>
 *   <li>the bounded LRU pick: replayed in lockstep against the reference LRU with FULL
 *       residency equality (all ids, not a sample) at three mid-stream checkpoints plus
 *       the end — an eviction-order proof, not a spot check.</li>
 * </ul>
 * Then the usual discipline: warmup reps discarded, timed reps, allocation measurement.
 */
public final class CacheBench {
  private static final long FNV_OFFSET = 0xcbf29ce484222325L;
  private static final long FNV_PRIME = 0x100000001b3L;

  /** Prevents dead-code elimination of final structure state. */
  private static volatile long blackhole;

  public interface Factory {
    CacheOps create();
  }

  private CacheBench() {
  }

  public static long replay(CacheOps s, CacheWorkload w) {
    long checksum = FNV_OFFSET;
    long[] ids = w.traderId;
    for (int i = 0; i < w.n; i++) {
      checksum = (checksum ^ s.access(ids[i])) * FNV_PRIME;
    }
    blackhole ^= s.size();
    return checksum;
  }

  /** Part (a): balance correctness for every candidate. Returns null if OK. */
  public static String verifyBalance(CacheOps candidate, CacheWorkload w, long oracleChecksum) {
    long c = replay(candidate, w);
    if (c != oracleChecksum) {
      return "checksum mismatch (access returned a wrong balance)";
    }
    return null;
  }

  /** Part (b1): exact touched-set residency for the no-eviction candidates. */
  public static String verifyUnboundedResidency(CacheOps candidate, CacheWorkload w) {
    if (candidate.size() != w.distinctTouched) {
      return "size mismatch: " + candidate.size() + " vs distinct-touched " + w.distinctTouched;
    }
    for (int id = 1; id <= w.universe; id++) {
      boolean have = candidate.contains(id);
      if (have != w.touched[id]) {
        return "residency mismatch at id " + id + ": candidate=" + have
            + " stream-touched=" + w.touched[id];
      }
    }
    return null;
  }

  /**
   * Part (b2): the LRU pick's eviction behavior must match the reference EXACTLY.
   * Lockstep replay: both structures see the same access; at each checkpoint (n/4, n/2,
   * 3n/4) and at the end, FULL residency over ids 1..universe must be identical.
   */
  public static String verifyLruResidency(LruHashCache candidate, CacheWorkload w) {
    int capacity = w.capacity;
    LinkedHashMap<Long, Long> ref = new LinkedHashMap<Long, Long>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
        return size() > capacity;
      }
    };
    int[] checkpoints = { w.n / 4, w.n / 2, (3 * w.n) / 4 };
    int next = 0;
    for (int i = 0; i < w.n; i++) {
      if (next < checkpoints.length && i == checkpoints[next]) {
        String p = compareResidency(candidate, ref, w, i);
        if (p != null) return p;
        next++;
      }
      long id = w.traderId[i];
      candidate.access(id);
      Long v = ref.get(id);
      if (v == null) {
        ref.put(id, AccountStore.balance(id));
      }
    }
    return compareResidency(candidate, ref, w, w.n);
  }

  private static String compareResidency(LruHashCache candidate, LinkedHashMap<Long, Long> ref,
      CacheWorkload w, int atOp) {
    boolean[] refResident = new boolean[w.universe + 1];
    for (Long id : ref.keySet()) {
      refResident[id.intValue()] = true;
    }
    for (int id = 1; id <= w.universe; id++) {
      boolean have = candidate.contains(id);
      if (have != refResident[id]) {
        return "LRU residency mismatch at op " + atOp + ": id " + id + " candidate=" + have
            + " reference=" + refResident[id];
      }
    }
    return null;
  }

  public static BenchResult timed(String bench, String name, Factory factory, CacheWorkload w,
      int warmup, int reps, AllocCounter alloc, String note) {
    for (int i = 0; i < warmup; i++) {
      replay(factory.create(), w); // JIT warmup, discarded
    }
    long[] times = new long[reps];
    for (int i = 0; i < reps; i++) {
      System.gc();
      CacheOps s = factory.create();
      long t0 = System.nanoTime();
      replay(s, w);
      times[i] = System.nanoTime() - t0;
    }
    long allocDelta = -1;
    if (alloc.isEnabled()) {
      System.gc();
      long a0 = alloc.bytes();
      CacheOps s = factory.create(); // construction included (table pre-allocation must show)
      replay(s, w);
      allocDelta = alloc.bytes() - a0;
    }
    double median = Stats.median(times);
    return new BenchResult(bench, name, w.n, w.seed, reps, true,
        Stats.min(times) / 1e6, median / 1e6, Stats.max(times) / 1e6,
        allocDelta < 0 ? -1.0 : (double) allocDelta / w.n, note);
  }

  public static BenchResult failed(String bench, String name, CacheWorkload w, int reps,
      String why) {
    return new BenchResult(bench, name, w.n, w.seed, reps, false, 0, 0, 0, -1, why);
  }
}
