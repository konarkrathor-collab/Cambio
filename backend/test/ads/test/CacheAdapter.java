package ads.test;

import ads.bench.cache.AccountStore;
import ads.bench.cache.CacheOps;
import ads.vault.LruCache;

/**
 * Presents the production {@link LruCache} as the benchmark's {@link CacheOps}, loading
 * misses from the benchmark's {@link AccountStore} exactly as its candidates do.
 */
final class CacheAdapter implements CacheOps {
  private final LruCache<Long> cache;

  CacheAdapter(int capacity) {
    cache = new LruCache<>(capacity, AccountStore::balance);
  }

  @Override
  public long access(long traderId) {
    return cache.get(traderId);
  }

  @Override
  public boolean contains(long traderId) {
    return cache.contains(traderId);
  }

  @Override
  public int size() {
    return cache.size();
  }
}
