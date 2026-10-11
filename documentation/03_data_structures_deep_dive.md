# Data Structures Deep Dive & Algorithmic Foundations

This document details the mathematical proofs, structural invariants, and operational complexities for all custom data structures employed in the **Order Matching System with Currency Loophole Detector**.

---

## 1. Radix Tree (Price Ladder Index)

### Purpose
Maintains a digit-bucketed hierarchy of distinct price levels keyed by a fixed-point tick value (`priceTick = price / tickSize`), giving $O(1)$-for-fixed-key-width search, insertion, and deletion — the tree's depth is fixed by the number of digits in `priceTick`, **independent of** $P$, the number of active price levels. This is a **deterministic** bound (every lookup walks exactly the same number of levels), not a statistical amortization — we use the term loosely in casual speech elsewhere in this project's docs, but the precise claim is "constant depth for a fixed key width."

### Design Note: Radix Base Trade-off
A wider radix base means shallower trees but larger nodes. A byte-wise (`RADIX_BASE = 256`) node costs `256 × 8 bytes ≈ 2 KB` on a 64-bit system — real memory pressure per path, as the [Adaptive Radix Tree paper (Leis et al., ICDE 2013)](https://www.inf.ufpr.br/eduardo/ensino/ci809/papers/leis-icde2013.pdf) documents for exactly this failure mode. This project uses **`RADIX_BASE = 16`** (nibble/4-bit decomposition) instead: `16 × 8 bytes = 128 bytes` per node — still larger than a Red-Black Tree's ~56-byte node, but a deliberate, bounded trade-off rather than an unexamined one. See [05_data_structure_selection_rationale.md](./05_data_structure_selection_rationale.md) §1 for the full comparison, including where this choice is honestly a close call against Red-Black Tree and a flat tick-indexed array.

### Structural Invariants
1. Each node has up to 16 children, indexed by the next unconsumed 4-bit nibble of `priceTick`, plus a 16-bit `childBitmap` marking which children are populated.
2. Depth is bounded by `ceil(bitwidth(priceTick) / 4)` — a constant for a fixed tick width: 8 levels for a 32-bit tick, 16 levels for a 64-bit tick.
3. A node is only materialized along a path if at least one price level below it is populated; empty branches are pruned immediately, keeping memory proportional to $P$, not to the full tick space.
4. No rotations and no color/balance bookkeeping are required — correctness follows directly from the fixed digit decomposition, not from a rebalancing invariant.

```
priceTick = 10050 = 0x2742  (16-bit tick shown for clarity; nibbles: 2, 7, 4, 2)

              [ Radix Root ]  childBitmap marks which of 16 slots below are populated
               /    |    \
             0x2   0x5   0x9 ...          <- nibble 0 (most significant)
              |
             0x7 ...                       <- nibble 1
              |
             0x4 ...                       <- nibble 2
              |
             0x2 --> PriceLevelNode(10050) <- nibble 3 (leaf: order queue head/tail)
```

### Key Operations
* **Insert:** Walk the fixed nibble path from the root, creating buckets on demand, setting each level's `childBitmap` bit; attach the `PriceLevelNode` at the leaf. $O(1)$ for a fixed key width.
* **Search / Best Price Peek:** At each level, read `childBitmap` and jump to the highest-set bit (bids) or lowest-set bit (asks) via a hardware bit-scan instruction, instead of checking all 16 children individually. $O(1)$ for a fixed key width to find a single best price; walking $K$ consecutive price levels outward (for a depth chart) is $O(K)$, same as any ordered structure — the fixed-depth property bounds locating *one* key, not enumerating a range.
* **Delete:** When a price level's order queue and volume hit zero, remove the leaf, clear its parent's `childBitmap` bit, and prune now-empty buckets back up the path. $O(1)$ for a fixed key width, no rebalancing.

---

## 2. Doubly Linked List + Hash Map (Price-Time Priority Queue)

### Purpose
Implements the strict **Price-Time Priority** (First-Come, First-Served) execution rule at each price level, while supporting instantaneous order cancellation from anywhere in the queue.

### Structural Layout
Each `PriceLevelNode` holds pointers to the `head` and `tail` of a `DoublyLinkedList<Order>`. Concurrently, a global Hash Map (`orderId -> OrderNode*`) stores references to every active order in the system.

```
Global Hash Map:
  "ORD-101" ---> [ OrderNode: 100 units ] <---> [ OrderNode: 50 units ] ---> NULL
                   ^                              ^
                   | (head)                       | (tail)
             [PriceLevelNode ($100.50)]
```

### Complexity Breakdown
* **Enqueue Order:** Insert at tail in $O(1)$ time; insert key into Hash Map in $O(1)$ average time.
* **Match / Execute Order:** Pop from head in $O(1)$ time.
* **Cancel Order by ID:**
  1. Lookup `OrderNode*` in Hash Map: $O(1)$.
  2. Unlink node from Doubly Linked List (`node->prev->next = node->next; node->next->prev = node->prev`): $O(1)$.
  3. Total cancellation latency: **Strict $O(1)$**.

---

## 3. Directed Weighted Graph & Bellman-Ford (Currency Arbitrage)

### Mathematical Formulation
Let $V$ be the set of currencies ($\text{USD, EUR, GBP, JPY, INR}, \dots$) and $E$ be the set of exchange pairs.  
Let $R(u, v)$ be the conversion rate from currency $u$ to $v$.

A triangular arbitrage exists if there is a cycle of currencies $v_1, v_2, \dots, v_k, v_1$ such that:
$$R(v_1, v_2) \times R(v_2, v_3) \times \dots \times R(v_k, v_1) > 1$$

To solve this using standard shortest-path algorithms, we transform the product into a sum using logarithms:
$$\ln\left( \prod_{i=1}^k R(v_i, v_{i+1}) \right) > \ln(1) = 0$$
$$\sum_{i=1}^k \ln(R(v_i, v_{i+1})) > 0$$

Multiplying both sides by $-1$ reverses the inequality:
$$\sum_{i=1}^k -\ln(R(v_i, v_{i+1})) < 0$$

### Definition of Edge Weight
$$w(u, v) = -\ln(R(u, v))$$

> **Theorem:** Finding a sequence of currency trades with net multiplicative gain $> 1$ is mathematically equivalent to finding a **Negative Weight Cycle** in a directed graph where edge weights are $-\ln(R(u, v))$.

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
Total cycle weight: 0.0833 + 0.1508 - 0.2468 = -0.0127 < 0  ===> ARBITRAGE!
```

### Bellman-Ford Negative Cycle Detection Algorithm
1. Initialize $\text{dist}[v] = 0$ for all $v \in V$, $\text{predecessor}[v] = \text{null}$.
2. Relax all directed edges $(u, v) \in E$ exactly $|V| - 1$ times:
   $$\text{if } \text{dist}[u] + w(u, v) < \text{dist}[v] \implies \text{dist}[v] = \text{dist}[u] + w(u, v); \quad \text{predecessor}[v] = u$$
3. Perform a $|V|$-th relaxation pass. If any edge can still be relaxed, that edge is part of or reachable from a negative cycle.
4. Backtrack along the `predecessor` chain for $|V|$ steps to enter the cycle, then collect vertices until the start vertex repeats.
5. **Time Complexity:** $O(V \cdot E)$, deterministic — exactly $|V|-1$ relaxation passes regardless of input order, with no worst-case degeneration; with $|V| \approx 10\text{--}50$ currencies, execution completes in **$< 50$ microseconds**.

---

## 4. Hash Map + LRU Cache (High-Volume Active Trader Cache)

### Purpose
Optimizes user account balance and margin checks. In financial trading, a small minority of algorithmic accounts place the vast majority of orders. A **Hash Map** keyed directly by `traderId` gives every account $O(1)$ average lookup regardless of access pattern, and a companion **LRU doubly-linked list** keeps a bounded-capacity cache resident with the hottest accounts, evicting the coldest entry first when a new account needs a slot.

### Structure
* **Hash Map (separate chaining):** `traderId -> AccountNode*`, buckets resized (and entries rehashed) once load factor exceeds a threshold (e.g. 0.75) to keep chains short.
* **LRU Doubly Linked List:** every `AccountNode` also sits in a DLL ordered by recency of access; each cache hit unlinks the node and re-links it at the head in $O(1)$.

```
Hash Map:                              LRU List (head = most recent):
  "T101" ---> AccountNode(T101) <---+
  "T102" ---> AccountNode(T102)     |
  "T103" ---> AccountNode(T103)     |
                                     v
  [ T101 ] <-> [ T103 ] <-> [ T102 ] <-> ... <-> [ T099 (tail, evicted next) ]
```

### Complexity Breakdown
* **Lookup / Balance Check:** Hash Map access $O(1)$ average, $O(N)$ worst-case under pathological collisions (bounded in practice by resizing).
* **Touch (move-to-front on access):** Unlink + relink in the DLL, $O(1)$ — no rebalancing, no rotations, strictly cheaper per-operation than a Splay Tree's amortized rotation chain.
* **Eviction (cache full):** Drop the DLL tail and its Hash Map entry, $O(1)$.

---

## 5. Trie / Prefix Tree (Currency Pair & Ticker Lookup)

### Purpose
Enables instant symbol validation and autocomplete for currency pairs (`USD/EUR`, `USD/JPY`, `EUR/INR`).

### Structure
Each node contains a fixed-size alphabet array (`children['A'..'Z']` and `/`) and a boolean `isEndOfWord`.
* **Search / Insert Time:** $O(L)$ where $L$ is string length (typically $\le 7$ characters).
* **Prefix Autocomplete:** $O(L + K)$ where $K$ is the number of matching symbols.

---

## 6. Huffman Coding (Trade Telemetry Compression)

### Purpose
Financial matching engines produce massive audit logs. Huffman Coding constructs variable-length prefix codes based on character frequencies within trade batch payloads, reducing storage requirements by $40\%\text{--}60\%$.

### Construction Algorithm
1. Calculate character frequencies across trade batch strings.
2. Insert all character leaf nodes into a **Min-Heap (Priority Queue)**.
3. While the heap size $> 1$:
   * Extract two nodes with lowest frequencies ($n_1, n_2$).
   * Create an internal node with frequency $= \text{freq}(n_1) + \text{freq}(n_2)$ and children $n_1, n_2$.
   * Insert internal node back into the Min-Heap.
4. Traverse the resulting tree to assign binary prefix codes ($0 = \text{left}, 1 = \text{right}$).

---

## 7. Comparative Time & Space Complexity Summary

| Data Structure / Algorithm | Operation | Average Time | Worst-Case Time | Space Complexity |
| :--- | :--- | :---: | :---: | :---: |
| **Radix Tree** | Price Search / Insert / Delete | $O(1)$ for fixed key width | $O(1)$ for fixed key width (deterministic, not amortized) | $O(P)$ nodes, ~128 B/node at `RADIX_BASE=16` |
| **Price Level Queue (DLL)** | Enqueue Order (FIFO Tail) | $O(1)$ | $O(1)$ | $O(N)$ |
| **Order Cancel (Hash Map + DLL)**| Delete Arbitrary Order | $O(1)$ | $O(1)$ | $O(N)$ |
| **Bellman-Ford Graph** | Detect Arbitrage Cycle | $O(V \cdot E)$ | $O(V \cdot E)$ | $O(V + E)$ |
| **Hash Map + LRU Cache** | Account Margin Lookup | $O(1)$ average | $O(N)$ (pathological collisions) | $O(N)$ |
| **Prefix Trie** | Ticker Validation & Autocomplete | $O(L)$ | $O(L)$ | $O(L \cdot |\Sigma|)$ |
| **Huffman Coding** | Build Tree & Encode Payload | $O(K \log K + M)$ | $O(K \log K + M)$ | $O(K)$ |
