package ads.bench.cache;

/**
 * CONTRACT (Phase 0): the active-trader account cache interface every candidate must
 * satisfy. Mirrors the margin/balance cache from documentation doc 05 §4: point lookups
 * keyed by traderId on the order-submission hot path, with no range queries.
 *
 * <p>Semantics (FROZEN; the oracle in {@link LruReferenceOracle} — a bounded
 * access-order LRU — is the normative reference for residency):
 * <ul>
 *   <li>{@code access(traderId)}: returns the account's balance. On a HIT the resident
 *       copy is returned (and counts as a use for eviction purposes); on a MISS the
 *       account is loaded via {@link AccountStore#balance(long)} — the candidate MAY
 *       evict to respect its configured capacity — and the loaded balance is returned.
 *       Returned balances are identical for every correct candidate; that is what the
 *       replay checksum validates.</li>
 *   <li>{@code contains(traderId)}: residency probe ONLY. MUST NOT load anything and MUST
 *       NOT count as a use or alter eviction/recency order — the residency verifier calls
 *       it hundreds of thousands of times, and a touching contains() would desynchronise
 *       the LRU candidate from the reference.</li>
 *   <li>{@code size()}: resident accounts.</li>
 *   <li>Boundedness is per-candidate: the LRU pick is configured with a fixed capacity;
 *       the no-eviction candidates (plain hashes, splay) are expected to grow
 *       unboundedly — their final size IS the measured cost, not a correctness failure.</li>
 * </ul>
 */
public interface CacheOps {
  long access(long traderId);

  boolean contains(long traderId);

  int size();
}
