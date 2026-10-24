# Data Structures Deep Dive & Algorithmic Foundations

This document describes the six data structures **as built** in `backend/src/ads/`: their invariants, their operations and costs, and how the production code compares with the benchmark version it was ported from. The reasons each structure was *chosen* over the alternatives are in [05_data_structure_selection_rationale.md](./05_data_structure_selection_rationale.md). Every number below comes from a committed result file, named next to it.

None of the structure packages (`engine`, `scanner`, `vault`, `directory`, `logbook`) imports a `java.util` collection: every map, list, heap and tree is hand-written.

| Subsystem | Structure | Production class |
| :--- | :--- | :--- |
| Price ladder (one per side per pair) | B+ tree, order 32, linked leaves | `engine/BPlusTreeLadder.java` behind `engine/PriceLadder.java` |
| Order queue + cancel | Doubly linked FIFO per price level + one engine-wide hash index | `engine/PriceLevel.java`, `engine/OrderIndex.java`, `engine/Order.java` |
| Currency loophole detector | Directed weighted graph, Bellman-Ford on `-ln(rate)` + exact triangle check | `scanner/ArbitrageScanner.java`, `scanner/BellmanFord.java` |
| Account cache | Hash ledger + bounded hash/LRU cache | `vault/Vault.java`, `vault/LruCache.java` |
| Ticker lookup | Fixed-alphabet trie with child bitmap | `directory/TickerTrie.java`, `directory/Directory.java` |
| Trade-log compression | Canonical Huffman coding | `logbook/HuffmanCodec.java`, `logbook/Logbook.java` |

---

## 1. B+ Tree (Price Ladder Index)

### Purpose
Each order book has two ladders: bids (best = highest) and asks (best = lowest). A ladder holds one entry per **active price level**, keyed by the integer price tick (`priceTick = price / tickSize`). The value at each entry is a `PriceLevel`, which holds the level's tick, its aggregate resting volume, and the head and tail of the FIFO queue of orders at that price (§2).

The matching engine depends only on the `PriceLadder` interface (`lowest`, `highest`, insert level, remove level, walk K levels). Another ordered structure could therefore replace the B+ tree without touching the matching loop.

### Structure
- An in-memory B+ tree of **order 32**. Every node holds at most 32 keys, and every non-root node at least 16.
- **All entries live in the leaves.** Leaves are doubly linked in key order.
- **Internal nodes hold only separator keys.** Invariant: every key in child *i* < `keys[i]` ≤ every key in child *i+1*. After deletes a separator may be stale but is still a valid bound, as in any standard B+ tree.
- Keys are kept in primitive `long[]` arrays, so binary search inside a node touches no boxed objects.
- Each slot an entry moves out of is cleared, so a removed `PriceLevel` and its orders are never kept reachable (`backend/BUGS.md`, the Stage 0 note on stale slots).

```
                     [ 1.08510 | 1.08540 ]                  <- internal: separators only
                    /          |          \
   [1.08490 1.08500] <-> [1.08510 1.08520] <-> [1.08540 1.08550 1.08560]   <- leaves, linked
        |       |            |       |            |       |       |
   PriceLevel PriceLevel ...                                  (each: tick, volume, queue head/tail)
```

### Key Operations
* **Best price:** one root-to-leaf walk to the leftmost leaf (asks) or the rightmost leaf (bids). O(log P) for P active levels; at order 32 a tree of a million levels is four levels deep.
* **Insert a level:** descend, insert into the leaf, and split a full node (33 keys → 16 + 17), pushing a separator up. O(log P).
* **Remove a level:** delete from the leaf. An underfull node first borrows from a sibling; if it cannot, it merges with one. The root collapses when it has one child. O(log P).
* **Depth walk of K levels** (order book depth, depth chart): **one** descent, then a sequential scan along the leaf chain. O(log P + K). A binary tree needs a ceiling or floor search per level instead, O(K log P). This is the property B+ trees exist for.
* The tree changes only when a level is **created or emptied**. An order joining an existing level touches only that level's queue.

### Why not the original Radix Tree pick
The design-stage pick was a 16-ary radix tree on the tick. The benchmark lab measured it against every alternative ([RESULTS.md](../benchmark/results/RESULTS.md) Runs 12c, 13a and 14):
- On walk-workload speed it **tied** red-black.
- The B+ tree was **~9% faster** than radix on that workload: 28.40 vs 30.94 ms at seed 42 (Run 13a).
- In resident memory the B+ tree held **~29 B per live level**, radix **52–59 B** and red-black 48 (Run 14).

Radix kept only qualitative advantages: zero restructuring and less code. The ladder was therefore changed to the B+ tree before Stage 0 ([08_build_plan_and_acceptance.md §3a](./08_build_plan_and_acceptance.md#3a-decision--the-price-ladder)). The radix implementation stays in `benchmark/src/ads/bench/ladder/` with its results.

---

## 2. Doubly Linked List + Hash Index (Price-Time Priority Queue)

### Purpose
Enforces strict **price-time priority**, first in first out at each price level, and supports cancellation of any resting order by id with no scan.

### Structure (intrusive: one node, two structures)
Each resting `Order` object is at the same time:
- a node of its price level's **doubly linked FIFO queue** (`prev`/`next`, oldest at the head); and
- a node of the **engine-wide `OrderIndex`** bucket chain (`hnext`). This is one separate-chaining hash table, `orderId → Order`, covering every resting order of every pair. Its buckets are a power of two, doubled when the load exceeds 0.75.

The order also carries a `level` pointer back to its `PriceLevel`. Because the links live inside the order, indexing an order allocates nothing beyond table growth.

```
OrderIndex (one, engine-wide)          PriceLevel 1.08510 (asks, EUR/USD)
  bucket 3 -> Order#101 -> Order#57      head -> Order#57 <-> Order#101 <-> Order#140 <- tail
  bucket 9 -> Order#140                           ^ executes first (oldest)
```

### Operations
* **Enqueue (new resting order):** append at the tail, O(1); insert into the index, O(1) on average.
* **Match:** take from the head, O(1). `PriceLevel.fill` is the only code that changes `remaining`, and it updates the level's aggregate volume in the same step, so the two cannot drift apart.
* **Cancel by id:**
  1. Index lookup, O(1) on average.
  2. Follow `order.level` to the queue and unlink, O(1).
  3. If the level is now empty, remove it from the ladder, O(log P).

  The hash lookup is O(1) on average and O(N) only under pathological collisions. Resizing at 0.75 keeps chains short.

---

## 3. Directed Weighted Graph & Bellman-Ford (Currency Loophole Detector)

### Mathematical Formulation
Let $V$ be the set of currencies and $R(u, v)$ the units of $v$ that one unit of $u$ buys. A loophole is a cycle $v_1 \to v_2 \to \dots \to v_k \to v_1$ with
$$R(v_1, v_2) \times R(v_2, v_3) \times \dots \times R(v_k, v_1) > 1 .$$
Taking logarithms and negating turns the product into a sum:
$$\sum_{i=1}^k -\ln R(v_i, v_{i+1}) < 0 .$$

With edge weight $w(u,v) = -\ln R(u,v)$, a profitable conversion loop is exactly a **negative-weight cycle**.

```
      USD
     /   ^
    /     \  -ln(1.28) = -0.2468
   /       \
-ln(0.92)   GBP
= +0.0833    ^
 /           |
v            | -ln(0.86) = +0.1508
EUR ---------+
Total cycle weight: 0.0833 + 0.1508 - 0.2468 = -0.0127 < 0  ===> loophole
```

### The graph as built
- **Rates.** A tick `EUR_USD 1.0852` sets the direct edge EUR→USD and its reciprocal USD→EUR. Every other pair is **derived through USD** when both USD legs are known: $R(a,b) = R(\text{USD},b) / R(\text{USD},a)$. A direct rate is never overwritten by a derived one. Unknown rates are NaN and have no edge.
- **Validity.** Every direct quote carries an expiry: 30 s for a live tick, 2 h for a fallback snapshot. A scan leaves out expired quotes and every cross derived from them, so a feed that stops makes alerts clear instead of freezing them.
- **Size.**
  - The Directory knows 12 currencies (132 directed edges when every pair is known).
  - The live feed streams the 21 pairs among 7 of them: USD, EUR, GBP, JPY, AUD, CAD and CHF (`feed/symbols.json`).
  - INR, CNY, HKD, SGD and NZD come only from the daily fallback ([06_market_data_ingestion_service.md](./06_market_data_ingestion_service.md)).

### The scan (`ArbitrageScanner.scan`)
1. **Bellman-Ford** (`BellmanFord.java`). Every vertex starts at distance 0, as if a virtual source connected to all of them, so a cycle anywhere is found. Relax all E edges V−1 times, then one more pass; an edge that still relaxes leads into a negative cycle, which `CycleTrace` walks back to recover. The work is the same whatever the input order.
2. **Early exit.** The first pass runs on the plain weights. If it finds no negative cycle, no shifted pass can find one either, so the scan stops after one Bellman-Ford run.
3. **Threshold-shifted passes.** A cycle counts only if its exact product reaches `1 + threshold` (default 0.02%, chosen by the threshold study in doc 06). Bellman-Ford returns *some* negative cycle, not the best one, so a sub-threshold cycle could hide a real one (`backend/BUGS.md` B4). Passes L = 2 … V+1 therefore raise every weight by ln(1+t)/L, and the scan keeps the most profitable returned cycle that passes the exact check.
4. **Exact triangle check.** The project's definition of a loophole is **triangular**. Every directed triangle is checked exactly (440 at V = 12), so any triangle at or above the threshold is always reported. Longer cycles keep only the Bellman-Ford guarantee. This is the documented limitation B8, accepted in Stage 4: loops of 5 to 7 legs near the threshold are bid/ask noise, not tradeable.

**Cost:** O(V·E) for one Bellman-Ford run. In the worst case (a cycle present) it is (V+1) runs, plus one pass over the triangles. With V currencies there are E = V(V−1) edges.

**Measured** (`backend/test.sh --scan-timing`, [results/scan_timing.txt](./results/scan_timing.txt), median per scan, µs):

| V (edges) | what it is | no cycle | +0.4% cycle | sub-threshold cycle |
| :--- | :--- | --: | --: | --: |
| 7 (42) | the live feed's currencies | 0.86 | 4.28 | 4.30 |
| 12 (132) | the Directory's universe (live + fallback) | 3.16 | 24.36 | 24.98 |
| 20 (380) | doc 04's stress size | 11.61 | 160.33 | 173.22 |

The early exit is why the no-cycle column is so much cheaper. The shifted passes are why a cycle multiplies the cost by roughly V. The earlier claim that a scan of 10–50 currencies completes "in < 50 µs" was never measured; it holds up to V = 12 and is false at V = 20 with a cycle present (see doc 04 §3B).

---

## 4. Hash Ledger + LRU Cache (Trader Accounts)

### Purpose
Every order needs a margin check, and every trade changes two balances. A small set of active traders places most of the orders.

### Structure (`Vault`)
* **Ledger.** Every account lives in a separate-chaining hash table (chains through `Account.ledgerNext`) plus an array kept in opening order. It is persisted as JSON Lines (`accounts.jsonl`). A flush writes a temp file, forces it to disk and renames it over the old one, so a crash leaves the old file or the new one, never a torn file.
* **Hot path.** `LruCache` is a separate-chaining hash map whose nodes also thread an intrusive most-recently-used list, with a capacity of 1,024. A hit touches the node: unlink, then relink at the head, O(1). A miss loads from the ledger, inserts at the head and evicts the tail once over capacity, O(1). Cache and ledger hold the **same** `Account` objects, so an eviction never loses a balance.
* **Bounds** (Stage 6 addendum): at most 1,000 accounts, each opening with `cash` and `marginLimit` ≤ 10¹⁵. Total cash therefore cannot exceed 10¹⁸, and no 64-bit balance can overflow. The loader refuses a file that breaks the bounds.

### Complexity
Lookup and settle are O(1) on average and O(N) only under pathological collisions. Touch and evict are O(1).

---

## 5. Trie (Currency Pair Directory and Autocomplete)

### Structure (`TickerTrie`)
* A fixed alphabet of 27 characters, `A`–`Z` and `/`. Every node holds a 27-slot child array plus a **child bitmap** of the slots that are present.
* Navigation is one array index per character, with no comparisons.
* Prefix search walks to the prefix node and collects its subtree, visiting only the present children through the bitmap.
* A character outside the alphabet means "no match" for a lookup and is rejected on insert (`backend/BUGS.md` B1).
* Every registered pair gets a dense id (0, 1, 2, … in insertion order), stored at its terminal node. The engine keeps its books in an **array indexed by pair id**, so the trie is also the pair → book map.
* The Directory registers all 132 ordered pairs of the 12 currencies (`EUR/USD`, `USD/EUR`, …). Each pair's tick size is 10⁻³ when the quote currency is JPY and 10⁻⁵ otherwise.

### Complexity
* Exact lookup: O(L) for a string of length L (7 for a pair).
* Prefix search: O(L + K) for K matches, capped by the caller's buffer.

---

## 6. Huffman Coding (Trade Log Compression)

### Purpose
Every trade is appended to the Logbook as one text line:

```
tradeId,seq,pair,priceTick,quantity,buyOrderId,sellOrderId,buyerId,sellerId,takerSide
```

The buffer is **rotated** into the archive:
* every 500 trades;
* on a 5 s timer if it is not empty (the Stage 6 bound on the crash-loss window);
* at shutdown.

Each rotation Huffman-encodes the batch into the next segment file, `trades-000001.huf`, `trades-000002.huf` and so on (temp file, fsync, rename).

### Construction (`HuffmanCodec`, static order-0, canonical codes)
1. Count byte frequencies in the batch.
2. Build the Huffman tree with a hand-written **min-heap**: repeatedly merge the two lowest-frequency nodes.
3. Read off each byte's **code length**, then assign **canonical** codes from the lengths alone.
4. The stream is `[4-byte original length][256 one-byte code lengths][MSB-first packed body]`. The decoder rebuilds identical codes from the length table, so no tree is transmitted.
5. The decoder validates everything: header present, lengths forming a valid prefix code, body long enough. It throws on a corrupt or truncated segment instead of returning wrong bytes (`backend/BUGS.md` B2).

**Measured.**
* The earlier "40–60% smaller" claim is replaced by the measured ratio of 0.5874, i.e. 41% smaller, on the lab's 5.5 MB trade-log workload (encoded / original). The source is [RESULTS.md](../benchmark/results/RESULTS.md) Run 15, which re-measured the Run 9 figure on the finished code.
* `run.sh archive` decodes every segment back to the exact bytes that were buffered, which `scripts/logbook_check.sh` checks against the API's trade list ([results/drill_logbook_check.txt](./results/drill_logbook_check.txt)).

---

## 7. Complexity Summary (as built)

| Structure | Operation | Average | Worst case | Space |
| :--- | :--- | :---: | :---: | :---: |
| **B+ tree ladder** (order 32) | best price; insert/remove a level | $O(\log P)$ | $O(\log P)$ | $O(P)$, ~29 B per level (Run 14) |
| | walk K levels of depth | $O(\log P + K)$ | $O(\log P + K)$ | — |
| **Level queue (DLL)** | enqueue at tail; match at head | $O(1)$ | $O(1)$ | $O(N)$ |
| **Cancel (OrderIndex + DLL)** | remove any resting order | $O(1)$ + $O(\log P)$ if the level empties | $O(N)$ (pathological collisions) | $O(N)$ |
| **Scanner (Bellman-Ford + triangles)** | full scan | $O(V \cdot E)$ with no cycle | $O(V^2 \cdot E + V^3)$ with a cycle | $O(V + E)$ |
| **Vault (hash ledger + LRU)** | margin check, settle | $O(1)$ | $O(N)$ (pathological collisions) | $O(N)$ |
| **Trie** | validate; autocomplete | $O(L)$; $O(L + K)$ | same | $O(\text{nodes} \cdot 27)$ |
| **Huffman** | build + encode a batch of M bytes | $O(M + 256 \log 256)$ | same | $O(256)$ table |

---

## 8. Production versus benchmark

### What changed after Stage 0
* **The ladder is now a B+ tree** (order 32, linked leaves), not the radix tree of the original design. It changed for the measured reasons in §1. The swap happened before any production code was written, behind a `PriceLadder` interface.
* **Every other pick stands.** The DLL + hash index, Bellman-Ford, hash + LRU, trie and Huffman are the same structures the benchmark selected.
* **The changes from the benchmark versions are limited to what production needs:**
  - each ladder leaf holds a `PriceLevel` (with its queue) instead of a volume;
  - the queue's index became one **engine-wide** index with the links inside the order node;
  - the trie assigns dense pair ids and rejects out-of-alphabet input (B1);
  - the Huffman decoder validates its input (B2);
  - the cache takes a loader;
  - the scanner adds validity expiry, threshold-shifted passes and the exact triangle check (B4, B8).

### Parity and timing (final run)
`backend/test.sh` runs each production structure through a thin adapter against the benchmark's **own** correctness gate and oracle, on the same seeded workloads. All six parity gates pass on the final code: [results/parity_gates.txt](./results/parity_gates.txt), 207 tests passed and 0 failed.

`backend/test.sh --timing` times production and benchmark versions in the same JVM, interleaved, with 7 reps after 3 warmups ([results/parity_timing.txt](./results/parity_timing.txt)):

| workload | benchmark ms | production ms | delta | bench B/op | prod B/op |
| :--- | --: | --: | --: | --: | --: |
| ladder walk n=300000 | 24.43 | 25.30 | +3.6% | 1.1 | 42.7 |
| ladder wide range (16,777,216 ticks) | 24.50 | 28.39 | +15.9% | 1.1 | 42.7 |
| ladder sorted n=12000 | 0.6778 | 0.7665 | +13.1% | 20.3 | 78.8 |
| queue shallow n=300000 | 7.98 | 8.78 | +10.0% | 20.2 | 40.2 |
| queue deep n=300000 | 8.76 | 11.91 | **+35.9%** | 20.9 | 40.9 |
| cache n=300000, cap 10,000, Zipf 1.0 | 10.42 | 12.66 | +21.6% | 11.2 | 17.7 |
| arbitrage real (V=8, per scan) | 0.0005 | 0.0006 | +13.0% | 3.0 | 3.3 |
| arbitrage synthetic V=8 with cycle (per scan) | 0.0005 | 0.0005 | +2.1% | 3.0 | 3.3 |
| arbitrage synthetic V=50 (per scan) | 0.1053 | 0.1040 | −1.3% | 0.3 | 0.3 |
| ticker exact n=300000 | 8.96 | 6.20 | −30.8% | 0.2 | 0.2 |
| ticker prefix n=300000 | 32.92 | 26.36 | −19.9% | 0.2 | 0.2 |
| Huffman encode (5,505,087 B) | 62.41 | 59.84 | −4.1% | 2.09 | 2.09 |
| Huffman decode | 71.00 | 77.80 | +9.6% | – | – |

**Reading the table.**
* Doc 08 asks for any regression above 25% to be explained. Only the deep queue exceeds it, at +35.9%.
* The production queue node is a full `Order`: id, pair, side, trader, tick, sequence, remaining, level pointer, two queue links and an index link. The benchmark node holds only an id, a volume and the links. The production queue therefore allocates twice as much per operation (40.9 vs 20.9 B/op). On the deep profile about 15,000 orders are live at once, so the larger nodes cost more cache misses.
* The extra fields are what let a cancel reach its level and the matching loop settle a trade, so the cost is inherent to the engine. It is not a defect of the port.
* The production ladder allocates more per operation (42.7 vs 1.1 B/op) because each new level allocates a `PriceLevel` object. The benchmark stores a bare `long` volume.
* The arbitrage rows time the plain Bellman-Ford adapter. The production scan's full cost, with the shifted passes and triangles, is the §3 table.
