# Project Synopsis & Proposal

## Project Title
**Order Matching System with Currency Loophole Detector**

> **Status.** This page began as the project proposal. It now describes the system as built (doc 08 Stage 7). Where the built system differs from the proposal, the text says so and gives the reason, in the form *proposed: …; built: …*. The full list of changes is in §5. The architecture is in [02_system_architecture_and_design.md](./02_system_architecture_and_design.md), and the API in [09_api_contract.md](./09_api_contract.md).

---

## 1. Executive Summary & Plain English Abstract

Exchanges for stocks, commodities, forex and crypto match buy and sell orders continuously, and the time each match takes matters. Forex markets also quote many currency pairs at once. Because each pair is quoted separately, the rates can briefly disagree: converting money around a loop of currencies can return more than it started with. This is a **currency loophole**, or **triangular arbitrage** when the loop has three legs.

This project is a full-stack trading simulator with three parts:
1. **An in-memory order matching engine.** It matches buyers and sellers by **price-time priority** (best price first, first-in first-out within a price), using data structures written from scratch. Each side of a book is a **B+ tree of order 32** over price levels, so finding the best price and inserting or removing a price level take $O(\log P)$ for $P$ active levels. Executing the order at the head of a level and cancelling an order by id take $O(1)$ (average, for the hash lookup). *Proposed: a radix tree with $O(1)$ level operations for a fixed key width; built: a B+ tree, because the Stage 0 benchmark measured no speed advantage for the radix tree and found it used about twice the memory per level* ([08 §3a](./08_build_plan_and_acceptance.md#3a-decision--the-price-ladder)).
2. **A currency-loophole scanner.** It turns exchange rates into a directed graph with edge weights $-\ln(R)$, so a profitable loop becomes a negative cycle. It finds such cycles with **Bellman-Ford** and also checks **every triangle exactly**. Rates come from a live Finnhub feed, or from a Frankfurter snapshot when the live feed is down.
3. **A web dashboard.** It shows the order book ladder and depth chart, an order ticket, the trades feed, the currency graph with the detected cycle highlighted, a simulator that compares the engine with a naive baseline, and an inspector that shows the engine's internal structures. Updates are pushed to the browser with Server-Sent Events.

### Stack (as built)

| Tier | Language and tools | Responsibility |
| :--- | :--- | :--- |
| Backend (`backend/`) | Java 17, plain `javac`, no build tool, no external libraries | All computation: matching, order books, arbitrage scan, accounts, ticker search, trade archive compression, and the HTTP and Server-Sent Events gateway (the JDK's built-in HTTP server) |
| Data feed (`feed/`) | Python 3 | Fetches rates and forwards them to the backend (`POST /api/market/rates`). No business logic. |
| Dashboard (`frontend/`) | React with Vite (JavaScript), the d3 modules `d3-force`, `d3-scale`, `d3-shape` and `d3-selection` | Visualisation and order entry |

*Proposed: a WebSocket server for browser updates; built: Server-Sent Events (`GET /api/stream`), because every update flows one way, from server to browser, and the JDK's HTTP server can serve SSE with no extra library.* The stack was confirmed with the course faculty ([08 §1](./08_build_plan_and_acceptance.md#1-stack-confirmed-with-the-course-faculty)). How to run each part is in [backend/README.md](../backend/README.md), [feed/README.md](../feed/README.md) and [frontend/README.md](../frontend/README.md).

---

## 2. Problem Statement & Real-World Motivation

### The Order Matching Problem
A simple web application might keep orders in a relational database and find a match with a query such as `SELECT * FROM orders WHERE ... ORDER BY price ASC, timestamp ASC`.
* **The bottleneck:** every order then pays for locks, index scans and disk I/O, and the time per order grows with the size of the book.
* **The approach taken:** an in-memory matching engine. Each side of each pair's book is an ordered index of price levels (a **B+ tree**; *proposed: a radix tree*). Each price level holds a **doubly linked FIFO queue** of orders, and one engine-wide **hash index** maps an order id to its queue node, so a cancel never searches. Prices are integer ticks inside the engine, so no floating-point rounding can affect a match.

### The Currency Loophole (Arbitrage) Problem
An illustrative example with 3 currency pairs (these rates are made up to show the arithmetic):
* $USD \to EUR$ at rate $R_1 = 0.92$
* $EUR \to GBP$ at rate $R_2 = 0.86$
* $GBP \to USD$ at rate $R_3 = 1.28$

If a trader starts with $10,000\text{ USD}$:
$$\text{EUR} = 10,000 \times 0.92 = 9,200\text{ EUR}$$
$$\text{GBP} = 9,200 \times 0.86 = 7,912\text{ GBP}$$
$$\text{USD} = 7,912 \times 1.28 = 10,127.36\text{ USD}$$
$$\text{Net Profit} = +\$127.36 \text{ (a } 1.27\% \text{ return on the loop)}$$

Gaps of this size did not appear in the real data this project recorded. On the committed capture of real ticks, the scanner's default threshold reports no loophole at all; smaller apparent "profits" are bid/ask noise ([06_market_data_ingestion_service.md](./06_market_data_ingestion_service.md), [backend/BUGS.md](../backend/BUGS.md) B8). The dashboard's demo drawer can inject a loophole, clearly labelled as a replay, to show detection working.

* **The challenge:** checking every possible loop by brute force grows quickly with the number of currencies, and rates change all the time.
* **The approach taken:** take the negative natural logarithm of each rate ($w = -\ln(R)$). Multiplying rates becomes adding weights, and a profitable loop $\prod R_i > 1$ becomes a **negative cycle** $\sum -\ln(R_i) < 0$. **Bellman-Ford** relaxes every edge $|V|-1$ times and then detects a negative cycle in one more pass: $O(V \cdot E)$, with the same work whatever the input order. Because Bellman-Ford returns *some* negative cycle rather than the most profitable one, the built scanner runs extra shifted passes and also checks every directed triangle exactly (§3, objective 2).

---

## 3. Project Objectives

Each objective is stated as proposed, followed by its status as built.

1. **Custom data structure implementations.** Build the structures from scratch (proposed: radix tree, doubly linked list with hash index, directed graph, hash map with LRU eviction, trie, Huffman coding), without third-party container libraries.
   **Status: met, with one structure changed.** All six structures are hand-written in `backend/src/ads/`, and none of the structure classes or the matching path imports a `java.util` collection ([08 §3](./08_build_plan_and_acceptance.md#3-rules-for-everyone-writing-code-here), rule 7). The price ladder is a **B+ tree of order 32** (`BPlusTreeLadder`) instead of the radix tree; the engine reaches it only through the `PriceLadder` interface, so the choice stays reversible. Each structure passed the benchmark lab's own correctness gate against an independent oracle when it was ported (doc 08 Stage 0).

2. **Time complexity.** The bounds below are what the built code guarantees. $P$ is the number of active price levels on one side of a book, $K$ the number of levels walked, $V$ and $E$ the currencies and rates in the graph.

   | Operation | Proposed | As built | Status |
   | :--- | :--- | :--- | :--- |
   | Best bid / best ask | $O(1)$ (radix tree, fixed depth) | $O(\log P)$: one root-to-leaf descent of the B+ tree (`lowest()`, `highest()`) | Changed with the ladder |
   | Insert or remove a price level | $O(1)$ for a fixed tick width | $O(\log P)$, including B+ tree splits, borrows and merges | Changed with the ladder |
   | Book depth for $K$ levels | $O(K)$ | $O(\log P + K)$: one descent, then a scan along the linked leaves | Changed with the ladder |
   | Execute against the head of a level | $O(1)$ | $O(1)$ per fill (`PriceLevel` head of a doubly linked list) | Met |
   | Cancel by order id | $O(1)$ | $O(1)$ average: hash lookup in `OrderIndex` and unlink from the level's list; $O(\log P)$ more only when the cancel empties its level, which is then removed from the ladder | Met, with that qualification |
   | Arbitrage detection | $O(V \cdot E)$, deterministic | One Bellman-Ford pass, $O(V \cdot E)$, when no negative cycle exists. When one does, up to $V$ more shifted passes (so $O(V^2 \cdot E)$ worst case) plus an exact check of every directed triangle, $O(V^3)$ | Changed: more work, so that a sub-threshold cycle cannot hide a real one ([backend/BUGS.md](../backend/BUGS.md) B4, B8) |
   | Account margin check | $O(1)$ average (hash map + LRU) | $O(1)$ average: `Vault` hash ledger behind an `LruCache` | Met |

   The measured performance targets of doc 04 (throughput, cancel latency, scan time) are restated with their measured values, met or missed, in [04_project_roadmap_and_milestones.md](./04_project_roadmap_and_milestones.md). The simulator's engine-versus-baseline measurements, including the shallow workload on which the naive baseline is as fast as the engine, are in [backend/BUGS.md](../backend/BUGS.md) B11.

3. **Full-stack visual experience.**
   * *Live order book depth visualiser (bids and asks).* **Met:** the Market tab has the order book ladder with depth bars and a d3 depth chart, updated from `BOOK_UPDATE` events.
   * *Interactive currency graph with highlighted arbitrage paths.* **Met:** the Arbitrage tab draws the 12-currency graph with `d3-force`; stale rates are drawn faint and dashed, and the current cycle is highlighted with its legs, multiplier and profit.
   * *Order simulator able to run 5,000+ stress-test orders.* **Met:** `POST /api/simulate` runs from 1 to 1,000,000 seeded orders on an isolated engine, with a shallow and a deep profile ([09 §3.12](./09_api_contract.md#312-post-apisimulate-additive-stage-4)).
   * *Benchmark tab comparing latency with naive approaches.* **Met, as the Simulator tab:** each run can replay the same order stream through a naive plain-array matcher, checks that both produce the same trades and balances (`checksumMatch`), and shows throughput and latency percentiles side by side with the doc 04 targets marked met or missed. The structure-by-structure benchmarks live in the separate benchmark lab ([benchmark/results/RESULTS.md](../benchmark/results/RESULTS.md)).
   * *Added during the build:* an Inspector tab that shows each side's price levels, queue heads and the B+ tree's shape, and a clearly labelled DEMO drawer ([frontend/README.md](../frontend/README.md)).

4. **Live market data.** *(Added after the proposal; specified in [doc 06](./06_market_data_ingestion_service.md).)* **Met, with a coverage limit:** the Python feed streams Finnhub (OANDA) ticks and falls back to a Frankfurter snapshot when the socket has been down for 15 s. OANDA streams no pair involving INR, so INR gets a rate only from the fallback snapshot ([feed/README.md](../feed/README.md)).

---

## 4. Syllabus & Course Outcome (CO) Alignment

| Syllabus Unit | Core Topic | Data Structure / Algorithm Applied (as built) | Course Outcome |
| :--- | :--- | :--- | :---: |
| **Unit 1: Dictionaries** | Dictionary ADT | `OrderIndex` (order id → resting order) for cancel by id; the `Vault` account ledger (trader id → account); the ticker `Directory` (pair name → dense pair id) | **CO1** |
| **Unit 2: Hashing** | Separate chaining | `OrderIndex`: separate chaining through the orders themselves, resized at load 0.75. `Vault`: separate-chaining ledger with an `LruCache` (hash map plus an intrusive most-recently-used list) in front for margin checks | **CO2** |
| **Unit 3: Trees** | Multiway search trees: **B+ tree** | B+ tree of order 32 for each side of the order book's price ladder (`BPlusTreeLadder`), leaves linked for depth walks. *Proposed: radix tree; replaced after the Stage 0 benchmark ([08 §3a](./08_build_plan_and_acceptance.md#3a-decision--the-price-ladder)).* | **CO3** |
| **Unit 4: Graphs** | Directed weighted graphs, Bellman-Ford | Currency rate graph with weights $-\ln(R)$; Bellman-Ford negative-cycle detection with cycle reconstruction (`BellmanFord`, `CycleTrace`), plus an exact triangle check (`ArbitrageScanner`) | **CO3** |
| **Unit 5: Text Processing** | Tries, Huffman coding | `TickerTrie`: fixed-alphabet trie with a child bitmap for pair validation and prefix search (dashboard autocomplete). `HuffmanCodec`: canonical Huffman coding of the trade archive (`Logbook` segments) | **CO4** |
| **Unit 6: Design Techniques** | Dynamic programming, greedy | Bellman-Ford's edge relaxation is a dynamic program over path lengths. Huffman tree construction is greedy (repeatedly merge the two least frequent nodes from a min-heap). *Proposed: "currency conversion chain optimisation and execution ordering"; no separate optimiser was built.* | **CO3** |

---

## 5. What changed from the proposal

| Topic | Proposed | Built | Why |
| :--- | :--- | :--- | :--- |
| Price ladder | Radix tree (16-ary), $O(1)$ for a fixed key width | B+ tree of order 32 behind the `PriceLadder` interface, $O(\log P)$ | The benchmark lab measured the radix tree as no faster than the alternatives and found the B+ tree the leanest in resident memory ([benchmark/results/RESULTS.md](../benchmark/results/RESULTS.md) Runs 10–14; decision in [08 §3a](./08_build_plan_and_acceptance.md#3a-decision--the-price-ladder)) |
| Order cancel index | A hash map from order id to its queue node | One engine-wide `OrderIndex` for every pair, with the hash chains running through the orders themselves | `DELETE /api/orders/{id}` carries no pair, so the order must be found from its id alone (doc 08 Stage 1) |
| Arbitrage scan | One Bellman-Ford run | Bellman-Ford with shifted passes plus an exact check of every triangle; minimum profit 0.02% by default; loops of 4 or more legs are best-effort | A plain Bellman-Ford run can return a sub-threshold cycle and miss a real one; real ticks show bid/ask noise above smaller thresholds ([backend/BUGS.md](../backend/BUGS.md) B4, B8 and the threshold note; doc 06) |
| Browser push | WebSocket (`/ws/live-market`) | Server-Sent Events (`GET /api/stream`) | One-way updates; JDK only ([08 §1](./08_build_plan_and_acceptance.md#1-stack-confirmed-with-the-course-faculty)) |
| Rate source | "Live forex rates", source not specified | Python feed: Finnhub WebSocket, Frankfurter fallback, replay of a recorded capture | doc 06, doc 08 Stage 2 |
| Currency universe | "50+ currencies" (original problem statement) | 12 currencies and 132 pairs for trading; 7 currencies have live rates | The ticker benchmark's roster; OANDA has no INR pair (doc 06) |
| Accounts | Hash map + LRU cache | Same, persisted to `accounts.jsonl`; order and trade ids persisted in `counters.json` so they never repeat after a restart | doc 08 Stage 1 and Stage 3 addendum; [backend/BUGS.md](../backend/BUGS.md) B12, B13 |
| Cash model | Not specified | Cash and margin in "simulation units" (price tick × quantity); cash can go negative | A simulator, not a clearing house ([08 Appendix A](./08_build_plan_and_acceptance.md#appendix-a--matching-rules-normative)) |
