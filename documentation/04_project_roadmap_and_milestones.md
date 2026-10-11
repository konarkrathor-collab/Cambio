# Project Roadmap, Milestones & Task Breakdown

This roadmap structures the development of the **Order Matching System with Currency Loophole Detector** across a 14-week timeline, directly synchronized with the course calendar, Class Tests, and Lab submissions.

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

---

### Phase 3: Backend Gateway, WebSocket & Load Simulator (Weeks 7 – 9)
* **Milestone 3:** Expose engine capabilities via high-throughput REST and WebSocket APIs.
  * WebSocket broadcasting for order book updates and loophole alerts.
  * REST endpoints for placing orders, cancelling orders, querying depth, and triggering scans.
  * **High-Speed Order Generator:** Simulates thousands of incoming orders per second with configurable market volatility.
* **Deliverable:** Automated script sending 5,000 simulated orders and logging execution latencies.

---

### Phase 4: Interactive Web Frontend & Visualizers (Weeks 10 – 12) *(Syncs with Class Test 2)*
* **Milestone 4:** Full interactive user interface built with modern web technologies.
  * **Live Order Book Depth Visualizer:** Real-time bid/ask ladder and cumulative volume depth chart.
  * **Interactive Forex Graph Map:** Force-directed network graph (D3.js / Canvas) where detected arbitrage loops glow and pulse.
  * **Simulation & Stress-Test Dashboard:** Sliders for speed, volume, and rate volatility, displaying real-time FPS, latency, and throughput counters.
  * **Under-the-Hood Inspector:** Interactive tree/list view showing the internal Red-Black Tree and Doubly Linked List states changing live.
* **Deliverable:** Functional local web application with real-time UI/UX.

---

### Phase 5: Benchmarking, Documentation & ICA Final Submission (Weeks 13 – 14)
* **Milestone 5:** Final optimization, comprehensive documentation, and submission prep.
  * Microsecond latency comparison table (Custom in-memory DS vs. Naive SQL / linear scan).
  * Plagiarism & integrity verification (purely original student code).
  * Final Project Report and Presentation Slides for Prof. Ashwini Rao.
* **Deliverable:** Complete code repository, project documentation, and live demo.

---

## 2. Technical Scope & Core Modules

The implementation work is divided into the following technical components, with individual responsibilities to be finalized after team discussion:

1. **Order Matching Engine Module:**
   * Custom **Radix Tree** price ladder implementation.
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
   * **Interactive Web Frontend** (Live Order Book depth chart, glowing D3.js Currency Graph & WebSocket streaming).

---

## 3. Verification & Testing Strategy

### A. Unit Testing
* **Radix Tree Invariant Checker:** Automated validation checking that every leaf sits at the fixed digit-bucket depth and that empty buckets are always pruned, after 10,000 random insertions and deletions.
* **Arbitrage Equivalence Test:** Verifies that compound product $\prod R_i > 1$ exactly matches negative cycle output $\sum -\ln(R_i) < 0$.

### B. Performance & Stress Testing
* **Throughput Target:** $\ge 50,000$ order insertions/matches per second in memory.
* **Scan Latency Target:** $< 100 \mu\text{s}$ per Bellman-Ford scan on a 20-currency graph.
* **Cancellation Latency:** Constant $O(1)$ time ($< 1 \mu\text{s}$) regardless of queue length.
