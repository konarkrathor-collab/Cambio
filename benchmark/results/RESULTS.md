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
- [x] Ladder v2b — 2-3 tree, B-tree, B+ — see Run 13a (B+ is the strongest tree measured; open tension recorded in doc 05 §1)
- [x] Arbitrage (Bellman-Ford, SPFA, Floyd-Warshall, Johnson, Tarjan+BF) — see below
- [x] Queue (array, singly list, DLL, tree-by-id, DLL+hash) — see Run 6
- [x] Cache (hash chaining, open addressing, hash+LRU, splay; Zipfian stream) — see Run 7
- [x] Ticker (hash set, sorted array, TST, trie; two-phase exact/prefix workload) — see Run 8
- [x] Compression (RLE, LZW, Huffman, order-0 arithmetic; round-trip gate) — see Run 9
- [x] Deeper-book adversarial queue run — see Run 13b (array 12–13× behind the pick at ~15k depth)
- [x] Resident-footprint (not churn) memory measurement for every ladder candidate — see Run 14 (radix holds ~2× the B+ tree and 9–22% more than red-black)

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

1. **Both V=20 and V=50 scans complete in well under 1 ms** (20–380 μs range). *(Corrected:
   this bullet originally called that "comfortably inside" doc 03's `< 50 μs` target, but its
   own V=20 Bellman-Ford median is 56 μs — over the target — and V=50 is 189 μs. Measured
   properly — warm, per scan — in Run 12b: 9.1 μs at V=20 (inside the target), 161 μs at V=50
   with a cycle (outside it).)*
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
   data shows directly (the V=20→V=50 slowdown differs sharply between the two algorithms:
   SPFA 0.022→0.200 ms is ≈9.1×, Bellman-Ford 0.056→0.189 ms is ≈3.4×). *(Corrected: this
   sentence originally read "speedup ratio … 2.5× vs ~8.6×", which matched neither algorithm's
   scaling; 2.5× was SPFA's lead over Bellman-Ford at V=20.)*

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

## Run 13 — Ladder v2b (2-3 tree, B-tree, B+ tree) and the deeper-book queue run

Closes the two items Run 12 left open in Next. Same machine and harness as Run 12 (Linux container, interleaved timing, `--reps 9 --warmup 3`); every candidate passed its correctness gate. Before timing, the three new trees were additionally stress-tested against the oracle at bands 1–3,000 (the book empties and refills constantly — root collapse, borrow and merge paths) across 4 seeds, plus the sorted pattern: all 26 configurations passed.

**New candidates** (`ladder/BTreeLadder.java`, `ladder/BPlusTreeLadder.java`):
- **2-3 tree** = `BTreeLadder(3)`: a B-tree of order 3 (1–2 keys, 2–3 children).
- **b-tree32** = `BTreeLadder(32)`: classic B-tree, keys + volumes in every node, ≤ 31 keys.
- **b+tree32** = `BPlusTreeLadder`: entries only in doubly linked leaves (≤ 32 keys), separator-only internals; depth walks are one descent + a leaf-chain scan, O(log P + K), instead of the binary trees' repeated ceiling/floor, O(K log P).

### 13a — Walk workload (`./run.sh ladder --n 300000 --seed {42,7} --reps 9 --warmup 3`)

| structure | median ms (seed 42 / 7) | alloc B/op |
|:---|--:|--:|
| flat-array | 11.06 / 11.21 | 1.7 |
| **b+tree32** | **28.40 / 27.58** | **1.1 / 1.2** |
| bst | 30.57 / 30.32 | 10.5 |
| radix16 | 30.94 / 31.29 | 8.6 |
| red-black | 33.12 / 33.37 | 12.6 |
| b-tree32 | 37.96 / 38.78 | 1.3 / 1.2 |
| sorted-array | 38.55 / 38.42 | 16.0 |
| avl | 44.15 / 42.71 | 10.5 |
| skip-list | 50.35 / 53.76 | 83.6 |
| oracle-treeMap | 52.84 / 50.16 | 57.8 |
| splay | 56.99 / 63.35 | 10.5 |
| 2-3-tree | 64.35 / 64.06 | 21.8 |
| unsorted-array | 121.94 / 132.96 | 16.0 |

Restructuring counters (seed 42): 2-3 tree 52,527 splits / 52,178 merges; b-tree32 666 splits / 643 merges (no rotations in either). Red-black / AVL / splay counters identical to Runs 10 and 12.

**Wide range** (`--range 16777216`, seed 42): b+tree32 26.73 ms / 1.1 B/op, b-tree32 39.06 / 1.3, 2-3 tree 64.10 / 21.8, radix16 34.97 / 8.6, red-black 34.06 / 12.6, flat-array 3,396.72 / 447.4 — the B-family trees, like radix, are unaffected by tick range.

**Sorted adversarial** (`--pattern sorted --n 12000 --band 4000`, seed 42): b+tree32 1.13 ms, b-tree32 1.51, 2-3 tree 2.31 — all in the balanced cluster (bst 43.26 ms).

### Findings vs doc 05 §1

1. **The B+ tree — the last unmeasured row of §1's table (rubric 3.60) — is the strongest tree candidate this lab has measured.** Fastest tree on the walk workload at both seeds (~9% ahead of radix16 — inside README rule 3c's tie band, so read it as "at least ties"), unaffected by tick range, balanced under sorted insertion, and **~7× less allocation per op than radix16** (1.1 vs 8.6 B/op). That last point matters: since Run 12c, §1's case for radix over red-black rests on memory and tick-range robustness. The B+ tree matches the robustness and beats radix on the memory metric this lab uses.
2. **What radix still has over the B+ tree:** no restructuring at all (fixed 16-level depth, no splits, borrows or merges — the determinism argument), and less code (236 lines vs 306 for the B+ tree, whose split/borrow/merge paths on two node kinds are exactly the edge-case surface §1's Feasibility = 2 score anticipated). Neither shows up as a speed number on these workloads.
3. **Caveat on the memory comparison:** alloc B/op is lifetime allocation churn, not resident footprint. The B+ tree allocates rarely (a new 33-slot node per split) while radix allocates a node per new path segment; resident memory per active level was not measured directly. A resident-footprint measurement is the natural next run if the team revisits §1.
4. **The order matters:** the 2-3 tree (order 3) is the slowest balanced tree (~64 ms, ~2× red-black), paying ~52k splits and ~52k merges for a shape a red-black tree encodes with ~67k cheap rotations. Wide fan-out is what makes the B-family competitive — b-tree32 does ~100× fewer restructures.
5. **B-tree vs B+ tree:** same fan-out, but b+tree32 is ~27% faster than b-tree32 here — the leaf chain turns each depth walk into one descent plus a sequential scan, where the B-tree repeats a root-to-leaf ceiling search per level. This is the "range scan" property B+ trees exist for, visible on a 12-level walk.

**Verdict status:** this is new benchmark evidence of exactly the kind doc 07 §5 says is required to re-open a pick. It is recorded here and in doc 05 §1 as an open tension for the team to decide, **not** a unilateral change of the radix pick.

### 13b — Deeper-book queue (`./run.sh queue --profile deep --n 300000 --seed {42,7} --reps 9 --warmup 3`)

Profile `deep` (`QueueWorkload` javadoc): per 1000 ops 500 enqueue / 300 dequeue / 150 cancel / 50 peek — 30% of arrivals cancelled, book grows to ~15k live orders (seed 42: 44,643 cancels, final depth 15,397).

| structure | median ms (seed 42 / 7) | vs pick | alloc B/op |
|:---|--:|--:|--:|
| oracle-linkedMap (JDK baseline) | 11.53 / 13.51 | 1.2–1.3× | 48.5 |
| array (shift-on-cancel) | 126.23 / 130.04 | **12–13×** | 1.7 |
| singly-list | 415.67 / 446.41 | 42–44× | 16.0 |
| dll-no-index | 483.14 / 481.17 | 45–51× | 20.0 |
| tree-by-id (treap) | 27.71 / 34.09 | 2.9–3.2× | 24.0 |
| **dll+hash (the pick)** | **9.54 / 10.73** | — | 20.9 |

**Findings vs doc 05 §2:** the honest surprise from Runs 6/12a — the plain array tying the pick on a shallow book — **does not survive depth**: at ~15k live orders and a 30% cancel rate the array is 12–13× slower and the un-indexed lists 42–51× slower, while the pick's time barely moves (8–10 ms → 9.5–10.7 ms). The treap's O(log n) penalty grows modestly with depth (2.2× → ~3×). The pick wins decisively in the regime a busy book actually reaches.

## Run 14 — Resident footprint: what each price ladder actually holds in memory

**Why:** every memory number before this run was `alloc B/op` — bytes *allocated* over a whole replay, divided by ops. That is churn, not footprint: a structure that allocates once and keeps it, and one that allocates and frees constantly, can score the opposite way from what each retains. Run 13a flagged that doc 05 §1's "radix has a ~32% memory edge over red-black" rests entirely on churn. This run measures **resident bytes** directly.

**Method** (`./run.sh footprint <ladder flags>`; `core/DeepSize.java`; rows in `results/footprint_results.csv`): each candidate is oracle-gated exactly as in the timed bench, then a fresh instance replays the workload and the structure *as it stands at the end* is measured two independent ways:
1. **Graph walk (deterministic):** every object reachable from the structure through instance fields, each counted once, sized with HotSpot layout rules read from the running JVM (here: 12 B object header, 16 B array header, 4 B compressed references, 8 B alignment). Includes everything the structure keeps — preallocated capacity, scratch buffers, unused child slots.
2. **Live-heap delta (cross-check):** used heap after full GCs with the structure reachable, minus after it is dropped.

The two agree to within ~1% for every node-based structure (most rows match to the byte) — the model is trustworthy. The array candidates show a larger heap delta (4.8 MB → 8.4 MB; 134.2 MB → 136.3 MB) because G1 stores objects that large in whole 4 MB "humongous" regions; that rounding is a real cost of a giant single array, recorded rather than smoothed over. Machine: same Linux container as Runs 12–13; all 65 measurements below passed the oracle gate.

**Scenarios:** the standard book (`--n 300000`, ~450 live levels at the end) at seeds 42 and 7; the same at a 256× wider tick range (`--range 16777216`, seed 42); and a **large book** (`--range 16777216 --band 100000`, ~54,000 live levels) at seeds 42 and 7.

| structure | B/level, standard book (seed 42 / 7) | B/level, large book (seed 42 / 7) | resident, large book (seed 42) |
|:---|--:|--:|--:|
| **b-tree32** | 31.3 / 30.6 | **28.5 / 28.4** | **1.54 MB** |
| **b+tree32** | **30.6** / 32.5 | 28.9 / 28.6 | 1.57 MB |
| avl | 40.1 / 40.1 | 40.0 / 40.0 | 2.17 MB |
| splay | 40.1 / 40.1 | 40.0 / 40.0 | 2.17 MB |
| bst | 40.2 / 40.2 | 40.0 / 40.0 | 2.17 MB |
| red-black | 48.2 / 48.2 | 48.0 / 48.0 | 2.60 MB |
| radix16 | 52.3 / 53.6 | 58.6 / 58.6 | 3.17 MB |
| skip-list | 57.0 / 56.9 | 56.5 / 56.5 | 3.06 MB |
| oracle-treeMap (boxed) | 88.1 / 88.1 | 88.0 / 88.0 | 4.77 MB |
| 2-3-tree | 95.7 / 97.1 | 94.8 / 94.8 | 5.14 MB |
| sorted-array / unsorted-array | 4.80 MB fixed (preallocated to n) | 88.6 | 4.80 MB (8.39 MB on the G1 heap) |
| flat-array | 0.52 MB fixed (range 65,536) | 2,478 (range 16.7M) | 134.2 MB (136.3 MB on the G1 heap) |

At the 256× wider range with the standard book, every structure's footprint is byte-identical to the narrow run **except flat-array** (0.52 MB → 134.2 MB). Range robustness, measured in resident bytes, holds for every tree and for radix.

**Findings vs doc 05 §1:**

1. **Radix's "memory edge" over red-black does not exist in resident memory — it reverses.** Radix16 holds **52–59 B per live level vs red-black's 48** — 9% more on the standard book, **22% more on the large book**. The "~32% edge" (8.6 vs 12.6 B/op) was churn: the red-black tree allocates a fresh 48 B node on every new level, while radix often re-walks path nodes that already exist. Doc 05 §1's sentence "radix keeps the ~32% memory edge" is withdrawn.
2. **Where radix's bytes go:** a leaf is a 32 B node; every branching interior node adds a 16-slot child array (80 B with compressed references; 144 B with uncompressed 8-byte references, close to docs 02/03's "128 B/node" estimate). The ~80k objects for ~54k levels are those interior path nodes. Sparse ticks spread across a wide band need more of them, which is why radix's per-level cost *grows* from the standard to the large book (52 → 59 B) while every comparison tree stays flat.
3. **The B-family trees are the leanest structures in the lab:** ~29 B/level, about **half of radix** and 40% below red-black. Each node packs up to 32 entries into two primitive arrays, so per-object overhead is paid once per ~20 entries. This confirms Run 13a's churn signal with the right metric: the B+ tree beats radix on resident memory by ~2×.
4. **The 2-3 tree is the heaviest balanced tree** (~95 B/level): same node layout as the order-32 B-tree, but only 1–2 entries per node, so per-node overhead dominates. Fan-out is what makes the B-family lean.
5. **Doc 05 §1's other claims, re-checked:** "memory scales with active price levels, not tick range" holds for radix (and for every tree). The flat array's range-proportional footprint is confirmed in resident bytes (134 MB to hold ~450 levels). The boxed `TreeMap` costs 88 B/level, 1.8× the hand-rolled red-black tree: the boxing caveat in Runs 1–2, quantified.

**Verdict status:** after Runs 12c, 13a and 14, the Radix Tree pick has no remaining measured advantage over the B+ tree on speed (tie), tick-range robustness (tie), or memory (B+ ~2× leaner), and it uses ~9–22% more memory than red-black. Its remaining case is qualitative: zero restructuring (fixed 16-level depth; deterministic, input-order-independent) and less code than the B+ tree. Recorded in doc 05 §1 and doc 07 for the team's decision, not resolved here.

## Run 15 — Final run on the finished code (Stage 7b)

**Why:** the closing measurement of the project. It re-runs the whole lab and the production parity suite on the final code, on one machine, in one sitting, so that the numbers quoted in the README, the demo script and the final report all come from a single committed run.

**Machine and method:** Linux container, Intel Xeon @ 2.10 GHz, 4 cores visible, 15.7 GB, OpenJDK 21.0.12 ([`documentation/results/environment.txt`](../../documentation/results/environment.txt)). Interleaved timing, `--reps 7 --warmup 3`, seed 42. Every candidate passed its correctness gate before it was timed. The full console output is in [`documentation/results/benchmark_run15.txt`](../../documentation/results/benchmark_run15.txt), and the rows were appended to `raw_results.csv` and `footprint_results.csv`. Nothing in `benchmark/src` changed after Run 14.

### 15a — Price ladder

| structure | walk, n=300000 (ms) | alloc B/op | sorted adversarial, n=12000 (ms) | resident B/level, large book |
|:---|--:|--:|--:|--:|
| flat-array | 10.34 | 1.7 | 3.93 | 2,477.8 |
| **b+tree32 (production)** | **24.50** | **1.1** | **0.78** | **28.9** |
| sorted-array | 27.66 | 16.0 | 1.00 | 88.6 |
| red-black | 29.00 | 12.6 | 0.92 | 48.0 |
| bst | 30.54 | 10.5 | **39.87** | 40.0 |
| radix16 | 32.28 (spread 280%) | 8.6 | 0.98 | 58.6 |
| b-tree32 | 34.53 | 1.3 | 0.91 | 28.5 |
| avl | 38.57 | 10.5 | 1.08 | 40.0 |
| skip-list | 47.02 | 83.6 | 1.61 | 56.5 |
| oracle-treeMap | 50.79 | 57.8 | 1.36 | 88.0 |
| splay | 55.10 | 10.5 | 0.92 | 40.0 |
| 2-3-tree | 57.74 | 21.8 | 1.67 | 94.8 |
| unsorted-array | 116.40 | 16.0 | 7.36 | 88.6 |

Rotation and split counters are identical to Runs 10–13. The resident footprint (`footprint --n 300000 --seed 42 --range 16777216 --band 100000`, 54,169 live levels) is byte-identical to Run 14, as a deterministic graph walk should be: the B+ tree uses 1,566,008 B (28.9 B/level), radix16 3,172,240 B (58.6), red-black 2,600,208 B (48.0) and flat-array 134,217,784 B.

**Findings.**
* The B+ tree is again the fastest tree on the walk workload, as in Run 13a. It is 15% ahead of red-black (24.50 vs 29.00 ms), and its sorted-insertion time is the lowest of every candidate.
* Radix16's 32.28 ms carries a 280% spread, so that single figure is noise; Run 12c's radix ≈ red-black tie stands.
* The unbalanced BST is again the outlier under sorted insertion: 39.87 ms against at most 1.67 ms for every balanced candidate, about 24× slower (Run 11: 31× or more).

### 15b — Order queue

| structure | shallow (ms) | deep (ms) | deep vs pick | alloc B/op (deep) |
|:---|--:|--:|--:|--:|
| oracle-linkedMap | 7.51 | 9.97 | 1.0× | 48.5 |
| array | 7.79 | 100.71 | 9.8× | 1.7 |
| singly-list | 29.33 | 488.86 | 48× | 16.0 |
| dll-no-index | 29.77 | 551.23 | 54× | 20.0 |
| tree-by-id (treap) | 22.54 | 29.94 | 2.9× | 24.0 |
| **dll+hash (the pick)** | **8.17** | **10.24** | — | 20.9 |

Run 13b reproduces. On the shallow book (3,125 live orders) the plain array ties the pick (7.79 vs 8.17 ms). On the deep book (15,397 live orders, 44,643 cancels) it is 9.8× slower, and the un-indexed lists are about 50× slower, while the pick moves only from 8.17 to 10.24 ms.

### 15c — Account cache, ticker, compression, arbitrage

- **Cache** (Zipf 1.0, capacity 10,000):
  - The pick, hash+LRU, takes 10.86 ms.
  - The unbounded hash maps are faster, at 6.40 (open addressing) and 6.53 (chaining). This is the price of bounding memory that Run 7 recorded: they end holding 47,907 entries against the pick's 10,000.
  - Splay takes 65.30 ms, 6.0× the pick.
  - The JDK `LinkedHashMap` LRU takes 15.84 ms; the pick is 1.46× faster than it.
- **Ticker:**
  - Exact match: hash-set 6.00 ms, trie 8.36 (1.39× slower than the hash set), TST 15.97, sorted-array 21.12.
  - Prefix: trie 28.84 ms, sorted-array 36.73, TST 39.14. The hash set cannot answer prefix queries.
  - Run 12d's corrected reading reproduces: the trie loses exact match to a hash set and wins the prefix workload it exists for, by 1.27–1.36×.
- **Compression** (5,505,087 B, every candidate round-trips byte for byte):
  - Ratios are identical to Run 9: huffman 0.5874, lzw 0.2962, arithmetic 0.5842, rle 1.0240.
  - Huffman encodes at 91.1 MB/s and decodes at 81.0 MB/s on this machine. Run 9 measured 186.3 and 177.6 MB/s on an Apple M4, so only the speeds depend on the machine.
- **Arbitrage, real data:**
  - The archived Frankfurter snapshot has V=8 and E=56, and the independent brute-force oracle finds no negative cycle.
  - Per scan: Bellman-Ford 0.0006 ms, Johnson 0.0006, SPFA 0.0006, Tarjan+BF 0.0015, Floyd-Warshall 0.0018.
- **Arbitrage, synthetic** (V=8 with an injected cycle): Bellman-Ford 0.0006 ms, Johnson 0.0006, SPFA 0.0013, Floyd-Warshall 0.0019, Tarjan+BF 0.0020.

### 15d — Production parity (the finished backend)

`backend/test.sh` puts each production structure behind a thin adapter and runs it through the benchmark's own correctness gate. All six structures pass, including the ladder under the walk, wide-range, sorted and band-1 to band-3 stress workloads: 207 tests passed, 0 failed ([`parity_gates.txt`](../../documentation/results/parity_gates.txt)).

`backend/test.sh --timing` puts production and benchmark side by side in one JVM ([`parity_timing.txt`](../../documentation/results/parity_timing.txt)):
- Ladder walk: production 25.30 ms, benchmark 24.43 ms (+3.6%).
- Queue, deep profile: production 11.91 ms, benchmark 8.76 ms (+35.9%). This is the only delta above doc 08's 25% line. The production queue node is the full `Order`, carrying the level pointer and the index link that cancel and settlement need; it allocates 40.9 against 20.9 B/op. The explanation is in doc 03 §8.
- Ticker: production 20–31% *faster* than the benchmark.
- Every other delta is between −5% and +22%.

The production scan's own cost, with the threshold-shifted passes and the exact triangle check that the benchmark's plain Bellman-Ford does not have, is in [`scan_timing.txt`](../../documentation/results/scan_timing.txt). Per scan: 0.86–4.30 µs at V=7, 3.16–24.98 µs at V=12, and 11.61–173.22 µs at V=20.

### What changes in the verdicts

None. All six picks reproduce on the finished code, and so do the recorded tensions: the flat array's narrow-range speed, the plain array's shallow-book tie, and LZW's 2× better ratio. The ladder pick, changed from radix to the B+ tree after Runs 12–14 (doc 08 §3a), is confirmed: the B+ tree is the fastest and leanest balanced ladder here.
