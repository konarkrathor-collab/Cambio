package ads.bench.ladder;

import ads.bench.core.Rng;

/**
 * DATA (Phase 1): generates ONE deterministic, immutable workload (operation log) that
 * every candidate replays identically.
 *
 * <p>Pattern "walk" (v1): activity random-walks within a band of {@code 2*bandHalf+1} ticks
 * centred at {@code base + range/2}, with a realistic mix of puts, removes, best-price
 * peeks and short depth walks. Removes always target currently-populated ticks (the
 * generator tracks liveness itself), so the log is valid for every correct structure.
 *
 * <p>Pattern "sorted" (Ladder v2 adversarial — doc 05 §1's monotonic-arrival claim): the
 * identical op mix, but PUT targets arrive in strictly ASCENDING tick order (cycling the
 * band if puts ever outnumber its levels). Ascending insertion is the classic degeneration
 * trigger for unbalanced BSTs; every self-balancing structure should be unaffected — which
 * is exactly what the run measures. Size it with a wider band so inserts stay distinct:
 * {@code --pattern sorted --n 12000 --band 4000}.
 */
public final class LadderWorkload {
  public static final byte PUT = 0;
  public static final byte REMOVE = 1;
  public static final byte BEST_ASK = 2;
  public static final byte BEST_BID = 3;
  public static final byte WALK_UP = 4;
  public static final byte WALK_DOWN = 5;

  public final int n;
  public final long seed;
  public final long base;
  public final int range;
  public final byte[] op;
  public final long[] tick; // key for value ops; fromTick for walks
  public final long[] vol;  // volume for PUT; k for WALK_UP / WALK_DOWN

  private LadderWorkload(int n, long seed, long base, int range,
      byte[] op, long[] tick, long[] vol) {
    this.n = n;
    this.seed = seed;
    this.base = base;
    this.range = range;
    this.op = op;
    this.tick = tick;
    this.vol = vol;
  }

  public static LadderWorkload generate(int n, long seed, long base, int range, int bandHalf,
      String pattern) {
    boolean sorted = "sorted".equals(pattern);
    if (!sorted && !"walk".equals(pattern)) {
      throw new IllegalArgumentException(
          "unsupported pattern: " + pattern + " (supports: walk, sorted)");
    }
    if (bandHalf < 1 || 2 * bandHalf + 1 > range) {
      throw new IllegalArgumentException("band (" + bandHalf + ") does not fit in range (" + range + ")");
    }
    Rng rng = new Rng(seed);
    byte[] op = new byte[n];
    long[] tick = new long[n];
    long[] vol = new long[n];
    int bandSize = 2 * bandHalf + 1;
    int mid = range / 2;
    long bandStart = base + mid - bandHalf;
    boolean[] active = new boolean[bandSize];
    int activeCount = 0;
    int putCursor = 0; // sorted pattern: ascending cursor through the band

    for (int i = 0; i < n; i++) {
      byte o;
      if (activeCount == 0) {
        o = PUT;
      } else {
        int roll = rng.nextInt(1000);
        if (roll < 520) o = PUT;
        else if (roll < 780) o = REMOVE;
        else if (roll < 850) o = BEST_ASK;
        else if (roll < 920) o = BEST_BID;
        else if (roll < 960) o = WALK_UP;
        else o = WALK_DOWN;
      }
      switch (o) {
        case PUT: {
          int idx = sorted ? putCursor++ % bandSize : rng.nextInt(bandSize);
          tick[i] = bandStart + idx;
          vol[i] = 1 + rng.nextInt(1_000_000);
          if (!active[idx]) {
            active[idx] = true;
            activeCount++;
          }
          break;
        }
        case REMOVE: {
          int idx = rng.nextInt(bandSize);
          if (!active[idx]) {
            for (int s = 1; s < bandSize; s++) {
              int j = (idx + s) % bandSize;
              if (active[j]) {
                idx = j;
                break;
              }
            }
          }
          tick[i] = bandStart + idx;
          active[idx] = false;
          activeCount--;
          break;
        }
        case WALK_UP:
        case WALK_DOWN: {
          tick[i] = bandStart + rng.nextInt(bandSize);
          vol[i] = 1 + rng.nextInt(12); // k levels
          break;
        }
        default: {
          tick[i] = 0;
          vol[i] = 0;
          break;
        }
      }
      op[i] = o;
    }
    return new LadderWorkload(n, seed, base, range, op, tick, vol);
  }

  /** Human-readable summary of the operation mix (for logs). */
  public String describe() {
    int put = 0, remove = 0, ask = 0, bid = 0, up = 0, down = 0;
    for (byte o : op) {
      switch (o) {
        case PUT: put++; break;
        case REMOVE: remove++; break;
        case BEST_ASK: ask++; break;
        case BEST_BID: bid++; break;
        case WALK_UP: up++; break;
        default: down++; break;
      }
    }
    return String.format(
        "n=%d seed=%d range=%d | put=%d remove=%d ask=%d bid=%d walkUp=%d walkDown=%d",
        n, seed, range, put, remove, ask, bid, up, down);
  }
}
