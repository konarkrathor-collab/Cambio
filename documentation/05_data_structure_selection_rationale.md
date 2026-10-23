# Data Structure Selection Rationale — Comparative Market Survey

This document exists to answer one question for the faculty panel: **"Why this data structure, and not any of the others available?"** For every subsystem in the project, we surveyed every realistic candidate data structure/algorithm used for that exact problem class — in academia and in production trading/compression systems — scored them against a fixed rubric, and recorded why the losing candidates lost. Nothing here is a default; every choice is a decision.

---

## Scoring Rubric

Each candidate is scored **1 (poor) – 5 (excellent)** on five weighted criteria:

| Criterion | Weight | What it measures |
| :--- | :-: | :--- |
| **Operation Fit** | 35% | How well the structure's native complexity matches *this specific* access pattern (not its complexity in general) |
| **Space Efficiency** | 15% | Memory overhead relative to the data actually held |
| **Implementation Feasibility** | 20% | Can a 3-person team build and correctness-test it from scratch in a semester, without bugs hiding in edge cases |
| **Industry Precedent** | 15% | Is this the structure real exchanges / compressors / routers actually use for this exact problem |
| **Determinism** | 15% | Worst-case behavior is predictable (critical for a latency-sensitive matching engine), not just good on average |

**Weighted Score** = Σ(criterion score × weight). Final pick per subsystem is the highest weighted score **for this use case**, not the "best" data structure in the abstract.

---

## 1. Price Ladder — Order Book Price-Level Index

**Access pattern:** insert/delete a price level, peek best bid/ask (min/max), occasionally walk depth outward from the best price. Prices are bounded, fixed-point values (ticks), not arbitrary reals.

| Candidate | Op. Fit | Space | Feasibility | Precedent | Determinism | **Weighted** |
| :--- | :-: | :-: | :-: | :-: | :-: | :-: |
| Unsorted Array | 1 | 4 | 5 | 1 | 2 | **2.40** |
| Sorted Array (binary search) | 2 | 5 | 4 | 2 | 3 | **3.00** |
| Unbalanced BST | 2 | 3 | 3 | 1 | 1 | **2.05** |
| AVL Tree | 4 | 3 | 2 | 3 | 5 | **3.45** |
| B+ Tree | 4 | 3 | 2 | 4 | 5 | **3.60** |
| Skip List | 4 | 3 | 4 | 4 | 2 | **3.55** |
| Direct-Indexed Flat Array (per tick) | 5 | 1 | 5 | 3 | 5 | **4.10** |
| Red-Black Tree *(previous pick)* | 4 | 3 | 3 | 5 | 5 | **3.95** |
| **Radix Tree, 16-ary nibble-bucketed on tick** *(final pick, revised)* | **5** | **3** | **4** | **2** | **5** | **4.05** |

> **Correction note:** the scores above were re-derived from the stated rubric after an external review found the original weighted sums did not match the formula on roughly every row in this document, and that the original Radix Tree row (Space = 4, Precedent = 4) was not defensible. Both are revised below with the reasoning shown, not just the number.

**Why the Radix Tree's own scores changed:**
- **Space dropped 4 → 3.** The architecture doc originally specified a 256-ary node (one branch per *byte* of the tick), which costs `256 × 8 bytes ≈ 2 KB` per node on a 64-bit system — a real and significant memory cost per path, not a rounding error. The [Adaptive Radix Tree paper (Leis et al., ICDE 2013)](https://www.inf.ufpr.br/eduardo/ensino/ci809/papers/leis-icde2013.pdf) exists specifically because naive wide-fanout radix nodes waste space when most children are null, which is exactly our situation (most price ticks are unpopulated). We have **revised the design to a 16-ary (nibble) radix base** — see §1 of [02_system_architecture_and_design.md](./02_system_architecture_and_design.md) — which bounds each node to `16 × 8 bytes = 128 bytes`, a real mitigation, not a dismissal of the problem. It is still larger per-node than a Red-Black Tree's compact ~56-byte node, so Space = 3 (better than naive, not free).
- **Precedent dropped 4 → 2.** We could not produce a citation of a real production order book using a radix tree specifically for price-level indexing — Redis and LevelDB's skip lists (cited elsewhere in this document) are *database* structures, not exchange order books, and do not establish precedent here. [Liquibook](https://github.com/enewhuis/liquibook/blob/master/README_ORDER_ENTRY.md) confirms the industry does represent prices as integer ticks, which supports the *key design*, but not specifically the radix-tree indexing structure built on top of it. We are keeping this score low rather than inventing a citation.
- **Op. Fit stays 5, Determinism stays 5.** Fixed-depth digit decomposition is genuinely input-order-independent (no rotations, no rebalancing), and a populated-child **bitmap** per node (added to the struct in §1 of the architecture doc) makes best-bid/best-ask peek a fixed number of bitmap scans rather than a 256-way linear check. Walking *K* price levels of depth, however, is **O(K)** like any ordered structure — the fixed-depth property only applies to locating a *single* key, not to enumerating a range. The earlier version of this document did not make that distinction clearly.

**Why the alternatives lost:**
- **Arrays (sorted/unsorted):** O(N) shift cost on insert/delete at an arbitrary position makes both unsuitable regardless of how cheap a single lookup is.
- **Direct-Indexed Flat Array:** scores highest of all on raw arithmetic (4.10) — true O(1) indexing, trivial to implement, fully deterministic. Its Space = 1 reflects a real failure mode, not a cosmetic penalty: a single wide-range instrument (e.g. a BTC/USD book spanning $1–$150,000 at cent resolution is 15 million ticks) forces an array sized to that full range even though only a few hundred price levels are ever populated at once. This is discussed further in the verdict below.
- **Unbalanced BST:** degenerates toward O(P) under sorted or monotonic order arrival. We keep the general claim that this is a real worst case; we no longer assert a specific claim about how often real markets trend, since we have no data to back that frequency claim.
- **AVL Tree:** stricter rebalancing than Red-Black means more rotations per structural mutation. (Note: the tree only mutates when a price level is *created* or *emptied* — an order joining an already-populated level only touches that level's order queue, not the tree. We overstated this in an earlier version of this document.)
- **B+ Tree:** traditionally associated with disk-block amortization, but cache-optimized in-memory B+-tree variants are an active, legitimate research area (see e.g. [DAMON 2023 work on cache-optimized in-memory indexes](https://dbis.cs.tu-dortmund.de/storages/dbis-cs/r/papers/2023/cache-optimization/cache-optimization-damon-2023-submission.pdf)), so we no longer claim it's "only" a disk structure. It still isn't chosen here because its extra fan-out/key-count bookkeeping solves a cache-line-amortization problem we don't have at this project's scale, when a simpler fixed-width digit decomposition already gives us comparison-free routing.
- **Red-Black Tree:** still a fully legitimate, industry-proven answer — O(log P) worst-case, no amortization tricks, and (now, at 3.95) numerically close enough to the Radix Tree (4.05) that this is a narrow call, not a dominant win.

**Verdict — a closer call than originally presented.** Once corrected, Radix Tree (4.05), Direct-Indexed Array (4.10), and Red-Black Tree (3.95) are within rubric noise of each other. We are overriding the raw 0.05 numeric edge that Direct-Indexed Array holds, for a reason the single Space score can't capture on its own: Direct-Indexed Array's O(1) guarantee depends entirely on the tick range staying small, and it degrades catastrophically (megabytes → gigabytes) rather than gracefully if one instrument is configured with a wide range, whereas the Radix Tree's memory scales with the number of *active* price levels (empty branches are pruned), regardless of how wide the tick range is. For a multi-instrument exchange where tick ranges vary by orders of magnitude between pairs, that worst-case robustness is worth more to us than a statistically-insignificant scoring edge. A single-instrument system with a known, narrow tick range would be better served by the Direct-Indexed Array.

### Benchmark Evidence (measured — see `benchmark/results/RESULTS.md` Runs 1–2, 10–11 and 12c)

The rubric table above was the design-stage prediction. We then actually built all candidates from scratch (fairness-gated against a correctness oracle, median-of-7 timing, per-operation allocation counted via the JVM's thread-allocation counters — methodology in `benchmark/README.md`) and ran them on a seeded 300,000-operation workload, twice: once at the original 65,536-tick range, once with the range widened 256× to 16,777,216 ticks (the exact "wide-range, multi-instrument" scenario this section's robustness argument rests on). `TreeMap` (Java's own red-black tree) stands in directly for the Red-Black Tree candidate, not as a rough baseline.

| structure | narrow-range median | wide-range median | wide-range alloc B/op |
| :--- | --: | --: | --: |
| flat-array | **6.24 ms** (fastest) | 659.81 ms (**105× slower**) | 447.4 (**263× more memory**) |
| sorted-array | 21.18 ms | 21.16 ms (unchanged) | 16.0 (unchanged) |
| radix16 | 22.28 ms | 22.21 ms (unchanged) | 8.6 (unchanged) |
| oracle-treeMap (Red-Black Tree) | 25.22 ms | 24.64 ms (unchanged) | 57.8 (unchanged) |
| skip-list | 34.19 ms | 34.04 ms (unchanged) | 83.6 (unchanged) |
| unsorted-array | 74.66 ms | 74.49 ms (unchanged) | 16.0 (unchanged) |

**This empirically confirms the verdict above, not just the argument for it.** The "closer call than originally presented" was real at narrow range — flat-array's raw 6.24 ms genuinely beats radix16's 22.28 ms there, exactly as the 4.10-vs-4.05 rubric scores predicted. But the robustness override is no longer just reasoning — it's measured: widening the tick range 256× left every other structure's cost *unchanged* (memory scales with active price levels, confirming "empty branches are pruned" is real, not aspirational) while the flat array alone collapsed, 105× slower and consuming 263× more memory per operation. Separately, against the **boxed `TreeMap` stand-in**, Radix Tree initially appeared to win on both axes (22.2 ms vs 24.6–25.2 ms; 8.6 vs 57.8 bytes per operation) — but that comparison has since been settled by measurement, and the settlement corrects it — recorded immediately below. **The stand-in caveat, settled (Runs 10 and 12c).** Ladder v2 built the hand-rolled red-black tree with primitive `long` keys and no boxing. Run 10 (one machine, sequential timing) had it ~12% faster than radix (19.3 vs 21.6 ms); that **did not reproduce**: under interleaved timing at three seeds × two runs on a second machine (Run 12c) the two **tie** (means 34.6 ms radix vs 34.9 ms red-black, each winning 3 of 6 head-to-heads). What reproduces exactly, on every seed and machine, is radix's ~32% memory edge (8.6 vs 12.6 B/op) and the tick-range robustness of Run 2 (re-confirmed in Run 12c: flat-array 3,668 ms at wide range, radix unchanged). The verdict therefore rests on memory, robustness, and deterministic fixed-depth behavior — walk-workload speed is a tie between the two. The rubric's 4.05-vs-3.95 framing was a close call, and the measurements confirm exactly that: a trade, not a dominance.

**The monotonic-order bullet, now with data (Run 11).** §1 claimed "Unbalanced BST degenerates toward O(P) under sorted or monotonic order arrival" while explicitly flagging it as having no data. The new `sorted` adversarial workload supplies it: on the same 12,000-op log every balanced candidate finishes in ≤ 0.88 ms while the plain BST takes 25.6 ms — ~36× slower (re-run in Run 12c: 44.7 ms vs ≤ 1.43 ms, 31×+) — on a structure that ties the fastest trees under the ordinary walk workload. Same code, different insertion order: the cost of not balancing, quantified. The rotation counters from both runs also test the AVL-vs-Red-Black bullet: on the walk workload AVL performs 18% more rotations in aggregate (78,895 vs 66,857), driven by inserts (+32%: 50,057 vs 37,930) with deletes statistically equal (28,838 vs 28,927); on the sorted workload the two converge entirely (7,635 vs 7,806). The textbook "AVL pays more rotations" shows up as a modest insert-side premium on these workloads, not a categorical difference — recorded as measured.

**What's still open:** Direct-Indexed Array wasn't tested at a range as extreme as a real wide-swinging instrument (e.g. BTC/USD) might need, so its narrow-range speed advantage (6.24 ms) is confirmed but its failure mode is confirmed too — this run doesn't pin down the exact range threshold where Radix Tree starts winning outright, only that it exists and is crossed well before 16.7M ticks. The B+ Tree remains the one candidate from the table above without a hand-rolled implementation (Ladder v2 delivered Unbalanced BST, AVL, Red-Black, and Splay; 2-3 and B-tree stay syllabus-coverage items) — flagged open in `RESULTS.md`'s Next list, not checked off. Full methodology and raw CSV are in `benchmark/results/RESULTS.md`.

---

## 2. Order Queue Per Price Level (FIFO Execution + Arbitrary Cancellation)

**Access pattern:** append to tail (new order), pop from head (execute), delete an arbitrary order by ID (cancel) — all with no scanning allowed.

| Candidate | Op. Fit | Space | Feasibility | Precedent | Determinism | **Weighted** |
| :--- | :-: | :-: | :-: | :-: | :-: | :-: |
| Array / Dynamic Array | 2 | 4 | 5 | 1 | 2 | **2.75** |
| Singly Linked List | 2 | 4 | 4 | 1 | 4 | **2.85** |
| Doubly Linked List (no index) | 4 | 4 | 5 | 3 | 5 | **4.20** |
| Balanced Tree keyed by orderId | 3 | 3 | 2 | 2 | 5 | **2.95** |
| **Doubly Linked List + Hash Map** *(unchanged final pick)* | **5** | **4** | **4** | **5** | **5** | **4.65** |

**Why the alternatives lost:** arrays require shifting elements on arbitrary-position delete (O(N)); a plain DLL without an index map requires an O(N) scan to *find* the node before you can unlink it in O(1) — the unlink is cheap, but locating it isn't; a balanced tree keyed by orderId gives O(log N) everywhere, strictly worse than O(1) for no benefit since we never need orders sorted by ID.

**Winner: Doubly Linked List + Hash Map (unchanged).** This is the one subsystem where no alternative even comes close — the combination is the textbook-optimal answer (it's literally how LRU caches and real matching engines solve this), so we did not change it.

### Benchmark Evidence (measured — see `benchmark/results/RESULTS.md` Runs 6 and 12a)

All five candidates were implemented from scratch behind a frozen `QueueOps` contract and replayed the same seeded 300,000-op workload (enqueue / dequeue-head / cancel-by-id / peek — mix and justification in `QueueWorkload`; methodology in `benchmark/README.md`), oracle-gated against a `LinkedHashMap` reference before any timing. Numbers below are Run 12a (interleaved timing, seeds 42 and 7, Linux container); Run 6's original table carried a treap handicap, corrected below.

| structure | median ms (seed 42 / 7) | vs pick | alloc B/op |
| :--- | --: | --: | --: |
| oracle (LinkedHashMap baseline) | 10.00 / 9.17 | ~1.0–1.1× | 45.2 |
| array (shift-on-cancel) | 11.40 / 9.78 | 1.0–1.3× | 0.4 |
| singly list | 28.10 / 23.88 | 2.5–3.1× | 16.0 |
| DLL (no index) | 28.41 / 26.08 | 2.7–3.2× | 20.0 |
| balanced tree by orderId (treap) | 20.24 / 20.06 | 2.1–2.3× | 24.0 |
| **DLL + Hash Map** | **8.97 / 9.59** | — | 20.2 |

**Findings:** the verdict survives measurement, and the hash index's role is visible in numbers — the pick is ~3× faster than the plain DLL (the O(n) cancel scan is the entire gap) and ~2.2× faster than the treap. **Correction:** Run 6 first reported the treap 105× slower, attributing it to "id order ≠ arrival order". That was a benchmark error — ids are assigned in arrival order (as an exchange sequencer does), so the FIFO head is the smallest id, an O(log n) leftmost walk; the first implementation scanned the whole tree instead. Fixed, the design-stage bullet above ("O(log N) everywhere, strictly worse than O(1)") is confirmed as written. Also stated plainly: the value of the hash index shrinks as the resting book gets shallow — at this workload's ~3k live orders the plain array is within noise of the pick (1.0–1.3×), so its O(n) shift cost becomes decisive only as depth and cancel-rate grow (a deeper-book adversarial run is queued in `RESULTS.md`'s Next list, not tuned into this one).

---

## 3. Currency Loophole Detection (Negative Cycle in a Weighted Directed Graph)

**Access pattern:** given ~10–50 currency vertices and a near-complete edge set (every pair quotes a rate), find whether a negative-weight cycle exists and reconstruct it, on every rate update.

| Candidate | Op. Fit | Space | Feasibility | Precedent | Determinism | **Weighted** |
| :--- | :-: | :-: | :-: | :-: | :-: | :-: |
| Dijkstra (any variant) | 1 | 4 | 5 | 1 | 5 | **2.85** |
| Floyd-Warshall | 3 | 2 | 4 | 3 | 5 | **3.35** |
| Tarjan's SCC + per-cycle weight sum | 2 | 3 | 2 | 2 | 4 | **2.45** |
| Johnson's Algorithm | 3 | 3 | 2 | 3 | 5 | **3.10** |
| SPFA (queue-optimized Bellman-Ford) | 5 | 4 | 4 | 4 | 4 | **4.35** |
| **Bellman-Ford** *(final pick)* | **5** | **4** | **5** | **5** | **5** | **4.85** |

**Why the alternatives lost:**
- **Dijkstra:** mathematically disqualified, not just suboptimal — it assumes non-negative edge weights, and our entire detection trick (`w = -ln(R)`) *depends on* negative weights existing. Cannot be used at all.
- **Floyd-Warshall:** correctly finds negative cycles (checking the diagonal after all-pairs relaxation) and is simple to implement, but runs in O(V³) — for a near-complete graph where E ≈ V², that's effectively worse than Bellman-Ford's O(V·E) for our density. It's the right answer only when you need *all-pairs* shortest paths simultaneously, which we don't.
- **Tarjan's SCC:** finds that a cycle exists structurally, but doesn't natively carry edge weights through the detection — we'd need a separate weighted-cycle-sum pass per candidate cycle on top of it, adding complexity for no complexity-class benefit.
- **Johnson's Algorithm:** essentially "Bellman-Ford once, then Dijkstra from every vertex" to get all-pairs shortest paths efficiently on sparse graphs — built for a problem (all-pairs) we don't have, since we only need to know *whether a cycle exists*, from any vertex.
- **SPFA:** a legitimate, widely-used competitive-programming optimization — identical worst-case O(V·E) bound and the same underlying correctness proof as Bellman-Ford, since it's Bellman-Ford with an early-exit queue. Its appeal is that it typically does far fewer relaxations on average, because only vertices whose distance just improved get re-queued. We scored it lower on three criteria that matter more for *this* system than raw average-case speed: **Feasibility** (correct queue/visit-count bookkeeping and re-entry tracking is more edge-case-prone to implement and test than a flat double loop), **Precedent** (Bellman-Ford is the overwhelmingly standard reference in arbitrage-detection literature; SPFA is mostly a competitive-programming heuristic, and is even known in that community to degrade to Bellman-Ford's worst case — with *worse* constant-factor overhead from queue operations — on specifically constructed adversarial graphs), and **Determinism** (Bellman-Ford's runtime is input-order-independent and fixed at exactly $|V|-1$ passes; SPFA's runtime varies with queue dynamics, which is the wrong trade for a system whose selling point is predictable latency).

**Winner: Bellman-Ford.** On a graph this small (10–50 currencies), the absolute time difference between the two is negligible — both complete in microseconds — so SPFA's average-case speed advantage buys us nothing material, while Bellman-Ford's fixed iteration count, simpler implementation, and textbook-standard status directly serve this project's goals: provable correctness, deterministic latency, and alignment with how arbitrage detection is actually taught and cited. This is the one subsystem in this survey where the theoretically "newer" optimization does not win once implementation risk and determinism are weighted honestly.

### Benchmark Evidence (measured — see `benchmark/results/RESULTS.md` Runs 3–5 and 12b)

All five candidates were implemented from scratch behind a frozen `GraphOps` contract and run against **real data**: a live snapshot pulled from the Frankfurter API (`benchmark/data/frankfurter_usd_2026-10-09.json`) — an API doc 06 now keeps as its *fallback* source (its primary is a live Finnhub WebSocket; see doc 06's revision note) — plus synthetic graphs at project scale (V=20, V=50). Correctness was verified against an independent brute-force cycle oracle (exhaustive enumeration — a different algorithm family entirely) wherever that's computationally feasible (V≤10); at larger V it's cross-checked against Bellman-Ford's own verdict, always stated explicitly rather than implied.

**Finding 1 — today's real currency rates have no arbitrage.** All five algorithms and the independent oracle agree: zero negative cycles among 8 real currencies as of 2026-10-09. This isn't a null result — it's the pipeline (real API → graph → 5 independent detectors → oracle) validated end-to-end on genuine, unmodified, cited data, and it's exactly what doc 06 predicted (real arbitrage gets closed in microseconds, long before a daily snapshot could see it).

**Finding 2 — the scaling numbers, re-measured fairly (Run 12b).** The first scaling run (Run 5) timed candidates one after another, one microsecond scan per rep after 3 warmup scans, with cycle reconstruction inside the timed region. Its tell was Johnson — identical code to Bellman-Ford — timing 2× faster purely from running later. Run 12b re-measured per scan with batched reps, ≥ 2,000 warmup scans per candidate, interleaved round-robin, reconstruction untimed (Linux container, so absolute numbers differ from Run 5's M4):

| structure | V=20, no cycle | V=50, injected cycle |
| :--- | --: | --: |
| spfa | **3.4 µs** (fastest here) | 216.7 µs |
| bellman-ford | 9.1 µs | 160.7 µs |
| johnson (BF delegate) | 8.6 µs | **145.3 µs** |
| floyd-warshall | 13.0 µs | 160.7 µs |
| tarjan+bf | 16.3 µs | 284.0 µs |

- **Johnson now tracks Bellman-Ford within run noise at every scale**, exactly as its implementation (a direct delegate) guarantees — confirming the rubric's "essentially Bellman-Ford once" claim, and confirming the harness fix.
- **Tarjan+BF is consistently the slowest** (1.8× Bellman-Ford at both scales) — confirms the "SCC-finding is pure overhead on a single-SCC currency graph" rejection: the complete graph is one strongly-connected component, so Tarjan's partitioning buys nothing.
- **Floyd-Warshall: rejection stands, but not because it is slowest everywhere.** It is 1.4× Bellman-Ford at V=20 yet **ties** it at V=50 (its O(V³) inner loop is branch-free and cache-friendly at this size). Run 5's "slowest at both scales, 2–4×" did not survive fair timing. The rejection rests on this section's stated grounds — all-pairs work and O(V²) memory for a single-source question — not on raw speed at 50 currencies.
- **SPFA's result is the determinism argument made visible.** With no cycle (V=20) it is 2.7× faster than Bellman-Ford — the early-exit advantage, where expected. With a cycle (V=50) it is 1.35× *slower*, because a negative cycle keeps re-queuing vertices until the visit-count trigger fires. Its cost depends on the input's shape; Bellman-Ford's fixed pass count does not.
- **Latency targets:** a warm scan of the real 8-currency graph takes ~0.6 µs; V=20 takes 9 µs (doc 04's "< 100 µs at 20 currencies" holds with ~10× headroom). Doc 03's "< 50 µs for 10–50 currencies" holds at V=20 but **not** at V=50 with a cycle present (161 µs).

**A process note worth keeping in the report:** building this benchmark surfaced two real bugs — a generator bug that accidentally created a free 2-currency arbitrage as a side effect of cycle injection (the exact failure mode doc 06 already flagged needing a bid/ask spread to prevent), and a Floyd-Warshall cycle-reconstruction bug where, in a complete graph, chasing the path pointer back to the starting vertex could wander into an unrelated embedded cycle forever. Both are documented with the fix in `RESULTS.md` rather than silently corrected — the failed test run that caught the second bug is still in the raw CSV.

---

## 4. Active Trader / Margin Account Cache

**Access pattern:** point lookup and update by `traderId` on every order submission — no range queries, no "give me accounts between X and Y" requirement. A small number of high-frequency accounts dominate traffic (Zipfian access).

| Candidate | Op. Fit | Space | Feasibility | Precedent | Determinism | **Weighted** |
| :--- | :-: | :-: | :-: | :-: | :-: | :-: |
| Unsorted Array / Linked List | 1 | 4 | 5 | 1 | 2 | **2.40** |
| AVL / Red-Black Tree keyed by traderId | 3 | 3 | 2 | 3 | 5 | **3.10** |
| Splay Tree *(previous pick)* | 4 | 3 | 2 | 3 | 2 | **3.00** |
| Plain Hash Map (no eviction) | 4 | 2 | 5 | 4 | 4 | **3.90** |
| **Hash Map + LRU Cache** *(final pick)* | **5** | **4** | **4** | **5** | **5** | **4.65** |

**Why the alternatives lost:**
- **Trees (AVL/Red-Black) keyed by traderId:** give O(log N), strictly worse than hashing's O(1) average, and we never need accounts in sorted traderId order — paying for ordering we don't use.
- **Splay Tree:** genuinely clever for skewed access (the exact Zipfian pattern we have), and it's a legitimate textbook answer here — but it only optimizes *average* access via amortization; a single cold lookup can still walk O(N) and perform a full rotation chain, which is worse determinism than a hash map's flat O(1) average, and it has no notion of bounded memory (it never evicts, so the cache grows forever as new traders appear).
- **Plain Hash Map:** matches our access pattern perfectly (O(1) average) but has no eviction policy, so memory grows unboundedly as the trader population grows over a trading session — a real operational problem, not just a theoretical one.

**Winner: Hash Map + LRU Cache.** The access pattern is pure point-lookup, which hashing already solves optimally; the only gap a plain hash map has — unbounded memory growth — is exactly what the LRU eviction list closes, at O(1) additional cost per access. This beats the Splay Tree specifically *because* our requirement is "bounded hot-cache," not "self-organizing sorted structure" — we don't need the ordering property a tree gives us, so paying its rotation cost is pure waste here.

### Benchmark Evidence (measured — see `benchmark/results/RESULTS.md` Run 7)

All four candidate classes were implemented from scratch behind a frozen `CacheOps` contract and replayed on one seeded **Zipfian** access stream (300,000 accesses over a 100,000-trader universe, exponent s = 1.0, cache capacity 10,000 — the top 10% of accounts received 81.1% of accesses; a uniform stream would not have tested this section's actual claim). Correctness was verified in two parts: balance correctness by replay checksum for every candidate, and **residency exactly** — the LRU pick was replayed in lockstep against an access-order `LinkedHashMap` reference with full (all-id) residency equality at three mid-stream checkpoints plus the end.

| structure | median ms | M ops/sec | alloc B/op | resident at end |
| :--- | --: | --: | --: | --: |
| oracle (LinkedHashMap baseline) | 7.43 | 40.4 | 37.4 | 10,000 |
| hash chaining (no eviction) | 3.30 | 90.8 | 6.9 | 47,907 |
| open addressing (no eviction) | 2.84 | 105.8 | 14.0 | 47,907 |
| **Hash Map + LRU** | **6.10** | **49.2** | 11.2 | **10,000** |
| splay tree (no eviction) | 36.77 | 8.2 | 6.4 | 47,907 |

**Findings:** the verdict survives, with the trade precisely quantified. The pick is *not* the raw-speed winner — the plain hashes are 1.8–2.1× faster because they skip all recency bookkeeping — but the pick holds steady state at exactly capacity while the no-eviction candidates grow to 47,907 residents (4.8×): precisely the "unbounded memory — a real operational problem" trade this section rejected the plain hash map for, now in numbers. The splay tree was rejected *under its dream workload*: at 81% top-decile skew it is still 6× slower than the pick and 12.9× slower than open addressing, amortized hot-node theory notwithstanding. The hand-rolled pick also beats the JDK baseline on both axes (1.2× faster, 3.3× less allocation churn — no boxing). One nuance the table alone would misread: alloc B/op is lifetime churn, not steady state — the unbounded maps allocate each account once, while the LRU re-allocates evicted accounts when they return; the real memory win is the resident bound stated above.

---

## 5. Currency Ticker / Pair Lookup & Autocomplete

**Access pattern:** validate a ticker exists (`USD/EUR`), and autocomplete partial input (`US` → `USD/EUR`, `USD/JPY`, ...) over a tiny, fixed alphabet (`A`–`Z`, `/`) and a small dataset (tens to low hundreds of pairs).

| Candidate | Op. Fit | Space | Feasibility | Precedent | Determinism | **Weighted** |
| :--- | :-: | :-: | :-: | :-: | :-: | :-: |
| Hash Set (exact match only) | 2 | 5 | 5 | 3 | 5 | **3.65** |
| Sorted Array + Binary Search + range scan | 3 | 5 | 4 | 2 | 5 | **3.65** |
| Ternary Search Tree | 4 | 4 | 3 | 3 | 4 | **3.65** |
| Suffix Tree / DAWG | 4 | 2 | 1 | 2 | 4 | **2.80** |
| **Trie (Prefix Tree)** *(unchanged final pick)* | **5** | **3** | **5** | **5** | **5** | **4.70** |

**Why the alternatives lost:** a Hash Set gives O(1) exact match but *cannot* do prefix queries at all — autocomplete is simply impossible with it. Sorted array + binary search can locate a prefix range but still needs an O(K) scan to collect matches and O(log N) insert-shift on updates. A Ternary Search Tree is the strongest real competitor — better memory than a fixed-array Trie when the alphabet is large/sparse — but our alphabet is tiny and fixed (27 symbols), so a Trie's direct array-indexed child access (O(1) per level) beats a TST's O(log σ) comparison per level; TST's memory advantage doesn't apply when σ is already small. Suffix trees/DAWGs are built for whole-corpus substring search over large text — massive overkill and implementation risk for a dataset of a few hundred short, fixed-format strings.

> **Rubric limitation worth flagging:** Hash Set, Sorted Array, and Ternary Search Tree all land on the exact same weighted score (3.65) despite being qualitatively very different — Hash Set *cannot perform the required operation at all* (no prefix query support), yet its strength on unrelated criteria (Space, Feasibility, Determinism) pulls its weighted average up to match candidates that can actually do the job. This is a general weakness of additive weighted-sum rubrics: a hard disqualifying constraint can be mathematically outweighed by unrelated strengths. We are not re-scoring Hash Set to fix this — we are flagging it, because pretending the rubric alone adjudicates this case would be another overclaim. Hash Set is disqualified by capability, not by score.

**Winner: Trie (unchanged).** For a tiny fixed alphabet and a prefix-query requirement, nothing beats direct character-indexed branching — it's the textbook-correct structure for exactly this problem, so we kept it.

### Benchmark Evidence (measured — see `benchmark/results/RESULTS.md` Run 8)

All four candidates were implemented from scratch behind a frozen `TickerOps` contract over a 132-pair universe, and the benchmark runs **two phases by design**: an exact-match workload (insert + contains) that a hash set *can* serve — giving it a fair timed baseline — and a prefix workload (insert + contains 65% / autocomplete 35%, avg 6.9 matches per prefix) which is the subsystem's reason to exist. All rows are oracle-gated; the hash set's incapability is surfaced as an explicit N/A row, observed by *attempting* the workload, not assumed.

| structure | phase 1: exact match (median ms) | phase 2: prefix (median ms) |
| :--- | --: | --: |
| naive linear scan (baseline) | 40.89 | 63.38 |
| hash set | **2.92** | N/A — prefixSearch structurally unsupported |
| sorted array + binary search | 11.33 | 17.02 |
| ternary search tree | 8.62 | 16.39 |
| **Trie** | 3.03 | **10.63** |

**Findings:** the flagged rubric limitation is now measured, not merely noted. The three-way 3.65 tie does **not** survive contact with a workload: Hash Set is the fastest exact-matcher (2.92 ms; Run 8 read the trie's 3.03 as a statistical tie, but interleaved re-runs at two seeds in Run 12d put the hash set ~1.8× ahead) and is *structurally absent* from prefix search; Sorted Array and TST lose to the Trie on the workload it exists for (17.02 / 16.39 vs 10.63 ms in Run 8; a narrower ~1.15–1.3× in Run 12d's interleaved re-runs) while also losing to Hash Set on exact match — their tied 3.65 was an artifact of additive scoring, exactly as the note above suspected. The naive linear scan runs 6–14× slower than the winners, quantifying why the registry is not just a list. (Two implementation findings during development — a TST enumeration bug the oracle gate caught, and a trie child-iteration refinement (27-slot scan → child bitmap, the same technique the architecture doc specifies for radix nodes) worth 3.2× on the prefix workload — are documented in RESULTS.md Run 8.)

---

## 6. Trade Telemetry Log Compression

**Access pattern:** batch-compress completed trade log text for archival storage; decompression correctness matters more than squeezing out the last few percent of ratio, and the implementation must be provably correct from scratch.

| Candidate | Op. Fit | Space (ratio) | Feasibility | Precedent | Determinism | **Weighted** |
| :--- | :-: | :-: | :-: | :-: | :-: | :-: |
| Run-Length Encoding (RLE) | 1 | 1 | 5 | 2 | 5 | **2.55** |
| LZ77 / LZ78 / LZW | 4 | 4 | 2 | 4 | 4 | **3.60** |
| Arithmetic / Range Coding | 4 | 5 | 1 | 4 | 3 | **3.40** |
| Asymmetric Numeral Systems (ANS) | 5 | 5 | 1 | 5 | 3 | **3.90** |
| BWT + MTF + RLE (bzip2-style) | 4 | 5 | 1 | 3 | 3 | **3.25** |
| **Huffman Coding** *(unchanged final pick)* | **4** | **4** | **5** | **4** | **5** | **4.35** |

**Why the alternatives lost:** RLE only helps on long repeated runs, which trade logs (varied JSON-like fields) don't reliably have. LZ-family, arithmetic coding, ANS, and BWT-based schemes (the actual state of the art — ANS is what powers zstd and LZFSE today) all compress *better* than Huffman in principle, because Huffman is constrained to integer-bit-length codes while arithmetic/ANS can assign fractional bits — but every one of them is substantially harder to implement correctly from scratch within a semester (adaptive models, carry propagation, state-table construction), and a single subtle bug silently corrupts archived trade records, which is an unacceptable risk for financial audit logs.

**Winner: Huffman Coding (unchanged).** It is provably optimal *within the constraint of integer-length prefix codes* (proven via the exchange argument), its min-heap-based tree construction is simple enough to implement and test exhaustively, and it is the structure explicitly named in the course's Text Processing unit — the rare case where "good enough and provably correct" beats "best possible but fragile."

### Benchmark Evidence (measured — see `benchmark/results/RESULTS.md` Run 9)

All four candidates were implemented from scratch behind a frozen `CompressorOps` contract over 5.5 MB of synthetic trade-log text in three batch sizes, with this module's prescribed gate: **byte-for-byte round-trip identity** `decode(encode(x)) == x`, per batch, before any timing.

| structure | ratio | encode MB/s | decode MB/s |
| :--- | --: | --: | --: |
| RLE (PackBits) | 1.0240 | 890.9 | 1546.6 |
| LZW | **0.2962** | 110.4 | 268.7 |
| **Huffman** | 0.5874 | 186.3 | 177.6 |
| order-0 arithmetic | 0.5842 | 36.1 | 25.9 |

**Findings:** both halves of this section's argument are now measured. (1) LZ-family compression does beat Huffman on ratio — by ~2× on this data, a much larger gap than "in principle" suggested; recorded honestly as the strongest counterweight to the pick. (2) Huffman round-tripped byte-perfect on its first run, while the from-scratch LZW's first version silently emitted garbage (a dictionary-initialization bug the round-trip gate caught before any number was published; FAIL row retained in the raw CSV) — precisely this section's *"a single subtle bug silently corrupts archived trade records"* happening in miniature. (3) Order-0 arithmetic coding beats Huffman by only 0.5% relative, at 5–7× the time cost — the fractional-bit advantage is real but small at the complexity level a semester allows, reinforcing the pick's calculus. The verdict stands on audit-safety and provable optimality-within-class; the evidence now shows the trade's full shape, including the LZW number to revisit if storage cost ever outweighed implementation safety.

---

## Final Summary

| Subsystem | Chosen | Strongest Rejected Alternative | Deciding Factor | Evidence |
| :--- | :--- | :--- | :--- | :-: |
| Price Ladder | **Radix Tree** (16-ary, revised) | Direct-Indexed Array (4.10, numerically highest) | Overridden on robustness grounds, not a numeric win — see §1 verdict. Memory scales with active price levels, not tick-range magnitude | **Measured** |
| Order Queue + Cancel | **DLL + Hash Map** | Plain DLL (4.20) | Needed O(1) *lookup* before O(1) unlink — the Hash Map supplies exactly that | **Measured** |
| Arbitrage Detection | **Bellman-Ford** | SPFA (4.35) | Fixed, input-order-independent iteration count and simpler implementation beat SPFA's average-case speed, which is immaterial at this graph size (10–50 vertices) | **Measured** |
| Account Cache | **Hash Map + LRU** | Plain Hash Map (3.90) | Point-lookup workload doesn't need a tree's ordering; LRU closes the one real gap (unbounded memory) a plain hash map has | **Measured** |
| Ticker Lookup | **Trie** | 3-way tie: Hash Set / Sorted Array / Ternary Search Tree (3.65 each) | Only the Trie and TST can perform prefix queries at all; Trie wins on alphabet size (see rubric-limitation note in §5) | **Measured** |
| Log Compression | **Huffman Coding** | ANS (3.90) | Provably correct and simple to implement from scratch; ANS's ratio gain isn't worth the implementation risk for audit-critical logs | **Measured** |

**Overall conclusion:** this document was corrected after an external review found that most of its original weighted-sum arithmetic did not match its own stated formula, and that the original Price Ladder scoring overstated the Radix Tree's memory efficiency and industry precedent. With the numbers fixed, four of six subsystems are decisive wins (Order Queue, Arbitrage Detection, Account Cache, Log Compression), one has a genuine three-way statistical tie resolved by a hard capability requirement (Ticker Lookup), and one — Price Ladder — was a close engineering judgment call *when this document was rubric-only*.

**All six verdicts are now measured.** Every subsystem in this document has since been implemented from scratch behind a frozen contract and benchmarked (`benchmark/results/RESULTS.md`, Runs 1–12), against real data where it exists (a live Frankfurter currency snapshot for arbitrage) and a correctness gate independent of every candidate. All six benchmarks *confirmed* their section's verdict rather than overturning it — and the Price Ladder's case got the full double cycle: it first appeared to sharpen in radix's favor against a boxed `TreeMap` stand-in, then settled honestly once Ladder v2's hand-rolled Red-Black tree was measured: Run 10 had the real RB ~12% faster on the walk workload, but interleaved re-runs across three seeds on a second machine (Run 12c) show the two tied on speed, while radix keeps the ~32% memory edge and the tick-range robustness the verdict actually rests on. Building these benchmarks surfaced four real implementation bugs along the way — two in the arbitrage work, one in the Ternary Search Tree's sibling enumeration, one in LZW's dictionary lookup — all documented in RESULTS.md rather than hidden, plus one performance refinement (the Trie's child bitmap) that moved a verdict-relevant number by 3.2×. A later harness review (Run 12) corrected three timing artifacts — sequential timing that let JIT state leak between candidates, a treap queue candidate handicapped by an O(n) head search (105× slower, really ~2.2×), and arbitrage timings that included verification-only cycle reconstruction — and withdrew two single-machine speed claims that did not reproduce (RB faster than radix; trie tied with hash set on exact match); no verdict changed. Three honest tensions are recorded alongside the confirmations: Price Ladder's flat-array speed win at narrow range, Order Queue's plain-array near-tie at shallow depth, and Compression's ~2× LZW ratio advantage over the pick. None of the six verdicts should be read as final without the implementation and benchmark record they now carry — but every one of them now has that record.
