# Project Roadmap, Milestones & Task Breakdown

This roadmap structures the development of the **Order Matching System with Currency Loophole Detector** across a 14-week timeline, directly synchronized with the course calendar, Class Tests, and Lab submissions.

> **Status (v1.0.0).** This is the plan as proposed. It is kept so the original intent stays visible, but parts of it no longer describe the system:
> * In practice the phases were replaced by the eight build stages of [08_build_plan_and_acceptance.md](./08_build_plan_and_acceptance.md), each with a verification gate. [07_team_workplan_and_phases.md §4](./07_team_workplan_and_phases.md#4-phases-replaced-by-the-build-stages) maps each phase to the stage that delivered it.
> * Each phase below carries an **As built** note wherever the plan and the delivered system differ.
> * §3B restates every performance target next to its measured value, marked met or missed. No target was changed.

---

## 1. Development Phases & Milestones

```
Timeline Overview (Weeks 1 - 14):
[Phase 1: DS Foundation] ---> [Phase 2: Engine Core] ---> [Phase 3: APIs & Sim] ---> [Phase 4: Frontend UI] ---> [Phase 5: Benchmark & Final Demo]
      (Weeks 1-3)                  (Weeks 4-6)                 (Weeks 7-9)                (Weeks 10-12)                 (Weeks 13-14)
                                  [Class Test 1]                                         [Class Test 2]               [Final ICA Submission]
```

---

### Phase 1: Custom Data Structures Foundation (Weeks 1 – 3)
* **Milestone 1:** Implement pure, tested OOP classes for all fundamental data structures without third-party containers.
  * Custom `RadixTree` with digit-bucket branching and bucket pruning, insertion, and deletion (no rotations required).
  * Custom `DoublyLinkedList` and `HashMap` (with separate chaining).
  * Custom `DirectedGraph` with adjacency list representations.
  * Custom `HashMap`-backed `LRUCache`, `Trie`, and `HuffmanTree`.
* **Deliverable:** Unit test suite verifying invariants (fixed-depth digit traversal and correct bucket pruning for Radix Tree, LRU eviction ordering, Huffman compression ratios).
* **As built:**
  * The benchmark lab (`benchmark/`) built every candidate for every subsystem, not only the picks, and gated each against an independent oracle.
  * The production ports (Stage 0) pass the benchmark's own correctness gates (`backend/test.sh`).
  * **The price ladder is a B+ tree (order 32), not a Radix Tree.** The lab measured the radix tree as no faster than a B+ tree or a red-black tree and about 2× the B+ tree's resident memory, so it was replaced before Stage 0 (doc 08 §3a, doc 03 §1).
  * A separate "DirectedGraph" class was not needed. The scanner holds the graph as a rate matrix (doc 03 §3).

---

### Phase 2: Core Algorithmic Subsystems (Weeks 4 – 6) *(Syncs with Class Test 1)*
* **Milestone 2:** Assemble the matching engine and loop detector modules.
  * **Module A (Order Matching Engine):**
    * Dual Radix Trees for Bids (Max) and Asks (Min).
    * Price-Time FIFO matching loop.
    * $O(1)$ order cancellation mechanism.
  * **Module B (Currency Loophole Detector):**
    * Currency pair rate ingestion into directed graph.
    * $w = -\ln(R)$ logarithmic transformation module.
    * Bellman-Ford negative cycle scanner and cycle path reconstructor.
* **Deliverable:** Command-line demo showing 1,000 orders matched and an artificial triangular arbitrage detected and printed.
* **As built:**
  * B+ tree ladders (one per side per pair), not Radix Trees.
  * The scanner adds the threshold-shifted passes and an exact triangle check (doc 03 §3).
  * The demo is `backend/run.sh demo` (Stage 1).

---

### Phase 3: Backend Gateway, WebSocket & Load Simulator (Weeks 7 – 9)
* **Milestone 3:** Expose engine capabilities via high-throughput REST and WebSocket APIs.
  * WebSocket broadcasting for order book updates and loophole alerts.
  * REST endpoints for placing orders, cancelling orders, querying depth, and triggering scans.
  * **High-Speed Order Generator:** Simulates thousands of incoming orders per second with configurable market volatility.
* **Deliverable:** Automated script sending 5,000 simulated orders and logging execution latencies.
* **As built:**
  * Server-Sent Events (`GET /api/stream`) replace WebSocket for broadcasting. The browser only receives, and SSE needs no extra library on either side.
  * The REST API is frozen in [09_api_contract.md](./09_api_contract.md).
  * The order generator runs inside the JVM (`POST /api/simulate`, `backend/scripts/simulate.sh`) on an isolated engine.
  * It is compared against a naive linear-scan matcher fed the identical stream, with trades and balances checked equal.

---

### Phase 4: Interactive Web Frontend & Visualizers (Weeks 10 – 12) *(Syncs with Class Test 2)*
* **Milestone 4:** Full interactive user interface built with modern web technologies.
  * **Live Order Book Depth Visualizer:** Real-time bid/ask ladder and cumulative volume depth chart.
  * **Interactive Forex Graph Map:** Force-directed network graph (D3.js / Canvas) where detected arbitrage loops glow and pulse.
  * **Simulation & Stress-Test Dashboard:** Sliders for speed, volume, and rate volatility, displaying real-time FPS, latency, and throughput counters.
  * **Under-the-Hood Inspector:** Interactive tree/list view showing the internal Radix Tree and Doubly Linked List states changing live.
* **Deliverable:** Functional local web application with real-time UI/UX.
* **As built:**
  * React + d3 dashboard (`frontend/`), served by the Java server on the same port (`start-all.sh`).
  * Views:
    * Market: ladder, depth chart, order ticket, trades, accounts.
    * Arbitrage: the force graph, with the cycle glowing.
    * Simulator: engine versus baseline, with the targets.
    * Inspector: the ladder's levels and queues, and the B+ tree's shape.
  * The simulator takes order count, seed, profile, scan interval and rate volatility as form fields. There are no FPS counters.

---

### Phase 5: Benchmarking, Documentation & ICA Final Submission (Weeks 13 – 14)
* **Milestone 5:** Final optimization, comprehensive documentation, and submission prep.
  * Microsecond latency comparison table (Custom in-memory DS vs. Naive SQL / linear scan).
  * Plagiarism & integrity verification (purely original student code).
  * Final Project Report and Presentation Slides for Prof. Ashwini Rao.
* **Deliverable:** Complete code repository, project documentation, and live demo.
* **As built:**
  * The comparison baseline is a naive in-memory linear-scan matcher, not SQL. Results are in §3B and [10_final_report.md](./10_final_report.md).
  * Demo script: [DEMO.md](./DEMO.md).

---

## 2. Technical Scope & Core Modules

The implementation work is divided into the following technical components, with individual responsibilities to be finalized after team discussion:

1. **Order Matching Engine Module:**
   * Custom **Radix Tree** price ladder implementation. *(As built: B+ tree, see Phase 1.)*
   * **Doubly Linked List + Hash Map** for $O(1)$ head execution and cancellations.
   * Core **Price-Time Priority** order matching loop.

2. **Currency Loophole & Graph Module:**
   * **Directed Weighted Graph** for foreign exchange pairs.
   * **$-\ln(R)$ logarithmic transformation** for exchange rates.
   * **Bellman-Ford Negative Cycle Scanner** and circular path reconstruction.
   * Market simulation engine with configurable volatility.

3. **Auxiliary Data Structures & Web UI Module:**
   * **Hash Map + LRU Cache** for active trader margin/balance caching.
   * **Prefix Trie** for currency ticker autocomplete & validation.
   * **Huffman Coding** for trade execution log compression.
   * **Interactive Web Frontend** (Live Order Book depth chart, glowing D3.js Currency Graph & WebSocket streaming). *(As built: SSE streaming, see Phase 3.)*

---

## 3. Verification & Testing Strategy

### A. Unit Testing
* **Radix Tree Invariant Checker:** Automated validation checking that every leaf sits at the fixed digit-bucket depth and that empty buckets are always pruned, after 10,000 random insertions and deletions.
* **Arbitrage Equivalence Test:** Verifies that compound product $\prod R_i > 1$ exactly matches negative cycle output $\sum -\ln(R_i) < 0$.
* **As built:**
  * The ladder check runs against the B+ tree. Every ladder is replayed against a `TreeMap` oracle on the walk, wide-range, sorted and band-1 to band-3 stress workloads (300,000 operations each for the main workloads). Empty levels must be removed.
  * The scanner reports a cycle only if its **exact** rate product reaches the threshold, so the two forms are checked against each other on every report.
  * Totals: 207 backend tests ([results/parity_gates.txt](./results/parity_gates.txt)), plus the feed and frontend suites, the end-to-end script and six failure drills, all run by `scripts/verify-all.sh`.

### B. Performance & Stress Testing

The three targets as originally written:
* **Throughput Target:** $\ge 50,000$ order insertions/matches per second in memory.
* **Scan Latency Target:** $< 100 \mu\text{s}$ per Bellman-Ford scan on a 20-currency graph.
* **Cancellation Latency:** Constant $O(1)$ time ($< 1 \mu\text{s}$) regardless of queue length.

**Measured against the targets (final run, v1.0.0).** The machine is in [results/environment.txt](./results/environment.txt): a Linux container, Xeon @ 2.10 GHz, 4 cores, OpenJDK 21. The targets are unchanged. Each row gives the measurement and the file it comes from.

| Target (unchanged) | Measured | Verdict | Source |
| :--- | :--- | :-: | :--- |
| Throughput ≥ 50,000 orders/s in memory | **933,266 to 3,643,727 orders/s** across seven runs (shallow book: 5k, 50k, 200k, 1M orders; deep book: 5k, 50k, 200k). Each is timed inside the engine with no HTTP: limit and market orders, cancels and invalid orders, with matching, margin checks and settlement | **Met** (lowest run 18.7× the target) | [results/simulator.txt](./results/simulator.txt) |
| Cancel O(1), < 1 µs regardless of queue length | Median per cancel **0.094 to 0.641 µs** in every run. p99 is **0.417 to 5.040 µs**: under 1 µs in 3 of 7 runs, over in the other 4 (2.392, 5.040, 1.413 and 1.951 µs) | **Met at the median, missed at p99** | [results/simulator.txt](./results/simulator.txt) |
| — "regardless of queue length" | The work per cancel is constant: one hash lookup and one unlink. Measured wall time is not constant. The median rises from 0.094 µs with 57 orders resting to 0.641 µs with 113,390 resting (about 7× for 2,000× more orders), because a larger book misses the CPU cache more often | **Partly met**: constant in operations, not in nanoseconds | [results/simulator.txt](./results/simulator.txt) |
| Scan < 100 µs on a **20-currency** graph | No cycle present: **11.61 µs**. A +0.4% cycle present: **160.33 µs**. A sub-threshold cycle present: **173.22 µs** | **Missed with a cycle present** | [results/scan_timing.txt](./results/scan_timing.txt) |
| — at the project's real sizes | **7 currencies** (the live feed): 0.86 µs with no cycle, 4.28 µs with a cycle. **12 currencies** (live plus fallback): 3.16 µs with no cycle, 24.36 to 24.98 µs with a cycle | **Met** (worst 24.98 µs) | [results/scan_timing.txt](./results/scan_timing.txt) |

**Why the 20-currency scan misses.**
* A plain Bellman-Ford pass at V = 20 would fit easily. The benchmark's plain Bellman-Ford takes about 104 µs per scan at **V = 50** ([results/parity_timing.txt](./results/parity_timing.txt)).
* The production scan does more than one pass. When a negative cycle exists, it runs up to V + 1 threshold-shifted passes so that a sub-threshold cycle cannot hide a real one (`backend/BUGS.md` B4), and then checks every triangle exactly (B8).
* That multiplies the cost by roughly V when a cycle is present. It is a deliberate trade of speed for a correct report.
* At the graph sizes the system actually runs (7 and 12 currencies), the worst case is 25 µs.
* The scan runs at most every 500 ms (the throttle), on the engine thread between commands, so it never delays matching for long. The flood check measures this: [results/drill_flood_check.txt](./results/drill_flood_check.txt).

**Supporting numbers** (not targets):
* **Deep book.** The engine is **17.24× and 91.12×** faster than the naive linear-scan matcher at 50,000 and 200,000 orders, with 28,382 and 113,390 orders resting.
* **Shallow book** (26 to 57 orders resting). The naive matcher is as fast or faster, at 0.47× to 1.31×. A linear scan of 50 orders is cheap, and the structures pay off only when books are deep.
* **Identical results.** In every run the engine and the naive matcher produced the same trades and the same final balances: `checksumMatch: true` in all 7 runs ([results/simulator.txt](./results/simulator.txt)).
