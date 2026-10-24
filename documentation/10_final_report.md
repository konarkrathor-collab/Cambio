# Final Project Report: Order Matching System with Currency Loophole Detector

**Course:** Advanced Data Structures (ADS – `702IT0E009`), SVKM's NMIMS MPSTME / STME  
**Faculty In-Charge:** Prof. Ashwini Rao  
**Group:** Krishna Solanki (`I075`), Konark Rathor (`I062`), Palak Savla (`F054`)  
**Release:** v1.0.0

Every number in this report comes from a committed result: the files in [results/](./results/README.md), [benchmark/results/RESULTS.md](../benchmark/results/RESULTS.md) or [backend/BUGS.md](../backend/BUGS.md). The source is named next to each table. Nothing is estimated.

---

## Abstract

We built a trading-exchange simulator with a currency-loophole detector, in which every data structure on the hot path is hand-written. It has three parts:
- a Java matching engine with price-time priority;
- a Bellman-Ford scanner that finds profitable currency conversion loops in real forex rates;
- a React dashboard fed by Server-Sent Events.

Six structures were chosen by first building and benchmarking every realistic alternative against an independent correctness oracle (15 recorded benchmark runs). Measurement overturned one of our own picks: the price ladder was planned as a radix tree and became a B+ tree. The B+ tree tied or beat the radix tree on speed and held about half the memory per price level.

Results on the finished system:
- **Throughput.** The engine handles 933,266 to 3,643,727 orders per second in memory, against a target of 50,000.
- **Deep versus shallow books.** It is 17.24× and 91.12× faster than a naive linear-scan matcher on deep books, and no faster on shallow ones. We report both.
- **Scan time.** A scan takes at most 24.98 µs at the 12 currencies the system uses. The original 100 µs target at 20 currencies is missed when a cycle is present (160.33 µs).
- **Real data.** On a recorded capture of real OANDA ticks the detector finds no loophole above its data-derived 0.02% threshold.
- **Reliability.** Fuzzing, crash, feed-failure, stream-stress and 10-minute soak drills all pass. Twenty bugs found during the build are documented with their fixes.

---

## 1. Problem

An exchange keeps, for each instrument, a book of resting buy and sell orders. It must:
- match each incoming order against the best opposite price, oldest first within a price (price-time priority);
- let any resting order be cancelled by id;
- show the book's depth;

all at a rate of thousands of orders per second.

Separately, forex rates for different pairs are quoted independently. If converting around a loop of currencies, for example USD → EUR → GBP → USD, multiplies to more than 1, there is a **currency loophole** (triangular arbitrage when the loop has three legs).

With edge weights $w(u,v) = -\ln R(u,v)$, a loop with $\prod R > 1$ has $\sum w < 0$. Detecting a loophole is therefore detecting a negative cycle in a directed graph (doc 03 §3).

The course asks for both problems to be solved with data structures we design, justify and implement ourselves. The project brief added three more requirements:
- real market data, never presented as live when it is not;
- a dashboard to demonstrate it;
- evidence for every structure choice.

---

## 2. Design

Full detail is in [02_system_architecture_and_design.md](./02_system_architecture_and_design.md). The API, with captured examples, is in [09_api_contract.md](./09_api_contract.md).

```
  React dashboard  --REST-->  Java backend (one port)  <--POST rates--  Python feed
        ^                       |                                     (Finnhub WebSocket,
        +------ SSE ------------+  HTTP threads -> ONE engine thread   Frankfurter fallback,
                                   MatchingEngine | ArbitrageScanner    --replay FILE)
                                   Vault | Directory | Logbook
                                   Simulator thread (isolated engine + naive baseline)
```

- **One engine thread.**
  - Every order, cancel, read and scan runs on one thread, in arrival order. HTTP threads only parse and validate.
  - The engine therefore needs no locks, and the same order log always produces the same trades. That determinism is what the simulator's checksum comparison relies on.
- **Integer prices.** Prices are integer ticks inside the engine. The API converts them with exact decimal arithmetic, rejecting a price that is not a whole number of ticks; the engine package uses no floating point.
- **A Python feed that computes nothing.** It fetches ticks and forwards them. All rate derivation, staleness and detection is in Java, so the data structures being assessed do all the work.
- **Server-Sent Events instead of WebSocket.** Every live update flows one way, from server to browser, and the JDK's HTTP server can stream SSE with no library.
- **Persistence.**
  - The Huffman trade archive, the account ledger and the id counters are flat files.
  - Trades are always written before balances, so a balance on disk never includes a trade the archive lacks.
  - Ids are reserved ahead on disk, so they never repeat after a crash.
  - Resting orders are not persisted.
- **Matching rules** (doc 08 Appendix A):
  - limit and market orders;
  - fills at the resting order's price;
  - a margin check, with a fixed validation precedence;
  - cash in simulation units (price tick × quantity).

---

## 3. Data-structure selection and the evidence

The method:
1. Score every realistic candidate against a weighted rubric: operation fit, space, feasibility, precedent, determinism.
2. Build every candidate in a separate benchmark lab (`benchmark/`) behind a frozen interface.
3. Gate each candidate against an independent oracle before timing it.
4. Time the candidates interleaved, with warmups.

The rubric and the full survey are in [05_data_structure_selection_rationale.md](./05_data_structure_selection_rationale.md). The structures as built are described in [03_data_structures_deep_dive.md](./03_data_structures_deep_dive.md). The numbers below are from the final run, Run 15 ([results/benchmark_run15.txt](./results/benchmark_run15.txt), seed 42, 7 timed reps after 3 warmups).

### 3.1 Price ladder: B+ tree (order 32), replacing the planned radix tree

| structure | walk, 300,000 ops (ms) | sorted insertion, 12,000 ops (ms) | resident B per level (54,169 levels) |
| :--- | --: | --: | --: |
| flat array | 10.34 | 3.93 | 2,477.8 (134 MB) |
| **B+ tree, order 32** | **24.50** | **0.78** | **28.9** |
| sorted array | 27.66 | 1.00 | 88.6 |
| red-black tree | 29.00 | 0.92 | 48.0 |
| unbalanced BST | 30.54 | **39.87** | 40.0 |
| radix tree, 16-ary (original pick) | 32.28 | 0.98 | 58.6 |
| B-tree, order 32 | 34.53 | 0.91 | 28.5 |
| AVL tree | 38.57 | 1.08 | 40.0 |
| skip list | 47.02 | 1.61 | 56.5 |
| JDK TreeMap (oracle) | 50.79 | 1.36 | 88.0 |
| splay tree | 55.10 | 0.92 | 40.0 |
| 2-3 tree | 57.74 | 1.67 | 94.8 |
| unsorted array | 116.40 | 7.36 | 88.6 |

**The flat array.** It is fastest when the tick range is narrow. Its memory grows with the range, not with the book: it holds 134 MB for a book that every tree keeps in 1.5–5 MB. That disqualifies it for instruments with wide tick ranges.

**Radix tree versus B+ tree.**
- The radix tree, our design-stage pick, tied red-black on speed across seeds (Run 12c). Its timing in this run carries a 280% spread.
- In resident memory it used 9–22% more than red-black (Run 14).
- The B+ tree was the fastest and leanest balanced ladder.
- We changed the pick before writing production code (doc 08 §3a). The engine depends only on a `PriceLadder` interface, so the change stays reversible.

**The unbalanced BST** shows the cost of not balancing: about 24× slower under sorted insertion than every balanced candidate.

### 3.2 Order queue: doubly linked list + hash index

| structure | shallow book, 3,125 live (ms) | deep book, 15,397 live (ms) |
| :--- | --: | --: |
| JDK LinkedHashMap (oracle) | 7.51 | 9.97 |
| array (shift on cancel) | 7.79 | 100.71 |
| **DLL + hash index (pick)** | **8.17** | **10.24** |
| treap keyed by id | 22.54 | 29.94 |
| singly linked list | 29.33 | 488.86 |
| DLL without an index | 29.77 | 551.23 |

**Shallow book:** the array ties the pick. **Deep book:** it is 9.8× slower, and lists without the index are about 50× slower. The pick barely moves. In production the index is one hash table for the whole engine, threaded through the order nodes themselves.

### 3.3 Loophole detection: Bellman-Ford on −ln(rate)

**On the real 8-currency Frankfurter snapshot**, where the brute-force oracle finds no cycle, per scan:

| algorithm | ms per scan |
| :--- | --: |
| Bellman-Ford | 0.0006 |
| Johnson | 0.0006 |
| SPFA | 0.0006 |
| Tarjan + Bellman-Ford | 0.0015 |
| Floyd-Warshall | 0.0018 |

**With an injected cycle:**

| algorithm | ms per scan |
| :--- | --: |
| Bellman-Ford | 0.0006 |
| Johnson | 0.0006 |
| SPFA | 0.0013 |
| Floyd-Warshall | 0.0019 |
| Tarjan + Bellman-Ford | 0.0020 |

Bellman-Ford ties the fastest. It does the same work whatever the input order, and it is the simplest to verify.

**The production scanner adds three things** (doc 03 §3):
1. quote expiry;
2. threshold-shifted passes, so a sub-threshold cycle cannot hide a real one (B4);
3. an exact check of every triangle.

### 3.4 Accounts: hash ledger + LRU cache

| structure | ms (300,000 lookups, Zipf 1.0) | entries held |
| :--- | --: | --: |
| hash, open addressing (unbounded) | 6.40 | 47,907 |
| hash, chaining (unbounded) | 6.53 | 47,907 |
| **hash + LRU (pick, capacity 10,000)** | **10.86** | **10,000** |
| JDK LinkedHashMap LRU (oracle) | 15.84 | 10,000 |
| splay tree | 65.30 | 47,907 |

The unbounded maps are faster because they never evict. The pick is the fastest *bounded* structure, 1.46× faster than the JDK's own LRU.

### 3.5 Pair lookup: trie

**Exact match:**

| structure | ms |
| :--- | --: |
| hash set | 6.00 |
| **trie** | **8.36** |
| ternary search tree | 15.97 |
| sorted array | 21.12 |

**Prefix search:**

| structure | ms |
| :--- | --: |
| **trie** | **28.84** |
| sorted array | 36.73 |
| ternary search tree | 39.14 |
| hash set | cannot answer |

The trie loses exact match to a hash set and wins the prefix workload it exists for. It also gives each pair a dense id, so the engine keeps its books in an array.

### 3.6 Trade archive: canonical Huffman coding

| codec | ratio (encoded / original) | encode MB/s | decode MB/s |
| :--- | --: | --: | --: |
| RLE | 1.0240 | 302.0 | 516.1 |
| LZW | **0.2962** | 68.0 | 110.1 |
| **Huffman (pick)** | 0.5874 | 91.1 | 81.0 |
| arithmetic, order 0 | 0.5842 | 18.0 | 11.2 |

LZW compresses about 2× better. However, our first LZW silently corrupted data, which the round-trip gate caught (Run 9), while Huffman was correct on its first run. For an audit archive we chose the codec whose correctness is easiest to establish, and we record LZW's ratio as the strongest argument against that choice.

---

## 4. Implementation

| Part | Size and shape | Where |
| :--- | :--- | :--- |
| **Backend** | Java 17, plain `javac`, no build tool, no external libraries. The structure packages import no `java.util` collection. Matching uses only `long` ticks | `backend/src/ads/` |
| **Feed** | Python 3. Finnhub WebSocket with reconnect backoff, Frankfurter fallback after 15 s down, replay of a capture. The API key is read only from the environment and is never logged | `feed/` |
| **Dashboard** | React + Vite, d3 modules, no UI framework, no CDN. Served by the Java server on the same port (`serve --static-dir`, with path-traversal protection) | `frontend/` |
| **Tooling** | `start-all.sh` (one command, one port), `scripts/verify-all.sh` (every gate), the drills, a fuzzer, a soak test, a link checker | root, `scripts/` |
| **Benchmark lab** | Every candidate structure, oracle-gated, with an evidence log of Runs 1–15 | `benchmark/` |

**How it was built.** In eight stages, each closed by a verification gate (doc 08):
- Stage 0: structures ported with parity tests;
- Stage 1: the engine and the services;
- Stage 2: the feed;
- Stage 3: the API and the event stream;
- Stage 4: the simulator;
- Stage 5: the dashboard;
- Stage 6: hardening and failure drills;
- Stage 7: delivery.

**Production versus benchmark.**
- The production versions of all six structures pass the benchmark's own correctness gates: 207 backend tests passed, 0 failed ([results/parity_gates.txt](./results/parity_gates.txt)).
- They run close to the benchmark versions ([results/parity_timing.txt](./results/parity_timing.txt)):
  - ladder walk +3.6%;
  - queue shallow +10.0%;
  - cache +21.6%;
  - ticker 20–31% faster;
  - Huffman encode −4.1%.
- The one delta above 25% is the deep queue, at +35.9%. The production queue node is a full order, carrying the level pointer and index link that O(1) cancel needs; it allocates 40.9 against 20.9 bytes per operation (doc 03 §8).

---

## 5. Evaluation

### 5.1 The doc 04 targets, measured

The original targets are unchanged. The full table, with sources, is in [doc 04 §3B](./04_project_roadmap_and_milestones.md#b-performance--stress-testing).

| Target | Measured | Verdict | Source |
| :--- | :--- | :-: | :--- |
| ≥ 50,000 orders/s in memory | 933,266 to 3,643,727 orders/s over 7 runs | **Met** | [simulator.txt](./results/simulator.txt) |
| Cancel < 1 µs, O(1) | median 0.094 to 0.641 µs; p99 0.417 to 5.040 µs, above 1 µs in 4 of 7 runs | **Met at the median, missed at p99** | [simulator.txt](./results/simulator.txt) |
| Scan < 100 µs, 20 currencies | 11.61 µs with no cycle; 160.33 µs with a cycle; 173.22 µs with a sub-threshold cycle | **Missed with a cycle present** | [scan_timing.txt](./results/scan_timing.txt) |
| (the same at the real sizes) | 7 currencies: 0.86 / 4.28 / 4.30 µs; 12 currencies: 3.16 / 24.36 / 24.98 µs | **Met** | [scan_timing.txt](./results/scan_timing.txt) |

**Why the scan misses at 20 currencies.** When a negative cycle exists, the scanner runs up to V + 1 threshold-shifted Bellman-Ford passes, then checks every triangle. That multiplies the cost by roughly V, in exchange for never letting a small cycle hide a real one (B4). We kept the correctness and report the miss.

**Why the median cancel time grows.** The work per cancel is constant, but the measured median rises about 7× as the book grows 2,000×. The cause is cache misses, not extra work.

### 5.2 Engine versus naive linear scan (the same order stream, results checked equal)

From [results/simulator.txt](./results/simulator.txt). The simulator runs the engine and a naive matcher, which scans an array for the best price, on the identical seeded stream. Trades, rejects, cancels and every trader's final cash are compared: `checksumMatch` was true in all 7 runs.

| profile | orders | resting at end | engine orders/s | naive orders/s | speedup |
| :--- | --: | --: | --: | --: | --: |
| shallow | 5,000 | 46 | 1,094,517 | 2,326,872 | 0.47× |
| shallow | 50,000 | 26 | 1,050,815 | 1,650,080 | 0.64× |
| shallow | 200,000 | 26 | 1,764,882 | 2,540,308 | 0.69× |
| shallow | 1,000,000 | 57 | 3,643,727 | 2,781,949 | 1.31× |
| deep | 5,000 | 2,881 | 933,266 | 632,799 | 1.47× |
| deep | 50,000 | 28,382 | 1,046,260 | 60,679 | 17.24× |
| deep | 200,000 | 113,390 | 990,671 | 10,871 | 91.12× |

**Shallow books.** With fewer than 60 orders resting, scanning an array is as cheap as walking a tree, and the naive matcher wins or ties. We measured this in Stage 4 (B11), kept it as the default profile, and added the deep profile instead of tuning the workload.

**Deep books.** As the book deepens, the naive matcher's cost per order grows with the book while the engine's stays flat.

### 5.3 Real market data and the threshold

Replaying the committed capture of real OANDA ticks gives 3,690 ticks over 299 s and 432 scans ([results/threshold_study.txt](./results/threshold_study.txt)):

| threshold | scans reporting a cycle | some cycle reaches it (exhaustive) | some triangle reaches it |
| :-: | :-: | :-: | :-: |
| 0.001% | 429 / 432 | 429 / 432 | 429 / 432 |
| 0.005% | 427 / 432 | 427 / 432 | 427 / 432 |
| 0.010% | 58 / 432 | 286 / 432 | 4 / 432 |
| 0.020% | 0 / 432 | 0 / 432 | 0 / 432 |

- The best cycle in any scan reached 0.01652%. Below about 0.01% the ticks' alternation between bid and ask creates apparent cycles in almost every scan.
- The default threshold, 0.02%, is the smallest candidate at which fewer than 1% of scans report a cycle.
- At 0.010%, the best real cycles have 5 to 7 legs (4, 38 and 244 scans), not 3. The scanner's exact triangle check never misses a triangle; longer loops are best-effort (B8, accepted).

### 5.4 Integration and failure drills

| Check | Result | Source |
| :--- | :--- | :--- |
| End to end (real backend + replayed feed) | 20 passed, 0 failed. Stream order BOOK_UPDATE < TRADE < LOOPHOLE_ALERT; balances recomputed from the trades | [drill_e2e.txt](./results/drill_e2e.txt) |
| Every trade reaches the archive | 1,047 of 1,047 trades archived; the newest 500 byte-identical to the API; 14 hostile pair names rejected | [drill_logbook_check.txt](./results/drill_logbook_check.txt) |
| Scanning never blocks matching | Order p99 3.467 ms without and 3.287 ms with 200 ticks/s (change −0.180 ms; limit +2 ms) | [drill_flood_check.txt](./results/drill_flood_check.txt) |
| Drill 1: `kill -9` | 7 passed. 50 of 50 trades archived; balances = seed + archive; ids continue from ORD-1000101 | [drill_kill9.txt](./results/drill_kill9.txt) |
| Drill 2: feed killed | Status `disconnected` after 14.97 s; scan cleared 29.72 s after the kill (limit 30 s); live again 1 s after the restart | [drill_feed.txt](./results/drill_feed.txt) |
| Drill 3: socket lost | Frankfurter fallback after 16 s (trigger 15 s); live again 9 s after the socket returned | [drill_feed.txt](./results/drill_feed.txt) |
| Drill 4: fuzz | 7,415 requests: no 500, no hang, cash conserved, no double cancel, account bounds enforced | [drill_fuzz.txt](./results/drill_fuzz.txt) |
| Drill 5: event streams | 32 streams; order p99 3.272 → 3.942 ms (limit +2 ms); the never-reading client dropped; a 33rd stream refused | [drill_sse.txt](./results/drill_sse.txt) |
| Drill 6: soak (600 s at 300 orders/s + 50 ticks/s) | Threads 27 → 27; file descriptors 12 → 11; live heap 4,897 → 2,926 KB; order p99 3.296 → 3.197 ms (first and last two minutes) | [soak.txt](./results/soak.txt) |

The order latencies in this table are measured end to end over HTTP on the same machine, so they include the HTTP stack. The in-engine figures are in §5.1.

---

## 6. Bugs found and fixed

The team's rule was that every bug is written up, not silently fixed. [backend/BUGS.md](../backend/BUGS.md) records 20 bugs, each with what broke, why, and the fix: B1–B17 in the backend and feed, F1–F3 in the dashboard. Each fix came with a test that fails without it. The benchmark lab's own bugs (an LZW dictionary bug, a TST enumeration bug, two arbitrage-harness bugs) are in RESULTS.md.

| | What broke | Found by |
| :--- | :--- | :--- |
| B1 | A ticker character outside A–Z and `/` crashed the trie | Stage 0 port, reproduced on the benchmark class |
| B2 | The Huffman decoder accepted corrupt archives | Stage 0 port, reproduced on the benchmark class |
| B3 | A failed build left stale classes that then ran | Own tooling |
| B4 | A sub-threshold cycle could hide a real loophole | Stage 1 design review |
| B5 | The fallback could never activate: its CDN rejected Python's user agent | The first live outage drill (`feed/live_check.sh`) |
| B6, B7 | A deeply nested message would crash the feed; a client hanging up mid-body got a 500 | Stage 2, caught before commit |
| B8 | Near the threshold, longer real cycles are missed (**accepted**: triangles exact) | Threshold study |
| B9 | Every HTTP response waited about 40 ms (Nagle's algorithm) | Stage 3 timing |
| B10 | Pretty-printing contract examples rewrote prices | Own tooling |
| B11 | On shallow books the engine is no faster than the naive baseline (measured, kept) | Stage 4 simulator |
| B12 | A crash before the first counter flush reissued ids | A new crash test |
| B13 | After a crash, balances could include trades the archive lacked | Drill 1 |
| B14 | A balance near the 64-bit limit made a fill throw | Drill 4 (fuzz) |
| B15 | An oversized upload got "connection reset" instead of 413 | Drill 4 |
| B16 | A stream client that stopped reading held a thread forever | Drill 5 |
| B17 | A dead feed's loophole cleared up to 1 s past the 30 s limit | Drill 2 |
| F1 | The pair picker could choose the previous query's pair | Headless-browser run |
| F2 | Through the dev proxy, the stream never noticed the backend going away | Stage 5 outage check |
| F3 | With a feed running, the DEMO loophole was overwritten within seconds | Writing the demo script |

---

## 7. Limitations

- **Triangular detection only, exactly.**
  - Every three-currency loop at or above the threshold is reported.
  - Longer loops are best-effort: Bellman-Ford returns *a* negative cycle, not the most profitable one, and exhaustive search over simple cycles is NP-hard (B8).
- **Simulation units.**
  - A trade moves price-tick × quantity between two traders' cash, with no currency conversion.
  - Resting orders reserve nothing, so cash can go negative (doc 08 Appendix A, pinned by a test).
- **A single server.**
  - One JVM and one engine thread: deterministic, but not replicated or horizontally scalable.
  - Resting orders are in memory only, so a restart starts with empty books.
- **No authentication.** Any client that reaches the port can trade as any trader. The server binds to 127.0.0.1 by default.
- **The live feed depends on market hours and on network access.**
  - At weekends the feed is connected but silent, and the dashboard says WAITING.
  - The demo therefore replays a recorded capture, labelled REPLAY.
  - INR, CNY, HKD, SGD and NZD have no live OANDA quote and come only from the daily fallback.
- **Missed targets.**
  - The 20-currency scan target, with a cycle present.
  - The 1 µs cancel target, at p99.
- **Where the measurements were taken.**
  - All timing comes from one Linux container. Run 9 (an Apple M4) shows that absolute speeds differ by machine while the rankings hold.
  - Browser checks were run in headless Chromium only.

---

## 8. Conclusion

All six structures were justified by measurement rather than by textbook complexity alone, and the measurements changed one decision. The B+ tree replaced our own radix-tree pick when the lab showed it was as fast or faster and about half the memory.

The finished engine runs 18.7× to 72.9× above its throughput target in memory. It beats a naive matcher by up to 91.12× on deep books, and we report that it does not on shallow ones. The detector checks every triangle exactly on real forex data, and correctly finds nothing above a threshold chosen from that data.

Two targets were missed: the 20-currency scan with a cycle present, and cancel latency at p99. We report both rather than redefine them. Twenty bugs were found and documented along the way, most of them by the drills designed to break the system.

One command (`scripts/verify-all.sh`) re-runs every gate from a fresh clone, and one command (`start-all.sh`) brings up the whole system on one port for the demo described in [DEMO.md](./DEMO.md).

---

## Where to find things

| Topic | Document |
| :--- | :--- |
| Proposal and objectives (as built) | [01](./01_project_synopsis_and_proposal.md) |
| Architecture | [02](./02_system_architecture_and_design.md) |
| Structures, complexity, production versus benchmark | [03](./03_data_structures_deep_dive.md) |
| Targets, measured | [04 §3B](./04_project_roadmap_and_milestones.md#b-performance--stress-testing) |
| Why each structure | [05](./05_data_structure_selection_rationale.md) |
| Market data feed, the INR finding, the threshold study | [06](./06_market_data_ingestion_service.md) |
| What was built, slice ownership (for the team to confirm) | [07](./07_team_workplan_and_phases.md) |
| Build plan and acceptance gates | [08](./08_build_plan_and_acceptance.md) |
| API contract | [09](./09_api_contract.md) |
| Benchmark evidence log, Runs 1–15 | [RESULTS.md](../benchmark/results/RESULTS.md) |
| Bugs | [BUGS.md](../backend/BUGS.md) |
| Committed measurements | [results/](./results/README.md) |
