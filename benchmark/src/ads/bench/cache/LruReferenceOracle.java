package ads.bench.cache;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ORACLE (Phase 1): the normative LRU — a {@code LinkedHashMap} in ACCESS-ORDER mode with
 * {@code removeEldestEntry} enforcing the configured capacity. JDK-hardened, and exactly
 * the eviction policy the project's pick must match; the residency verifier replays this
 * in lockstep as the reference (and it doubles as the timed baseline row). Per this
 * module's brief, a JDK container is the accepted independence bar for the oracle —
 * every candidate is hand-rolled, and the exact-residency comparison makes any divergence
 * from this reference an unambiguous failure.
 */
public final class LruReferenceOracle implements CacheOps {
  private final int capacity;
  private final LinkedHashMap<Long, Long> map;

  public LruReferenceOracle(int capacity) {
    this.capacity = capacity;
    this.map = new LinkedHashMap<Long, Long>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
        return size() > LruReferenceOracle.this.capacity;
      }
    };
  }

  @Override
  public long access(long traderId) {
    Long v = map.get(traderId); // get() in access-order mode IS the hit-touch
    if (v != null) return v;
    long bal = AccountStore.balance(traderId);
    map.put(traderId, bal);
    return bal;
  }

  @Override
  public boolean contains(long traderId) {
    return map.containsKey(traderId); // containsKey does not touch, even in access order
  }

  @Override
  public int size() {
    return map.size();
  }
}
