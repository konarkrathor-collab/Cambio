# Benchmark Results — Evidence Log

This file accumulates measured results that feed back into `documentation/` (docs 03 and 05).
Raw data: `results/raw_results.csv`. Method: see `../README.md`.

## Run 1 — Ladder v1, narrow band (baseline)

**Command:** `./run.sh ladder --n 300000 --seed 42 --reps 7 --warmup 3`
**Workload:** 300,000 ops (put 156,349 / remove 78,112 / bestAsk 20,631 / bestBid 21,100 / walkUp 11,876 / walkDown 11,932) · range 65,536 ticks · activity band 1,001 ticks · 467 active levels at end · **all candidates validated vs oracle** (checksum `0x578e1efa5f6cfc9b`).

| structure | median ms | ops/sec | alloc B/op* | spread % |
|:---|--:|--:|--:|--:|
| oracle-treeMap (baseline) | 25.22 | 11.9M | 57.8 | 3.9 |
| unsorted-array | 74.66 | 4.0M | 16.0 | 0.2 |
| sorted-array | 21.18 | 14.2M | 16.0 | 3.3 |
| flat-array | **6.24** | **48.1M** | **1.7** | 0.5 |
| skip-list | 34.19 | 8.8M | 83.6 | 3.1 |
| radix16 | 22.28 | 13.5M | 8.6 | 3.0 |

\* alloc B/op includes construction; arrays preallocated to worst-case capacity are charged fairly.

## Run 2 — wide-range adversarial: the flat-array blow-up

**Command:** `./run.sh ladder --n 300000 --seed 42 --reps 7 --warmup 3 --range 16777216`
Identical op mix and band; only the instrument's *tick range* was widened 256× (65,536 → 16,777,216) — exactly doc 05 §1's "multi-instrument, wide tick range" scenario.

| structure | median ms | ops/sec | alloc B/op | Δ vs Run 1 |
|:---|--:|--:|--:|:---|
| oracle-treeMap | 24.64 | 12.2M | 57.8 | unchanged |
| unsorted-array | 74.49 | 4.0M | 16.0 | unchanged |
| sorted-array | 21.16 | 14.2M | 16.0 | unchanged |
| flat-array | **659.81** | 0.45M | **447.4** | **105× slower · 263× memory** |
| skip-list | 34.04 | 8.8M | 83.6 | unchanged |
| radix16 | 22.21 | 13.5M | 8.6 | unchanged |

Flat-array at 447.4 B/op ≈ **≈134 MB per instance** — vs radix16's 8.6 B/op (≈2.6 MB). The range-scaling failure mode predicted in the rationale doc is real and measurable.

## Findings vs doc 05 §1 claims

1. **"Close call" confirmed, not a dominant win.** In a narrow-range book the flat array wins on raw speed (6.24 ms vs radix 22.28 ms) — its 4.10 raw rubric score was justified. Radix16 sits within ~5 % of sorted-array (21.18 ms) and beats the TreeMap baseline (25.22 ms) while using **6.7× less memory per op** (8.6 vs 57.8 B/op). **Boxing caveat:** `TreeMap<Long,Long>` boxes keys/values, so this ratio is an upper bound on the gap versus a primitive-keyed hand-rolled RB — doc 05 §1 carries the same caveat; Ladder v2's RB candidate settles it.
2. **The robustness override is now empirically supported.** Widening the tick range leaves every structure's cost unchanged *except* the flat array (105× time, 263× memory). Radix/skip/array costs scale with ACTIVE levels, not the address space — "empty branches are pruned" as claimed.
3. **Memory ladder (narrow range, B/op incl. construction):** flat 1.7 < radix16 8.6 < sorted 16.0 = unsorted 16.0 < oracle 57.8 < skip-list 83.6. Radix is ~2× leaner than sorted arrays at comparable speed.
4. **Determinism:** spread ≤ 4 % across 7 reps for most candidates (JIT/GC noise); one cold-machine outlier (skip-list 34 % in the very first run) — medians absorb it. No candidate ever failed the oracle.
5. **Unsorted baseline:** ~12× slower than radix16 at this size (O(n) scans) — validates its 2.40 rubric score as the floor.

**Caveats:** JIT-compiled Java (warmups discard first 3 replays); allocation includes constructors; skip-list walkDown is O(k log n) (no back pointers); single-threaded on an Apple M4. Fully reproducible: n=300,000, seed=42, commands above. Raw rows: `results/raw_results.csv`.

## Next

- [x] **Ladder v2a** — BST, AVL, splay, red-black hand-rolled (+ rotation counters; RB retires the TreeMap stand-in) — see Run 10
- [x] Sorted-insertion adversarial pattern (BST degeneration demo) — see Run 11
- [ ] Ladder v2b — 2-3 tree, B-tree, B+ (B+ is doc 05 §1's last unmeasured row)
- [x] Arbitrage (Bellman-Ford, SPFA, Floyd-Warshall, Johnson, Tarjan+BF) — see below
- [x] Queue (array, singly list, DLL, tree-by-id, DLL+hash) — see Run 6
- [x] Cache (hash chaining, open addressing, hash+LRU, splay; Zipfian stream) — see Run 7
- [x] Ticker (hash set, sorted array, TST, trie; two-phase exact/prefix workload) — see Run 8
- [x] Compression (RLE, LZW, Huffman, order-0 arithmetic; round-trip gate) — see Run 9
- [ ] Deeper-book adversarial queue run (array O(n)-shift divergence at realistic depth)

---

## Run 3 — Arbitrage detection, real data: does today's forex market actually have arbitrage?

> **Superseded timing (Runs 3–5, see Run 12b):** sequential, single-scan reps with reconstruction timed; a warm real-data scan is ~0.6 µs, not ~10 µs. The no-arbitrage finding and all correctness results stand.

**Command:** `./run.sh arbitrage --graph real --seed 42 --reps 7 --warmup 3`
**Data:** a genuine captured snapshot — `GET https://api.frankfurter.dev/v1/latest?base=USD`,
2026-10-09, archived verbatim at `data/frankfurter_usd_2026-10-09.json` (the Frankfurter API
doc 06 now keeps as its *fallback* source — see that doc's revision note). 8-currency roster (USD,
EUR, GBP, JPY, AUD, CAD, CHF, INR), complete graph, 56 directed edges, `w = -ln(rate)`.
**Correctness:** verified against an independent brute-force oracle (exhaustive simple-cycle
enumeration — a different algorithm family entirely from shortest-path relaxation or SCC
decomposition, so agreement is real evidence) — feasible here because V=8 is small.

| structure | median ms | edges/sec* | alloc B/op* | spread % | agrees w/ oracle |
|:---|--:|--:|--:|--:|:--:|
| bellman-ford | 0.010 | 5.6M | 3.7 | 13.7 | yes |
| spfa | 0.005 | 11.3M | 20.4 | 47.1 | yes |
| floyd-warshall | 0.013 | 4.4M | 21.3 | 29.9 | yes |
| johnson | 0.010 | 5.6M | 4.0 | 8.4 | yes |
| tarjan+bf | 0.017 | 3.4M | 33.3 | 20.7 | yes |

\* "edges/sec" and "alloc B/op" repurpose the ladder bench's `ops_per_sec`/`alloc_bytes_per_op`
columns — here the denominator is edge count E, not an order-flow op count (see Main.java note).

**Finding: no arbitrage exists in today's real rates.** All five algorithms and the
independent oracle agree — zero negative cycles among these 8 currencies right now. This is
the expected, honest result doc 06 already predicted (genuine triangular arbitrage gets
closed in microseconds by institutional traders, long before a daily snapshot could observe
it) — and it's a meaningful validation in its own right: the full pipeline (real API → graph
construction → 5 independent detection algorithms → independent oracle) was run end-to-end on
live, unmodified, cited data and every piece of it agrees.

## Run 4 — Arbitrage detection, synthetic with injected cycle (small, oracle-verified)

**Command:** `./run.sh arbitrage --graph synthetic --v 8 --inject true --seed 42 --reps 7 --warmup 3`
Same 8-vertex scale, but a deliberately injected 3-cycle (`5→6→7→5`) so the *positive*
detection and cycle-reconstruction code paths get exercised too (Run 3 only proved the
negative case).

| structure | median ms | edges/sec | alloc B/op | spread % | agrees w/ oracle |
|:---|--:|--:|--:|--:|:--:|
| bellman-ford | 0.012 | 4.6M | 6.3 | 30.6 | yes |
| spfa | 0.013 | 4.2M | 23.0 | 20.8 | yes |
| floyd-warshall | 0.015 | 3.7M | 23.9 | 24.3 | yes |
| johnson | 0.012 | 4.7M | 6.6 | 9.1 | yes |
| tarjan+bf | 0.018 | 3.0M | 36.4 | 19.6 | yes |

**Two real bugs found and fixed while building this** (documented here rather than silently
corrected, per this project's own standing policy of showing the mistake, not just the fix):

1. **Generator bug — the free 2-cycle trap.** The first version of the synthetic generator
   shrank only the three *forward* edges of the intended injected cycle. In a
   reciprocal-consistent graph (`w(u,v) + w(v,u) = 0` exactly, by construction), shrinking
   *any single directed edge* makes its own reverse pair negative too — a trivial 2-currency
   "arbitrage" that isn't the one being tested for, and exactly the failure mode
   `documentation/06_market_data_ingestion_service.md` already flagged needing a bid/ask
   spread to prevent. **Fix:** added a `SPREAD = 0.0015` (15 bps) to every directed edge
   (so round-tripping any single pair always costs `2*SPREAD > 0`), and sized the injection
   amount (`0.002`) so it exceeds `SPREAD` (makes the intended 3-cycle negative) but stays
   under `2*SPREAD` (keeps every individual edge's own reciprocal pair positive).
2. **Floyd-Warshall reconstruction bug — chasing the wrong cycle.** In a complete
   (strongly-connected) graph, once *any* negative cycle exists anywhere, *every* vertex's
   `dist[i][i]` goes negative — a negative closed **walk** through it, not necessarily a
   simple cycle *at* it. The first implementation walked `next[][]` from the found vertex
   back to *itself*, which can wander into a *different* embedded cycle and oscillate forever
   without ever returning to the start — caught as a `FAIL` row (`cycle too short (0
   vertices)`) in the raw CSV below. **Fix:** walk forward collecting visited vertices until
   *any* vertex repeats (same idea `CycleTrace` already used for the predecessor-based
   candidates), extract just that sub-loop. The found vertex is only a valid *entry point*
   into *some* reachable negative cycle, never a guarantee the cycle passes through it.

## Run 5 — Arbitrage detection at project scale (V=20, V=50; no independent oracle)

> **Superseded timing (see Run 12b):** these medians were taken sequentially, one microsecond scan per rep after 3 warmup scans, with cycle reconstruction inside the timed region. Johnson (identical code to Bellman-Ford) timing 2× faster here was the symptom. Correctness findings stand; use Run 12b for timing.

**Commands:**
`./run.sh arbitrage --graph synthetic --v 20 --inject false --seed 7 --reps 9 --warmup 3`
`./run.sh arbitrage --graph synthetic --v 50 --inject true --seed 7 --reps 9 --warmup 3`

Exhaustive cycle enumeration is combinatorially infeasible past ~V=10, so these runs verify
against Bellman-Ford's own verdict instead of an independent oracle — explicitly logged as
such (`reference: ... Bellman-Ford verdict (V=20 > 10 — independent oracle infeasible ...)`),
never silently presented as independent proof. This matches the `|V| ≈ 10–50 currencies`
range documentation doc 03 §3 already claims a scan completes in `< 50 microseconds` for.

| structure | V=20, no cycle (median ms) | V=50, injected cycle (median ms) |
|:---|--:|--:|
| bellman-ford | 0.056 | 0.189 |
| spfa | **0.022** | 0.200 |
| floyd-warshall | 0.117 | 0.378 |
| johnson | 0.028 | 0.089 |
| tarjan+bf | 0.119 | 0.322 |

**Findings vs doc 05 §3 claims:**

1. **Both V=20 and V=50 scans complete in well under 1 ms** (20–380 μs range), comfortably
   inside the `< 50 μs`-at-20-currencies target doc 03 claims *when measured from a cold
   `nanoTime()` per rep as done here* — the project's own target was closer to the median
   here once JIT warmup is accounted for; worth noting in the final report as "order of
   magnitude confirmed, exact microsecond figure needs a dedicated JIT-steady-state run."
2. **Floyd-Warshall is the slowest candidate at both scales** (0.117 ms and 0.378 ms,
   2–4× every other candidate) — directly confirms doc 05's O(V³)-overkill rejection with
   real numbers, not just asymptotic argument.
3. **Tarjan+BF is consistently slower than plain Bellman-Ford** (0.119 vs 0.056 ms at V=20;
   0.322 vs 0.189 ms at V=50) — confirms doc 05's prediction that on a complete (single-SCC)
   currency graph, Tarjan's SCC-finding is pure overhead on top of what's still essentially a
   full Bellman-Ford pass.
4. **Johnson and Bellman-Ford track each other closely** (0.028 vs 0.056 ms at V=20; 0.089 vs
   0.189 ms at V=50) — expected, since `JohnsonArbitrage` is implemented as a direct delegate
   to `BellmanFordArbitrage` (see its class doc): Johnson's negative-cycle check *is* a
   Bellman-Ford pass from a virtual source, so measuring them as meaningfully different
   algorithms here would be measuring noise, not a real distinction. The gap visible in this
   run is JIT/GC variance, not an algorithmic difference.
5. **SPFA's picture is genuinely mixed, not a clean win.** It was the fastest candidate at
   V=20 with no cycle present (0.022 ms, ~2.5× faster than Bellman-Ford) — consistent with
   its early-exit advantage when nothing needs re-relaxing. But at V=50 *with* an injected
   cycle, it's roughly tied with (very slightly slower than) Bellman-Ford (0.200 vs 0.189 ms)
   and has a comparable-or-higher spread (61.5% vs 57.2%, though both are noisy at `reps=9`
   and shouldn't be over-read). This is a more nuanced result than "SPFA is faster" — it
   empirically supports doc 05's actual reasoning for keeping Bellman-Ford: not that SPFA is
   slow, but that its runtime is input/queue-dynamics-dependent rather than fixed, which this
   data shows directly (the V=20→V=50 speedup ratio differs sharply between the two
   algorithms, 2.5× vs ~8.6×, where Bellman-Ford's is far more predictable).

**Caveats (same discipline as Run 1/2):** single-threaded, Apple M4, JIT-compiled Java with
only 3 discarded warmup reps and 9 timed reps — these are directional numbers, not
publication-grade statistics; a dedicated higher-rep run with more seeds would be needed to
make a strict determinism claim from the spread% column rather than the qualitative one
above. Full raw rows: `results/raw_results.csv` (including the two failed debugging rows from
the bugs documented in Run 4, kept as an honest record rather than scrubbed).

## Run 6 — Order queue: FIFO execution + arbitrary cancel-by-id

> **Correction (see Run 12a):** the tree-by-id row below measured a handicap — the treap scanned the whole tree for the FIFO head although ids are assigned in arrival order (head = smallest id, O(log n)). Corrected, it is ~2.2× slower than the pick, not 105×. Finding 2 below is withdrawn.

**Command:** `./run.sh queue --n 300000 --seed 42 --reps 7 --warmup 3`
**Workload:** 300,000 ops (enqueue 150,049 / dequeue-head 134,841 / cancel-by-id 12,083 / peek 3,027; 3,125 live orders at the end). The mix and its justification live in `QueueWorkload`'s class javadoc (~8% of arrivals cancelled pre-execution — inside the commonly cited 5–20% exchange band; ~90% execute FIFO; ~2% accumulate as depth). All candidates validated against the `LinkedHashMap` oracle (checksum `0xe9ed43f6fd1fef66` + exact final-state equality) before any timing.

| structure | median ms | ops/sec | alloc B/op | spread % |
|:---|--:|--:|--:|--:|
| oracle-linkedMap (JDK baseline) | 3.06 | 98.0M | 45.2 | 36.2 |
| array (shift-on-cancel) | 5.76 | 52.1M | 0.4 | 6.6 |
| singly-list | 18.63 | 16.1M | 16.0 | 1.2 |
| dll-no-index | 18.58 | 16.1M | 20.0 | 2.0 |
| tree-by-id (treap) | 436.96 | 0.7M | 28.0 | 14.7 |
| **dll+hash (the pick)** | **4.18** | **71.8M** | 20.2 | 4.6 |

**Findings vs doc 05 §2 claims:**

1. **The pick wins, and the hash index's role is visible in the numbers.** dll+hash is the fastest candidate (4.18 ms) — **4.4×** faster than the plain DLL (18.58 ms), whose only deficit is the O(n) cancel-locate scan the hash map removes, and **105×** faster than the tree-by-id treap (436.96 ms).
2. **One honest refinement of the rubric:** the design bullet said a balanced tree keyed by orderId "gives O(log N) everywhere." Measured, that's optimistic — FIFO head operations cost a full **O(n) traversal** (id order ≠ arrival order; 134,841 dequeues × ~3k live nodes), making the tree the worst candidate by two orders of magnitude. The rejection stands, for a stronger reason than the rubric gave.
3. **The honest surprise: the plain array is closer than the rubric implied** — 5.76 ms vs 4.18 ms (1.4×, not orders of magnitude). At this workload's shallow resting depth (~3k live) and ~4% cancel share, its O(n) find+shift costs are small in absolute terms and `System.arraycopy` is cache-friendly; the divergence grows with depth and cancel rate. A deeper-book adversarial run is queued in Next rather than tuned into this one. (The array also allocates just 0.4 B/op — its compaction keeps the backing store tiny.)
4. **vs the JDK baseline:** the hand-rolled pick lands within 1.4× of `LinkedHashMap` (3.06 ms) while allocating **2.2× less per op** (20.2 vs 45.2 B/op — the oracle pays boxing on every entry).
5. **First-run clean sweep:** every candidate passed the oracle immediately — no bugs found in this module (contrast Run 4, where the harness caught two).

**Caveats:** single-threaded Apple M4; 3 discarded warmups + 7 timed reps; the oracle row's 36% spread is typical JIT/GC noise on the shortest-running candidate (medians absorb it). The oracle here is same-family (`LinkedHashMap`) but JDK-hardened — the independence bar this module's brief set. Raw rows appended to `results/raw_results.csv`.

## Run 7 — Account cache: Zipfian margin lookups (LRU vs plain hashes vs splay)

**Command:** `./run.sh cache --n 300000 --seed 42 --reps 7 --warmup 3`
**Workload:** 300,000 accesses over a 100,000-trader universe, Zipf s = 1.0, cache capacity 10,000. Measured skew: the top 10% of accounts receive **81.1%** of all accesses; 47,907 distinct accounts appear in the stream. (A uniform stream would be a methodology error here — see `CacheWorkload`'s javadoc.) Validation: every candidate matched the oracle's balance checksum (`0x624e175ea1e67cf3`); the LRU pick additionally passed **full-residency lockstep equality** against the access-order reference at n/4, n/2, 3n/4 and the end; the no-eviction candidates passed exact touched-set residency checks.

| structure | median ms | ops/sec | alloc B/op | spread % | resident at end |
|:---|--:|--:|--:|--:|--:|
| oracle-linkedMapLRU (JDK baseline) | 7.43 | 40.4M | 37.4 | 19.8 | 10,000 (capacity) |
| hash-chaining (no eviction) | 3.30 | 90.8M | 6.9 | 10.5 | 47,907 |
| hash-open (no eviction) | 2.84 | 105.8M | 14.0 | 10.1 | 47,907 |
| **hash+LRU (the pick)** | **6.10** | **49.2M** | 11.2 | 3.5 | 10,000 (capacity) |
| splay (no eviction) | 36.77 | 8.2M | 6.4 | 5.8 | 47,907 |

**Findings vs doc 05 §4 claims:**

1. **The pick is not the raw-speed winner — and that is exactly the rubric's own trade.** The plain hashes are faster (open addressing 2.84 ms; chaining 3.30 ms — 1.8–2.1× faster than the pick) because they do no recency bookkeeping and never reload evicted accounts. What the pick buys is the thing §4 actually rejected the plain hash map for: **bounded memory**. The pick holds steady state at exactly its capacity; the no-eviction candidates grow to 47,907 residents — 4.8× more accounts than they were configured to keep. Raw speed vs bounded footprint, measured on both sides.
2. **The hand-rolled pick beats the JDK baseline on both axes.** 1.2× faster than `LinkedHashMap` (6.10 vs 7.43 ms) with 3.3× less allocation churn (11.2 vs 37.4 B/op — the oracle boxes every key/value).
3. **Splay is rejected under its ideal workload.** §4 calls splay "genuinely clever for skewed access (the exact Zipfian pattern we have)" — so we gave it exactly that: 81% top-decile skew. It is still 6× slower than the pick and 12.9× slower than open addressing, and it grows unboundedly. Amortized "hot node near the root" theory does not survive a realistic miss stream (18.9% non-hot accesses + 47.9k distinct accounts constantly reshaping the tree). §4's determinism critique ("a single cold lookup can still walk O(N)") is not contradicted anywhere in the numbers.
4. **Allocation-churn nuance (important not to misread the table):** alloc B/op is *lifetime* churn, not steady state. The unbounded maps allocate each account exactly once (6.4–6.9 B/op); the LRU re-allocates evicted accounts when they return (11.2 B/op). Churn and footprint are different metrics, and the pick's real memory win is the resident bound (10,000 vs 47,907 entries).
5. **First-run clean sweep again:** all five rows passed validation immediately (contrast Run 4). The LRU lockstep check — 4 checkpoints × full 100k-id residency equality — passed untouched on the first replay.

**Caveats:** single-threaded Apple M4; 3 discarded warmups + 7 timed reps; one seeded stream at Zipf s = 1.0 (the `--zipf` flag exists for sensitivity runs — a sharper exponent would only strengthen the splay rejection, a flatter one weakens the locality story; noted, not run). Oracle is same-family (`LinkedHashMap`) but JDK-hardened — the independence bar this module's brief set. Raw rows appended to `results/raw_results.csv`.

## Run 8 — Ticker registry: exact-match validation + autocomplete (two-phase)

> **Correction (see Run 12d):** under interleaved timing at two seeds, the hash set is ~1.8× faster than the trie on exact match (not "a statistical tie"), and the trie's prefix lead over TST/sorted-array is ~1.15–1.3× (not ~1.5×). The N/A capability finding and the verdict stand.

**Command:** `./run.sh ticker --n 300000 --seed 42 --reps 7 --warmup 3`
**Data:** a 132-pair universe ("USD/EUR"-style — 12 currencies quoted in every direction; the first 8 match doc 06's roster), loaded once in a seeded shuffle, then queried. **Two phases, deliberately:** phase 1 (insert + contains) gives the hash set — which CANNOT do prefix search — a fair timed baseline; phase 2 (insert + contains 65% / autocomplete 35%, avg 6.9 matches per prefix) is the workload this subsystem exists for. ~60% of contains targets are registered pairs, ~40% synthetic typos; prefix lengths 1–6. All rows oracle-gated (checksums `0x6abd41c49b164a6` / `0x12d276fbe26e1156`; the prefix checksum includes an order-independent fold of every returned match set, since candidates may return matches in any order).

### Phase 1 — exact-match workload (insert + contains)

| structure | median ms | ops/sec | alloc B/op | spread % |
|:---|--:|--:|--:|--:|
| oracle-linearScan (naive baseline) | 40.89 | 7.3M | 0.0 | 3.9 |
| **hash-set** | **2.92** | **102.7M** | 0.0 | 20.6 |
| sorted-array | 11.33 | 26.5M | 0.0 | 6.2 |
| tst | 8.62 | 34.8M | 0.0 | 11.8 |
| trie | 3.03 | 98.9M | 0.2 | 0.7 |

### Phase 2 — prefix workload (insert + contains + prefixSearch)

| structure | median ms | ops/sec | alloc B/op | spread % |
|:---|--:|--:|--:|--:|
| oracle-linearScan (naive baseline) | 63.38 | 4.7M | 0.0 | 0.4 |
| hash-set | — | — | — | **N/A — prefixSearch structurally unsupported** |
| sorted-array | 17.02 | 17.6M | 0.0 | 3.4 |
| tst | 16.39 | 18.3M | 0.0 | 8.5 |
| **trie (the pick)** | **10.63** | **28.2M** | 0.2 | 5.8 |

**Findings vs doc 05 §5 claims:**

1. **The "three-way tie at 3.65" does not survive measurement — it was hiding both a capability gap and a performance gap.** Hash Set is the fastest exact-matcher (2.92 ms — a statistical tie with the trie's 3.03, ~100M ops/s each) and simultaneously **structurally absent from prefix search**: its phase-2 row is an explicit N/A *observed by attempting the workload*, not excluded by assumption. Sorted Array and TST lose to the Trie on the prefix workload (17.02 and 16.39 vs 10.63 ms) *and* to Hash Set on exact match — their tied 3.65 was an artifact of additive scoring, exactly as §5's rubric-limitation note suspected.
2. **The pick wins the workload it exists for**: fastest prefix search by ~1.5×, and the only candidate that is simultaneously ≈best-in-class at exact match (3.03 vs hash-set's 2.92).
3. **Naive baseline**: the linear list — the obvious first draft — is 6–14× slower than the winning structures; the benchmark quantifies why the registry is not just an ArrayList.
4. **Cost structure**: 104,905 autocomplete calls × avg 6.9 matches ≈ 101 ns/op for the trie (the O(L + K) profile made visible); sorted-array's binary-search + contiguous-scan is decent but pays ~7 string comparisons per op.

**Two implementation findings during development (documented, not silently corrected — all variants retained in the raw CSV):**

1. **A real bug, caught by the oracle gate** (third such catch, after Run 4's two): the TST's first `prefixSearch` also enumerated the prefix node's LEFT/RIGHT sibling subtrees — at the prefix's final character position those keys do not share the prefix — returning wrong match sets (`FAIL — checksum mismatch` on the first phase-2 run). Fixed by enumerating only the mid-subtree below the prefix node; the fix is documented in the class itself.
2. **A performance artifact, corrected for fairness**: the trie's first version scanned all 27 child slots per subtree node during autocomplete and lost to the sorted array/TST (34.09 vs ~16.5 ms). Replacing the scan with a **child bitmap** — the same technique the architecture doc specifies for the radix tree's nodes — brought it to 10.63 ms (**3.2×**). Terminal nodes also now store the original string reference (zero-allocation results) — matching what the sorted array gets for free — so the comparison stays structural rather than about result materialization (reconstruction had cost the trie 116.5 B/op of churn in the first run).

**Caveats:** single-threaded Apple M4; 3 discarded warmups + 7 timed reps; one seeded session (load order is a seeded shuffle; TST shape depends on it — not balanced). Everything is at a 132-pair scale; the trie's lead is expected to grow with universe size (fixed-depth walk vs O(log n) compares), but that projection is untested here. Raw rows appended to `results/raw_results.csv` — including the buggy-run and pre-bitmap rows as the honest record.

## Run 9 — Trade-log compression: round-trip-gated ratio/speed bake-off

**Command:** `./run.sh compression --seed 42 --reps 7 --warmup 3`
**Data:** synthetic trade-log text in three batches (256 KB + 1 MB + 4 MB = 5,505,087 bytes, 84,524 lines) — structured, pipe-delimited rows with repetitive fields (pairs, traders, lot sizes, incrementing ORD- ids) and varying content (timestamps, random-walk prices). **Correctness gate = round-trip identity** `decode(encode(x)) == x`, byte-for-byte, per batch — no oracle, exactly the property audit archives need. Ratio = encodedBytes/inputBytes, aggregate across batches; CSV holds two adapter rows per candidate (`phase=encode` / `phase=decode`).

| structure | ratio | enc ms | dec ms | enc MB/s | dec MB/s | alloc/in |
|:---|--:|--:|--:|--:|--:|--:|
| rle (PackBits) | 1.0240 | 6.18 | 3.56 | 890.9 | 1546.6 | 4.02 |
| **lzw** | **0.2962** | 49.88 | 20.49 | 110.4 | 268.7 | 0.80 |
| huffman (the pick) | 0.5874 | 29.54 | 30.99 | 186.3 | 177.6 | 2.09 |
| arithmetic (order-0) | 0.5842 | 152.33 | 212.18 | 36.1 | 25.9 | 1.59 |

**Findings vs doc 05 §6 claims:**

1. **LZW's ratio win is far bigger than the rubric implied.** At 0.296 (3.4:1) LZW beats Huffman by ~2× on this data — repetitive telemetry structure is exactly where dictionary coding shines. §6 conceded LZ-family "compress better in principle"; measured, this is a substantial practical gap, recorded honestly as the strongest counterweight to the pick.
2. **…and LZW is also the candidate that broke — first-hand confirmation of §6's risk argument.** The first version of the from-scratch LZW silently emitted one code for an entire 256 KB batch (a dictionary-lookup initialization bug: empty slots read as a "hit" on entry 0; decoded length 1 of 262,177; FAIL row retained in the raw CSV). The round-trip gate caught it before any ratio number was published. That is *"a single subtle bug silently corrupts archived trade records"* happening in miniature. Huffman, by contrast, round-tripped byte-perfect on its very first run — the doc's "trivial to get correct" claim, demonstrated.
3. **The "fractional bits" advantage of arithmetic coding is real but tiny at order-0:** 0.5842 vs Huffman's 0.5874 (−0.5% relative) at **5× the encode time and 7× the decode time**. This supports §6's actual calculus: the simple arithmetic coder buys ~nothing; the models that make arithmetic/ANS gains large (adaptive/context mixing) are precisely the ones §6 called too risky for a semester.
4. **RLE expands by 2.4%** (ratio 1.0240) — log text has almost no runs; it is the honest floor.
5. **Speed hierarchy:** RLE ≫ Huffman > LZW > arithmetic on encode; LZW's decode (268.7 MB/s) outruns its encode (hash lookups); Huffman runs ~180 MB/s both ways; allocation per input byte is lowest for LZW (0.80) and highest for RLE (4.02, output-buffer growth).

**Verdict, honestly stated:** the pick stands (byte-perfect first try + provable optimality within integer-length codes + curriculum alignment), but the trade's full shape is now measured — if storage cost ever outranked implementation safety for these logs, LZW's 2× smaller archives at ~1.7× the encode cost is the number to revisit, with its encoder/decoder synchronization risk as the price of admission.

**Caveats:** single-threaded Apple M4; 3 discarded warmups + 7 timed reps; aggregates across three batch sizes (per-batch variation not separately reported); one seeded 5.5 MB session; ratio is for *this* data shape — real logs with a different field mix would shift the numbers (the generator is fully documented in `LogWorkload`).

## Run 10 — Ladder v2: hand-rolled BST, AVL, red-black, splay join the walk workload

> **Correction (see Run 12c):** finding 1's "real RB ~12% faster than radix" did not reproduce across three seeds × two runs on a second machine under interleaved timing — radix16 and red-black tie (means 34.6 vs 34.9 ms, 3–3 head-to-head). The memory edge and rotation counters reproduce exactly.

**Command:** `./run.sh ladder --n 300000 --seed 42 --reps 7 --warmup 3`
Same workload as Run 1; the candidate set now includes the four hand-rolled syllabus trees the roadmap promised, each oracle-gated before timing. All ten candidates validated on the first run.

| structure | median ms | ops/sec | alloc B/op | spread % |
|:---|--:|--:|--:|--:|
| oracle-treeMap (boxed baseline) | 23.59 | 12.7M | 57.8 | 6.8 |
| unsorted-array | 69.40 | 4.3M | 16.0 | 0.5 |
| sorted-array | 20.67 | 14.5M | 16.0 | 0.9 |
| flat-array | **6.04** | **49.7M** | **1.7** | 1.4 |
| skip-list | 32.77 | 9.2M | 83.6 | 1.9 |
| radix16 | 21.63 | 13.9M | **8.6** | 17.2 |
| bst | **17.76** | **16.9M** | 10.5 | 2.2 |
| avl | 21.50 | 14.0M | 10.5 | 3.3 |
| **red-black** | **19.26** | **15.6M** | 12.6 | 2.5 |
| splay | 32.57 | 9.2M | 10.5 | 1.8 |

**Rebalance counters (fresh replays of the same workload, single rotations counted individually; double rotations = 2):**
- red-black: 66,857 rotations (insert 37,930 / delete 28,927)
- avl: 78,895 rotations (insert 50,057 / delete 28,838)
- splay: 2,898,896 restructure rotations (informational)

**Findings:**

1. **The TreeMap stand-in is retired — and it reverses the speed half of §1's earlier comparison.** The hand-rolled RB (primitive `long` keys, no boxing) runs 19.3 ms / 12.6 B/op vs the boxed TreeMap's 23.6 ms / 57.8 B/op — 1.22× faster and 4.6× leaner. Against Radix16 (21.6 ms / 8.6 B/op) the real RB is ~12% **faster**, while radix keeps a ~32% memory edge. The §1 verdict's basis shifts honestly to memory + tick-range robustness (Run 2) — not walk-workload speed, which belongs to Red-Black.
2. **The plain BST is the fastest tree on this workload** (17.8 ms — ahead of RB's 19.3 and AVL's 21.5): balance guarantees cost something (RB +8%, AVL +21% over unbalanced) and the walk workload never stresses balance. Run 11 is the other side of that coin.
3. **AVL-vs-RB rotations, measured:** AVL +18% in aggregate (78,895 vs 66,857) — all of it on the insert side (+32%: 50,057 vs 37,930); delete rotations are statistically equal (28,838 vs 28,927). The textbook "AVL pays more rotations" holds as a modest insert-side premium here, not categorically.
4. **Splay:** 2.9M restructure rotations (~43× RB's total); 32.6 ms — heavy machinery, mid-pack time on this access pattern.
5. **Clean sweep:** all ten candidates validated first try, including the CLRS red-black delete fixup — Ladder v2 found no bugs.

**Caveats:** as Run 1 (single-threaded M4; 3 discarded warmups + 7 timed reps); radix16's 17.2% spread is JIT noise on a fast row; this is a fresh full re-run, so the v1 structures' numbers shift a few percent vs Run 1 (normal run-to-run variance).

## Run 11 — Sorted-insertion adversarial: the BST degeneration demo

**Command:** `./run.sh ladder --pattern sorted --n 12000 --band 4000 --seed 42 --reps 7 --warmup 3`
Same op mix as the walk pattern, but PUT targets arrive in strict **ascending** tick order (6,256 ascending inserts through an 8,001-level band; 3,058 removes; ~2,700 queries). This is the workload doc 05 §1's bullet — *"Unbalanced BST degenerates toward O(P) under sorted or monotonic order arrival"* — claimed while explicitly flagging it as having no data to back.

| structure | median ms | ops/sec | alloc B/op | spread % |
|:---|--:|--:|--:|--:|
| oracle-treeMap | 0.88 | 13.7M | 63.8 | 23.0 |
| unsorted-array | 5.21 | 2.3M | 16.0 | 3.2 |
| sorted-array | 1.22 | 9.8M | 16.0 | 20.9 |
| flat-array | 2.89 | 4.1M | 43.7 | 3.1 |
| skip-list | 1.22 | 9.8M | 97.8 | 145.1 |
| radix16 | 0.84 | 14.2M | 20.7 | 12.1 |
| **bst** | **25.64** | **0.47M** | 20.9 | 2.4 |
| avl | 0.83 | 14.4M | 20.9 | 69.2 |
| red-black | 0.71 | 16.9M | 25.0 | 29.2 |
| **splay** | **0.62** | **19.3M** | 20.9 | 3.4 |

**Rebalance counters (same run):** red-black 7,806 (insert 6,158 / delete 1,648); avl 7,635 (insert 6,227 / delete 1,408); splay 40,231 restructures.

**Findings:**

1. **The claim is now backed, dramatically.** The same BST code that is the FASTEST tree on the walk workload (17.8 ms, Run 10) is **~36× slower than every balanced candidate here** (25.6 ms vs ≤ 0.88 ms) — 6,256 monotonic inserts turn it into a ~6,000-deep spine. Same code, different arrival order: the cost of not balancing, quantified.
2. **Everything self-balancing shrugs.** RB, AVL, radix, splay, and both arrays all finish ≤ 2.9 ms; the balanced cluster sits in one sub-millisecond band (0.62–0.88 ms).
3. **Splay is fastest here (0.62 ms)** — ascending insertion is its best case (each new maximum becomes the root in O(1)); the same structure was mid-pack on the walk workload. Its restructure count collapses from 2.9M (walk) to 40k (sorted), visualizing the shape-dependence.
4. **AVL vs RB rotation totals converge entirely under sorted insert** (7,635 vs 7,806) — reinforcing Run 10's finding that the "AVL pays more rotations" effect is workload-dependent, not categorical.

**Caveats:** sub-millisecond rows have noisy spreads (JIT/GC dominates at this absolute scale — skip-list's 145% is noise on a 1.2 ms row); the qualitative gap (25.6 ms vs ≤ 0.88 ms) dwarfs all of it. n=12,000 was sized so the adversarial run completes comfortably given the spine arithmetic (~6,256 distinct ascending inserts); a larger run would widen, not shrink, the BST gap.


## Run 12 — Harness corrections: interleaved timing, treap head lookup, untimed reconstruction

**Why this run exists.** A full review of the lab found four problems with how earlier runs were *timed* (no correctness result was ever wrong — every checksum, op count, ratio and rotation counter from Runs 1–11 reproduced bit-for-bit). Per this project's standing policy they are recorded here, not silently patched:

1. **The tree-by-id queue candidate was handicapped.** `TreeQueueOps` assumed "id order ≠ arrival order" and found the FIFO head with a full O(n) traversal. But `QueueWorkload` has always assigned ids with `nextId++` at arrival — exactly what an exchange sequencer does — so the head is simply the smallest id, a leftmost walk in expected O(log n). Run 6's "436.96 ms, 105× slower than the pick, worst by two orders of magnitude" measured the handicap, not the structure. **Fix:** leftmost-walk head lookup; the `QueueOps` contract now states the id-ordering assumption, and the treap throws on a non-increasing id instead of silently returning the wrong head.
2. **Sequential timing leaked JIT state between candidates.** Each candidate's warmup+reps ran back-to-back, so later candidates were measured under a different JIT/profile/inlining state (the shared `replay` call site goes megamorphic as more classes pass through it). Smoking gun: `JohnsonArbitrage` is a pure delegate to `BellmanFordArbitrage` — identical code — yet timed 0.089 vs 0.189 ms (Run 5) and 0.32 vs 0.83 ms (review re-run) purely from running later. **Fix:** `core/Interleaved.java` — warmup and timed reps run round-robin across all verified candidates with a rotating start (README fairness rule 3b), for the ladder, queue, cache, ticker and arbitrage benches. (Compression keeps its per-candidate encode/decode phases; its rows are tens of ms and its conclusions are ratio-driven.)
3. **Single-machine speed rankings were treated as settled.** The review re-run on a different machine flipped Run 10's "red-black ~12% faster than radix" and Run 8's "hash set and trie tie on exact match". **Fix:** not a code fix — this run measures the ladder at three seeds × two runs and the other benches at two seeds, and README rule 3c now says gaps under ~15–20% are ties unless they reproduce.
4. **Arbitrage timed cycle reconstruction**, although `GraphOps` documents it as verification-only, and each "rep" was a single microsecond scan after only 3 warmup scans (the first candidate was measured largely before JIT compilation). **Fix:** timed unit = build + `hasNegativeCycle()` only; each rep is a batch of scans sized to ~2M edge relaxations; every candidate warms up for ≥ 2,000 scans. Medians are now **per scan**.

**Machine:** these numbers come from a Linux cloud container (JDK 21), **not the Apple M4** of Runs 1–11, so absolute times are not comparable to earlier runs — only the ratios and rankings below are. All commands use `--reps 9 --warmup 3`; every candidate in every run below passed its correctness gate. Raw rows appended to `raw_results.csv`.

### 12a — Order queue with the corrected treap (`./run.sh queue --n 300000 --seed {42,7} --reps 9 --warmup 3`)

| structure | median ms (seed 42) | median ms (seed 7) | vs pick | alloc B/op |
|:---|--:|--:|--:|--:|
| oracle-linkedMap (JDK baseline) | 10.00 | 9.17 | ~1.0–1.1× | 45.2 |
| array (shift-on-cancel) | 11.40 | 9.78 | 1.0–1.3× | 0.4 |
| singly-list | 28.10 | 23.88 | 2.5–3.1× | 16.0 |
| dll-no-index | 28.41 | 26.08 | 2.7–3.2× | 20.0 |
| **tree-by-id (treap, corrected)** | **20.24** | **20.06** | **2.1–2.3×** | 24.0 |
| **dll+hash (the pick)** | **8.97** | **9.59** | — | 20.2 |

**Correction to Run 6 / doc 05 §2:** the treap is ~2.2× slower than the pick, **not 105×**, and it beats both un-indexed lists. The rubric's design bullet — "a balanced tree keyed by orderId gives O(log N) everywhere, strictly worse than O(1)" — was right all along; Run 6's "optimistic" refinement of it was an artifact of the handicap. The pick still wins, but on this shallow book (~3k live orders) its lead over the plain array is within noise (seed 7: 9.59 vs 9.78 ms) — Run 6's honest-surprise finding holds more strongly than it did.

### 12b — Arbitrage, per-scan, interleaved (`./run.sh arbitrage ... --reps 9 --warmup 3`)

| structure | real V=8 (µs) | V=8 injected (µs) | V=20, no cycle, seed 7 (µs) | V=50 injected, seed 7 (µs) |
|:---|--:|--:|--:|--:|
| bellman-ford | **0.6** | 0.7 | 9.1 | 160.7 |
| johnson (BF delegate) | 0.6 | 0.6 | 8.6 | 145.3 |
| spfa | 1.0 | 1.5 | **3.4** | 216.7 |
| floyd-warshall | 2.0 | 2.3 | 13.0 | 160.7 |
| tarjan+bf | 2.4 | 3.5 | 16.3 | 284.0 |

1. **The bias is gone:** Johnson now tracks Bellman-Ford within run noise at every scale (0.6/0.6, 8.6/9.1, 145/161 µs), as its implementation guarantees.
2. **Run 3–5's small-graph numbers were mostly JIT warmup.** A warm real-data scan is ~0.6 µs, not the 10 µs Run 3 reported.
3. **Doc 03/04 latency targets, re-checked:** doc 04's "< 100 µs at 20 currencies" holds with ~10× headroom (9.1 µs). Doc 03's "< 50 µs for 10–50 currencies" holds at V=20 but **not** at V=50 with a cycle (161 µs) — Run 5 had said the opposite while quoting a 56 µs V=20 median.
4. **SPFA:** fastest when no cycle exists (V=20: 2.7× faster than BF) and slowest-but-Tarjan with one (V=50: 1.35× slower) — the input-dependence doc 05 §3 cites, now measured cleanly. (Run 5's "2.5× vs ~8.6×" scaling sentence was garbled; on these graphs SPFA goes 3.4→216.7 µs, ×64, vs BF's 9.1→160.7 µs, ×18 — different graphs, cycle vs none, so read it as "SPFA's cost depends on whether there is a cycle", not as an asymptotic claim.)
5. **Floyd-Warshall is no longer "slowest at both scales":** it ties Bellman-Ford at V=50 (160.7 µs each — its O(V³) inner loop is branch-light and cache-friendly) and is 1.4–3.5× slower at V=8/20. The rejection still stands on doc 05 §3's actual grounds (all-pairs work we don't need, O(V²) memory); the "2–4× slower everywhere" evidence did not survive fair timing.

### 12c — Price ladder robustness: three seeds × two runs (`./run.sh ladder --n 300000 --seed {42,7,1234} --reps 9 --warmup 3`, each twice)

| structure | 6 medians (ms) | mean | B/op |
|:---|:--|--:|--:|
| flat-array | 13.40 13.06 13.64 12.69 11.71 11.35 | **12.6** | 1.7 |
| radix16 | 36.70 33.79 33.07 38.24 31.69 33.99 | **34.6** | 8.6 |
| red-black | 34.57 36.71 36.42 34.89 33.43 33.53 | **34.9** | 12.6 |
| bst | 37.67 35.00 37.56 34.56 30.79 34.90 | 35.1 | 10.5 |
| sorted-array | 40.45 41.00 43.75 36.81 35.50 39.69 | 39.5 | 16.0 |
| avl | 41.64 41.89 47.39 42.54 45.63 39.31 | 43.1 | 10.5 |
| oracle-treeMap | 53.37 55.49 55.04 51.87 56.29 51.47 | 53.9 | 57.8 |
| skip-list | 55.84 56.30 59.41 54.66 60.17 58.45 | 57.5 | 83.4 |
| splay | 58.58 59.04 63.36 59.94 57.79 59.11 | 59.6 | 10.5 |
| unsorted-array | 134.68 132.14 134.19 138.10 143.12 131.86 | 135.7 | 16.0 |

1. **Radix16 vs red-black is a tie, not a red-black win.** Each wins 3 of 6 head-to-heads; means differ by 1%. Run 10's "real RB ~12% faster than radix" did not reproduce on a second machine — it is withdrawn. Plain BST joins the same tie on this (random-walk) workload.
2. **What does reproduce exactly:** radix's memory edge (8.6 vs 12.6 B/op, ~32% less, identical on every seed and machine), the rotation counters (seed 42: RB 66,857 / AVL 78,895, as Run 10; AVL +18% at every seed), and flat-array's narrow-range speed lead (~2.7× faster than radix here).
3. **Wide range (`--range 16777216`, seed 42):** flat-array 3,668 ms / 447.4 B/op vs radix16 33.1 ms / 8.6 B/op; every other structure unchanged — Run 2's robustness result reproduces (more dramatically on this machine: ~270× slower than at narrow range).
4. **Sorted adversarial (`--pattern sorted --n 12000 --band 4000`):** BST 44.7 ms vs every balanced candidate ≤ 1.43 ms (31×+) — Run 11 reproduces. Rotation counts identical to Run 11.

### 12d — Ticker and cache rankings across seeds (seed 42 / seed 7)

- **Ticker, exact match:** hash-set 5.46/5.35 ms, trie 9.69/9.51, TST 16.0/16.0, sorted-array 22.2/21.2. **The trie is ~1.8× slower than the hash set, not a "statistical tie"** (Run 8) — withdrawn. **Prefix:** trie 35.4/34.8 ms vs TST 40.7/44.7 and sorted-array 46.7/44.0 — the trie still wins the workload it exists for, but by ~1.15–1.3×, not Run 8's ~1.5×. Hash set N/A as before.
- **Cache:** hash+LRU 14.0/15.3 ms vs open addressing 7.1/7.5 (pick ~2× slower, as Run 7 said), splay 78.7/81.2 (~5.5× slower than the pick); oracle LinkedHashMap 24.7/18.7 (the pick is 1.2–1.8× faster). Run 7's findings reproduce in shape.

### What changes in the verdicts

None of the six picks changes. Three pieces of supporting evidence are corrected: the treap is a 2.2× loser, not a 105× one (§2); the walk-workload speed comparison between radix and red-black is a tie, so §1's verdict rests on memory and tick-range robustness alone (it already said so); Floyd-Warshall's rejection rests on its stated grounds, not on being slowest everywhere (§3). Docs 05 and 07 are updated accordingly.
