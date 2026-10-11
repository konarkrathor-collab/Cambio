package ads.bench.ticker;

/**
 * CONTRACT (Phase 0): the currency ticker registry interface every candidate must satisfy.
 * Mirrors doc 05 §5: validate a pair exists (contains) and autocomplete partial input
 * (prefixSearch) over a tiny fixed alphabet (A-Z plus '/') and a small dataset (~hundreds
 * of pairs).
 *
 * <p>Semantics (FROZEN; the oracle in {@link LinearListOracle} is the normative reference):
 * <ul>
 *   <li>Tickers are "AAA/BBB" strings over A-Z plus '/'; the workload inserts each at most
 *       once.</li>
 *   <li>{@code insert(ticker)}: register a pair (idempotent for the same string).</li>
 *   <li>{@code contains(ticker)}: exact-match validation.</li>
 *   <li>{@code prefixSearch(prefix, out)}: fill {@code out} with every registered ticker
 *       starting with {@code prefix}, in ANY order, and return the count. The caller
 *       guarantees {@code out} is large enough (it is sized to the universe — the standard
 *       caller-provided-buffer pattern, which avoids per-query allocation).</li>
 *   <li><b>Structural incapacity is part of this benchmark's finding:</b> a candidate whose
 *       structure cannot answer prefix queries AT ALL (a hash set has no prefix ordering to
 *       walk) must throw {@link UnsupportedOperationException} — the harness reports that
 *       as an explicit N/A row, never a silent skip. That asymmetry is exactly what doc 05
 *       §5's rubric-limitation note flagged when Hash Set still tied the TST and Sorted
 *       Array at 3.65.</li>
 *   <li>{@code size()}: registered pairs.</li>
 * </ul>
 */
public interface TickerOps {
  void insert(String ticker);

  boolean contains(String ticker);

  int prefixSearch(String prefix, String[] out);

  int size();
}
