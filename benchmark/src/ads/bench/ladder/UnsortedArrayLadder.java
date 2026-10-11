package ads.bench.ladder;

/**
 * CANDIDATE (doc 05 §1): the do-nothing baseline - O(1) put, but every query and remove is
 * a full O(n) scan. Kept in the survey to bound how bad "no structure at all" is.
 */
public final class UnsortedArrayLadder implements LadderOps {
  private final long[] ticks;
  private final long[] vols;
  private int count;

  public UnsortedArrayLadder(int capacity) {
    ticks = new long[capacity];
    vols = new long[capacity];
  }

  private int indexOf(long tick) {
    for (int i = 0; i < count; i++) {
      if (ticks[i] == tick) return i;
    }
    return -1;
  }

  @Override
  public void put(long tick, long volume) {
    int i = indexOf(tick);
    if (i >= 0) {
      vols[i] = volume;
      return;
    }
    ticks[count] = tick;
    vols[count] = volume;
    count++;
  }

  @Override
  public void remove(long tick) {
    int i = indexOf(tick);
    if (i < 0) return;
    int last = count - 1;
    ticks[i] = ticks[last];
    vols[i] = vols[last];
    count = last;
  }

  @Override
  public long bestAsk() {
    if (count == 0) return -1;
    long best = Long.MAX_VALUE;
    for (int i = 0; i < count; i++) {
      if (ticks[i] < best) best = ticks[i];
    }
    return best;
  }

  @Override
  public long bestBid() {
    if (count == 0) return -1;
    long best = Long.MIN_VALUE;
    for (int i = 0; i < count; i++) {
      if (ticks[i] > best) best = ticks[i];
    }
    return best;
  }

  @Override
  public long walkUp(long fromTick, int k) {
    long sum = 0;
    long after = fromTick;
    for (int j = 0; j < k; j++) {
      long best = Long.MAX_VALUE;
      boolean found = false;
      for (int i = 0; i < count; i++) {
        long t = ticks[i];
        if (t >= after && t < best) {
          best = t;
          found = true;
        }
      }
      if (!found) break;
      sum += best;
      after = best + 1;
    }
    return sum;
  }

  @Override
  public long walkDown(long fromTick, int k) {
    long sum = 0;
    long before = fromTick;
    for (int j = 0; j < k; j++) {
      long best = Long.MIN_VALUE;
      boolean found = false;
      for (int i = 0; i < count; i++) {
        long t = ticks[i];
        if (t <= before && t > best) {
          best = t;
          found = true;
        }
      }
      if (!found) break;
      sum += best;
      before = best - 1;
    }
    return sum;
  }

  @Override
  public int size() {
    return count;
  }

  @Override
  public int dump(long[] outTicks, long[] outVols) {
    System.arraycopy(ticks, 0, outTicks, 0, count);
    System.arraycopy(vols, 0, outVols, 0, count);
    return count;
  }
}
