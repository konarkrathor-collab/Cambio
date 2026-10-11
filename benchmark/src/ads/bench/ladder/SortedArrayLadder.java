package ads.bench.ladder;

/**
 * CANDIDATE (doc 05 §1): sorted parallel arrays - O(log n) search, but O(n) memmove on
 * insert/delete. Demonstrates why "cheap lookup" alone does not save an array for a
 * mutation-heavy price ladder.
 */
public final class SortedArrayLadder implements LadderOps {
  private final long[] ticks;
  private final long[] vols;
  private int count;

  public SortedArrayLadder(int capacity) {
    ticks = new long[capacity];
    vols = new long[capacity];
  }

  /** First index with ticks[i] >= tick. */
  private int lowerBound(long tick) {
    int lo = 0;
    int hi = count;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (ticks[mid] < tick) lo = mid + 1;
      else hi = mid;
    }
    return lo;
  }

  /** First index with ticks[i] > tick. */
  private int upperBound(long tick) {
    int lo = 0;
    int hi = count;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (ticks[mid] <= tick) lo = mid + 1;
      else hi = mid;
    }
    return lo;
  }

  @Override
  public void put(long tick, long volume) {
    int i = lowerBound(tick);
    if (i < count && ticks[i] == tick) {
      vols[i] = volume;
      return;
    }
    System.arraycopy(ticks, i, ticks, i + 1, count - i);
    System.arraycopy(vols, i, vols, i + 1, count - i);
    ticks[i] = tick;
    vols[i] = volume;
    count++;
  }

  @Override
  public void remove(long tick) {
    int i = lowerBound(tick);
    if (i >= count || ticks[i] != tick) return;
    System.arraycopy(ticks, i + 1, ticks, i, count - i - 1);
    System.arraycopy(vols, i + 1, vols, i, count - i - 1);
    count--;
  }

  @Override
  public long bestAsk() {
    return count == 0 ? -1 : ticks[0];
  }

  @Override
  public long bestBid() {
    return count == 0 ? -1 : ticks[count - 1];
  }

  @Override
  public long walkUp(long fromTick, int k) {
    long sum = 0;
    int i = lowerBound(fromTick);
    for (int j = 0; j < k && i < count; j++, i++) {
      sum += ticks[i];
    }
    return sum;
  }

  @Override
  public long walkDown(long fromTick, int k) {
    long sum = 0;
    int i = upperBound(fromTick) - 1;
    for (int j = 0; j < k && i >= 0; j++, i--) {
      sum += ticks[i];
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
