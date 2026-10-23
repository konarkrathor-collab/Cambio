package ads.sim;

/**
 * FNV-1a, 64-bit, over a sequence of longs (each folded in as 8 little-endian bytes). The
 * simulator's {@code tradeChecksum}: the engine and the naive baseline fold every trade's
 * fields in the same order, so equal checksums mean the same trades in the same order.
 */
public final class Fnv64 {
  public static final long OFFSET_BASIS = 0xcbf29ce484222325L;
  public static final long PRIME = 0x100000001b3L;

  private long hash = OFFSET_BASIS;

  public void add(long v) {
    long h = hash;
    for (int i = 0; i < 8; i++) {
      h ^= (v >>> (8 * i)) & 0xff;
      h *= PRIME;
    }
    hash = h;
  }

  /** Folds in one trade: id, seq, pair, price, quantity, both order ids, both traders, taker side. */
  public void addTrade(long tradeId, long seq, int pairId, long priceTick, long quantity,
      long buyOrderId, long sellOrderId, long buyerId, long sellerId, boolean takerBuys) {
    add(tradeId);
    add(seq);
    add(pairId);
    add(priceTick);
    add(quantity);
    add(buyOrderId);
    add(sellOrderId);
    add(buyerId);
    add(sellerId);
    add(takerBuys ? 1 : 0);
  }

  public long value() {
    return hash;
  }

  /** The checksum as 16 lower-case hex digits. */
  public static String hex(long v) {
    String s = Long.toHexString(v);
    return "0".repeat(16 - s.length()) + s;
  }
}
