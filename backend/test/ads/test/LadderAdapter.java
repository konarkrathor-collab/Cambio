package ads.test;

import ads.bench.ladder.LadderOps;
import ads.engine.PriceLadder;
import ads.engine.PriceLevel;

/**
 * Presents a production {@link PriceLadder} as the benchmark's {@link LadderOps}, so the
 * benchmark's own replay, oracle and gate run against it unchanged. A level's volume is
 * the {@link PriceLevel}'s aggregate volume; walks sum the prices of the levels returned.
 */
final class LadderAdapter implements LadderOps {
  private final PriceLadder ladder;
  private PriceLevel[] buf = new PriceLevel[64];

  LadderAdapter(PriceLadder ladder) {
    this.ladder = ladder;
  }

  @Override
  public void put(long tick, long volume) {
    PriceLevel l = ladder.getOrCreate(tick);
    l.adjustVolume(volume - l.totalVolume());
  }

  @Override
  public void remove(long tick) {
    ladder.remove(tick);
  }

  @Override
  public long bestAsk() {
    PriceLevel l = ladder.lowest();
    return l == null ? -1 : l.price();
  }

  @Override
  public long bestBid() {
    PriceLevel l = ladder.highest();
    return l == null ? -1 : l.price();
  }

  @Override
  public long walkUp(long fromTick, int k) {
    if (k > buf.length) buf = new PriceLevel[k];
    int n = ladder.walkUp(fromTick, k, buf);
    long sum = 0;
    for (int i = 0; i < n; i++) sum += buf[i].price();
    return sum;
  }

  @Override
  public long walkDown(long fromTick, int k) {
    if (k > buf.length) buf = new PriceLevel[k];
    int n = ladder.walkDown(fromTick, k, buf);
    long sum = 0;
    for (int i = 0; i < n; i++) sum += buf[i].price();
    return sum;
  }

  @Override
  public int size() {
    return ladder.size();
  }

  /** Verification only: every level, via one full walk. */
  @Override
  public int dump(long[] ticks, long[] volumes) {
    PriceLevel[] all = new PriceLevel[ladder.size()];
    int n = ladder.walkUp(Long.MIN_VALUE, all.length, all);
    for (int i = 0; i < n; i++) {
      ticks[i] = all[i].price();
      volumes[i] = all[i].totalVolume();
    }
    return n;
  }
}
