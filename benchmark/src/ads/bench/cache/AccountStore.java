package ads.bench.cache;

/**
 * The backing margin store the cache fronts. In the real system this is the slow path
 * (database / ledger lookup); here it is a pure deterministic function of the trader id,
 * so every candidate loads identical values and every replay is reproducible.
 */
public final class AccountStore {
  private AccountStore() {
  }

  /** Deterministic account balance for a trader id (&gt;= 1). Same id -> same balance, always. */
  public static long balance(long traderId) {
    long z = traderId * 0x9E3779B97F4A7C15L;
    z ^= z >>> 30;
    z *= 0xBF58476D1CE4E5B9L;
    z ^= z >>> 27;
    return 1_000L + Math.floorMod(z, 900_000L);
  }
}
