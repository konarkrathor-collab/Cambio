package ads.bench.ladder;

import java.util.Map;
import java.util.TreeMap;

/**
 * ORACLE (Phase 1): the normative, obviously-correct reference implementation.
 *
 * <p>Uses {@code java.util.TreeMap} - allowed HERE ONLY. The oracle is the correctness
 * gate, not a candidate for selection. Every candidate must match it bit-for-bit on:
 * (a) the accumulated query checksum, and (b) the final dump (tick -> volume, plus size).
 */
public final class TreeMapLadder implements LadderOps {
  private final TreeMap<Long, Long> levels = new TreeMap<>();

  @Override
  public void put(long tick, long volume) {
    levels.put(tick, volume);
  }

  @Override
  public void remove(long tick) {
    levels.remove(tick);
  }

  @Override
  public long bestAsk() {
    return levels.isEmpty() ? -1 : levels.firstKey();
  }

  @Override
  public long bestBid() {
    return levels.isEmpty() ? -1 : levels.lastKey();
  }

  @Override
  public long walkUp(long fromTick, int k) {
    long sum = 0;
    Map.Entry<Long, Long> e = levels.ceilingEntry(fromTick);
    for (int i = 0; i < k && e != null; i++, e = levels.higherEntry(e.getKey())) {
      sum += e.getKey();
    }
    return sum;
  }

  @Override
  public long walkDown(long fromTick, int k) {
    long sum = 0;
    Map.Entry<Long, Long> e = levels.floorEntry(fromTick);
    for (int i = 0; i < k && e != null; i++, e = levels.lowerEntry(e.getKey())) {
      sum += e.getKey();
    }
    return sum;
  }

  @Override
  public int size() {
    return levels.size();
  }

  @Override
  public int dump(long[] ticks, long[] volumes) {
    int i = 0;
    for (Map.Entry<Long, Long> e : levels.entrySet()) {
      ticks[i] = e.getKey();
      volumes[i] = e.getValue();
      i++;
    }
    return i;
  }
}
