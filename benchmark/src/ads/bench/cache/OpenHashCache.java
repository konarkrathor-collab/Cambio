package ads.bench.cache;

/**
 * CANDIDATE (doc 05 §4): open-addressing hash map (linear probing) with NO eviction — the
 * second plain-hash baseline (syllabus Unit 2 names both chaining and open addressing).
 * Flat primitive arrays (cache-friendly, zero per-entry node allocation) at the cost of
 * rehashing on growth and probe clustering; unbounded like the chaining map, so its
 * finalSize shows the same growth past capacity.
 */
public final class OpenHashCache implements CacheOps {
  private static final long EMPTY = -1L; // trader ids are >= 1

  private long[] keys = new long[16];
  private long[] vals = new long[16];
  private int mask = 15;
  private int count;

  public OpenHashCache() {
    for (int i = 0; i < keys.length; i++) {
      keys[i] = EMPTY;
    }
  }

  private static int mix(long id, int mask) {
    long h = id * 0x9E3779B97F4A7C15L;
    h ^= h >>> 32;
    h *= 0xBF58476D1CE4E5B9L;
    h ^= h >>> 29;
    return (int) h & mask;
  }

  @Override
  public long access(long traderId) {
    int i = mix(traderId, mask);
    while (true) {
      long k = keys[i];
      if (k == traderId) return vals[i];
      if (k == EMPTY) {
        long bal = AccountStore.balance(traderId);
        keys[i] = traderId;
        vals[i] = bal;
        count++;
        if (count * 10 > keys.length * 6) {
          grow(); // keep load <= 0.6 so probes stay short and an EMPTY slot always exists
        }
        return bal;
      }
      i = (i + 1) & mask;
    }
  }

  private void grow() {
    long[] ok = keys;
    long[] ov = vals;
    int ncap = keys.length * 2;
    keys = new long[ncap];
    vals = new long[ncap];
    for (int j = 0; j < ncap; j++) {
      keys[j] = EMPTY;
    }
    mask = ncap - 1;
    for (int j = 0; j < ok.length; j++) {
      if (ok[j] != EMPTY) {
        int i = mix(ok[j], mask);
        while (keys[i] != EMPTY) i = (i + 1) & mask;
        keys[i] = ok[j];
        vals[i] = ov[j];
      }
    }
  }

  @Override
  public boolean contains(long traderId) {
    int i = mix(traderId, mask);
    while (true) {
      long k = keys[i];
      if (k == traderId) return true;
      if (k == EMPTY) return false;
      i = (i + 1) & mask;
    }
  }

  @Override
  public int size() {
    return count;
  }
}
