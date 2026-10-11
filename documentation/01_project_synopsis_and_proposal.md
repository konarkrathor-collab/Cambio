# Project Synopsis & Proposal

## Project Title
**Order Matching System with Currency Loophole Detector**

---

## 1. Executive Summary & Plain English Abstract

Modern financial exchanges (stocks, commodities, forex, crypto) process millions of orders per second where every microsecond matters. At the same time, international currency markets operate 24/7 across hundreds of currency pairs. Because exchange rates fluctuate rapidly across disparate global markets, temporary pricing discrepancies—known as **Currency Loopholes** or **Triangular Arbitrage**—inevitably emerge.

This project implements a full-stack, real-time trading platform that combines:
1. **An Ultra-Fast Order Matching Engine:** Matches buyers and sellers according to standard **Price-Time Priority (FIFO)** using custom in-memory data structures, guaranteeing $O(1)$ price insertion/deletion for a fixed tick width (deterministic, not amortized) and $O(1)$ head order execution.
2. **A Real-Time Currency Loophole (Arbitrage) Scanner:** Converts live forex rates into a directed graph and executes a logarithmic-transformed **Bellman-Ford Algorithm** to instantly pinpoint and highlight circular currency conversion paths that yield guaranteed risk-free profits.
3. **An Interactive Web Dashboard:** Provides live visual order book depth charts, an interactive force-directed currency network graph with glowing arbitrage cycles, an order placement playground, and algorithmic latency benchmarks.

---

## 2. Problem Statement & Real-World Motivation

### The Order Matching Problem
Traditional web apps store transaction requests in relational SQL databases and query orders using `SELECT * FROM orders WHERE ... ORDER BY price ASC, timestamp ASC`.
* **The Bottleneck:** Database table locks, disk I/O, and index scans create latency on the order of tens to hundreds of milliseconds. In volatile markets, this causes catastrophic slippage and order queuing bottlenecks.
* **The Solution:** An in-memory matching engine utilizing a **Radix Tree** (digit-bucketed by price tick) for sorted price discovery and **Hash-Indexed Doubly Linked Lists** for zero-overhead $O(1)$ order execution and cancellation.

### The Currency Loophole (Arbitrage) Problem
Consider 3 currency pairs:
* $USD \to EUR$ at rate $R_1 = 0.92$
* $EUR \to GBP$ at rate $R_2 = 0.86$
* $GBP \to USD$ at rate $R_3 = 1.28$

If a trader starts with $10,000\text{ USD}$:
$$\text{EUR} = 10,000 \times 0.92 = 9,200\text{ EUR}$$
$$\text{GBP} = 9,200 \times 0.86 = 7,912\text{ GBP}$$
$$\text{USD} = 7,912 \times 1.28 = 10,127.36\text{ USD}$$
$$\text{Net Profit} = +\$127.36 \text{ (a } 1.27\% \text{ risk-free return in milliseconds)}$$

* **The Challenge:** In a live market with 50+ currencies and hundreds of changing exchange rates, checking all $O(N^3)$ or $O(N!)$ combinations sequentially is too slow.
* **The Solution:** By taking the negative natural logarithm of exchange rates ($w = -\ln(R)$), multiplying rates becomes summing edge weights. Finding a profit cycle $\prod R_i > 1$ mathematically reduces to finding a **Negative Weight Cycle** $\sum -\ln(R_i) < 0$. The **Bellman-Ford Algorithm** discovers these cycles in deterministic $O(V \cdot E)$ time, relaxing every edge exactly $|V|-1$ times regardless of input order — a fixed, predictable runtime that matters for a latency-sensitive financial system.

---

## 3. Project Objectives

1. **Custom Data Structure Implementations:** Build pure, from-scratch data structures (Radix Trees, Doubly Linked Lists with Hash indexing, Directed Graphs, Hash Maps with LRU eviction, Tries, and Huffman Coding) without relying on high-level third-party container libraries.
2. **Deterministic Time Complexity:**
   * Order Placement & Insertion: $O(1)$ for a fixed tick width (deterministic, fixed-depth Radix Tree, independent of price level count $P$)
   * Order Execution (Head of Queue): $O(1)$
   * Order Cancellation: $O(1)$
   * Arbitrage Detection: $O(V \cdot E)$, deterministic (Bellman-Ford — fixed $|V|-1$ relaxation passes, no input-order dependence)
   * Active Account Margin Check: $O(1)$ average (Hash Map lookup, LRU-cached)
3. **Full-Stack Visual Experience:**
   * Live Order Book Depth Visualizer (Bids vs. Asks).
   * Interactive Currency Graph with real-time highlighted arbitrage paths.
   * Real-time Order Simulator capable of running 5,000+ stress-test orders.
   * Benchmark Tab measuring microsecond latency comparisons against naive approaches.

---

## 4. Syllabus & Course Outcome (CO) Alignment

| Syllabus Unit | Core Topic | Data Structure / Algorithm Applied | Course Outcome |
| :--- | :--- | :--- | :---: |
| **Unit 1: Dictionaries** | Dictionary ADT | Order ID Map for instant lookup | **CO1** |
| **Unit 2: Hashing** | Separate Chaining / Open Addressing | Hash Map indexing Doubly Linked List nodes for $O(1)$ cancellations; Hash Map + LRU Cache for Active Trader Margin Lookup | **CO2** |
| **Unit 3: Trees** | Radix Trees (multiway, digit-bucketed) | Radix Tree for Order Book Price Ladder | **CO3** |
| **Unit 4: Graphs** | Directed Graphs & Bellman-Ford | Forex rate graph with Negative Cycle Detection for Arbitrage Loopholes | **CO3** |
| **Unit 5: Text Processing** | Tries & Huffman Coding | Trie for currency pair lookup; Huffman Coding for trade telemetry logs | **CO4** |
| **Unit 6: Design Techniques** | Dynamic Programming / Greedy | Currency conversion chain optimization and execution ordering | **CO3** |
