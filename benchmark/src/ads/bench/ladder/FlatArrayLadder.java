package ads.bench.ladder;

/**
 * CANDIDATE (doc 05 §1): the direct-indexed flat array - true O(1) indexing and trivial to
 * implement, but memory scales with the tick RANGE, not the number of ACTIVE levels
 * (a wide-range instrument forces an enormous sparse array). The allocation counter makes
 * this weakness directly visible in the results table.
 */
public final class FlatArrayLadder implements LadderOps {
  private final long base;
  private final long[] volumes; // 0 = level not populated
  private int count;
  private int minIdx = -1;
  private int maxIdx = -1;

  public FlatArrayLadder(long base, int range) {
    this.base = base;
    this.volumes = new long[range];
  }

  private int index(long tick) {
    return (int) (tick - base);
  }

  @Override
  public void put(long tick, long volume) {
    int i = index(tick);
    if (volumes[i] == 0) {
      count++;
      if (count == 1) {
        minIdx = i;
        maxIdx = i;
      } else {
        if (i < minIdx) minIdx = i;
        if (i > maxIdx) maxIdx = i;
      }
    }
    volumes[i] = volume;
  }

  @Override
  public void remove(long tick) {
    int i = index(tick);
    if (volumes[i] == 0) return;
    volumes[i] = 0;
    count--;
    if (count == 0) {
      minIdx = -1;
      maxIdx = -1;
      return;
    }
    if (i == minIdx) {
      int j = i + 1;
      while (volumes[j] == 0) j++; // bounded by range; count > 0 guarantees a hit
      minIdx = j;
    }
    if (i == maxIdx) {
      int j = i - 1;
      while (volumes[j] == 0) j--;
      maxIdx = j;
    }
  }

  @Override
  public long bestAsk() {
    return count == 0 ? -1 : base + minIdx;
  }

  @Override
  public long bestBid() {
    return count == 0 ? -1 : base + maxIdx;
  }

  @Override
  public long walkUp(long fromTick, int k) {
    int i = Math.max(0, index(fromTick));
    long sum = 0;
    while (i < volumes.length && k > 0) {
      if (volumes[i] != 0) {
        sum += base + i;
        k--;
      }
      i++;
    }
    return sum;
  }

  @Override
  public long walkDown(long fromTick, int k) {
    int i = Math.min(volumes.length - 1, index(fromTick));
    long sum = 0;
    while (i >= 0 && k > 0) {
      if (volumes[i] != 0) {
        sum += base + i;
        k--;
      }
      i--;
    }
    return sum;
  }

  @Override
  public int size() {
    return count;
  }

  @Override
  public int dump(long[] outTicks, long[] outVols) {
    int n = 0;
    for (int i = 0; i < volumes.length; i++) {
      if (volumes[i] != 0) {
        outTicks[n] = base + i;
        outVols[n] = volumes[i];
        n++;
      }
    }
    return n;
  }
}
