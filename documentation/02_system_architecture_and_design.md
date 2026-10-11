# System Architecture & Design Specification

## 1. End-to-End System Architecture

The system is architected as an event-driven, full-stack application composed of three decoupled layers:
1. **Interactive Frontend Client (SPA):** Visualizes live order book state, interactive currency graph topologies, and order simulation controls.
2. **High-Performance Backend API & WebSocket Gateway:** Dispatches client orders, broadcasts real-time order book snapshots, and streams detected arbitrage loops.
3. **Core Algorithmic In-Memory Engine:** Hosts all custom-built data structures operating purely in RAM for maximum throughput and minimum latency.

```
+-----------------------------------------------------------------------------------------------+
|                                        FRONTEND LAYER                                         |
|                                                                                               |
|  +---------------------------+  +---------------------------+  +---------------------------+  |
|  |    Live Order Book View   |  |   Currency Loophole Map   |  |    Simulation & Tester    |  |
|  | - Bid/Ask Ladder Visual   |  | - Force-Directed Graph    |  | - 5,000 Order Load Test   |  |
|  | - Cumulative Depth Chart  |  | - Golden Glowing Cycle    |  | - Latency & Throughput UI |  |
|  +---------------------------+  +---------------------------+  +---------------------------+  |
+-----------------------------------------------^-----------------------------------------------+
                                                |
                                      WebSocket / REST JSON
                                                |
+-----------------------------------------------v-----------------------------------------------+
|                                 BACKEND API & ROUTING LAYER                                   |
|                                                                                               |
|  - POST /api/orders (Submit Limit/Market Order)         - POST /api/market/rates (Update Rate)|
|  - DELETE /api/orders/:id (Cancel Order)                - GET  /api/arbitrage/scan (Run Scan) |
|  - GET  /api/orderbook (Get Snapshot)                   - WS   /ws/live-stream (Live Updates) |
+-----------------------------------------------^-----------------------------------------------+
                                                |
+-----------------------------------------------v-----------------------------------------------+
|                                 CORE ALGORITHMIC ENGINE (RAM)                                 |
|                                                                                               |
|  [ SUB-ENGINE A: ORDER MATCHING ]                   [ SUB-ENGINE B: ARBITRAGE DETECTOR ]      |
|                                                                                               |
|  +---------------------------------------+          +--------------------------------------+  |
|  | Radix Tree (Bids & Asks)              |          | Directed Weighted Graph              |  |
|  | - Maintains sorted price levels       |          | - Vertices: Currency codes (USD, EUR)|  |
|  | - Root/Min/Max peek: O(1)             |          | - Directed Edges: Exchange rates     |  |
|  | - Level insertion/deletion: O(1) for   |      | - Weights: w = -ln(Exchange_Rate)    |  |
|  |   fixed key width (fixed nibble depth)|          |                                      |  |
|  +-------------------+-------------------+          +-------------------+------------------+  |
|                      |                                                  |                     |
|  +-------------------v-------------------+          +-------------------v------------------+  |
|  | Doubly Linked List + Order Map        |          | Bellman-Ford Cycle Detection Engine  |  |
|  | - FIFO Order Queue per price node     |          | - Relaxes edges (V-1) times          |  |
|  | - Head execution: O(1)                |          | - Detects negative cycle in V-th pass|  |
|  | - Cancellation by order ID: O(1)      |          | - Reconstructs circular cycle path   |  |
|  +---------------------------------------+          +--------------------------------------+  |
|                                                                                               |
|  [ SUB-ENGINE C: SUPPORT & TELEMETRY ]                                                        |
|                                                                                               |
|  +---------------------------------------+          +--------------------------------------+  |
|  | Hash Map + LRU Cache (Account Cache)  |          | Huffman Coding Log Compactor         |  |
|  | - O(1) avg lookup by traderId         |          | - Compresses trade telemetry stream  |  |
|  | - LRU list evicts cold accounts       |          | - Encodes variable-length bitstrings |  |
|  +---------------------------------------+          +--------------------------------------+  |
+-----------------------------------------------------------------------------------------------+
```

---

## 2. Core Subsystems Breakdown

### Subsystem 1: Order Matching Engine (Price-Time Priority)
* **Order Book Representation:**  
  Two independent **Radix Trees**, digit-bucketed on the fixed-point price tick (`priceTick = price / tickSize`), are maintained per trading pair:
  * `bidsTree`: Max-oriented radix index (highest buy tick reached by walking the most-significant populated bucket first).
  * `asksTree`: Min-oriented radix index (lowest sell tick reached by walking the least-significant populated bucket first).
* **Price Level Node Anatomy:**
  ```
  PriceLevelNode {
      priceTick: uint64,
      children: PriceLevelNode*[RADIX_BASE],   // RADIX_BASE = 16 (nibble/4-bit decomposition)
                                                // 16 x 8 bytes = 128 bytes/node, vs. 2 KB/node at a
                                                // naive RADIX_BASE = 256 (one branch per full byte)
      childBitmap: uint16,                     // bit i set iff children[i] != null — lets best-price
                                                // peek test all 16 children in one word compare
                                                // instead of a linear scan
      orderQueueHead: OrderNode*,
      orderQueueTail: OrderNode*,
      totalVolume: double
  }
  ```
  A 32-bit `priceTick` decomposes into 8 nibbles (4 bits each), so worst-case depth is 8 levels regardless of how many price levels $P$ are active; a 64-bit tick needs 16 levels. We chose 16-ary over a byte-wise 256-ary tree specifically to bound per-node memory — see [05_data_structure_selection_rationale.md](./05_data_structure_selection_rationale.md) §1 for the full memory-vs-depth trade-off and why this matters.
* **Order Cancellation Map:**  
  A Hash Map (`orderId -> OrderNode*`) allows instant pinpointing of any active order node in the doubly linked list. Removal from the list is $O(1)$, and if the list becomes empty, the `PriceLevelNode` is pruned from its digit bucket in $O(1)$ **for a fixed key width** — this is a deterministic bound from the fixed nibble-depth, not a statistical amortization — independent of how many price levels $P$ are active, and no rebalancing is required. Locating a *single* price level this way is O(1)-for-fixed-width; walking $K$ consecutive price levels to build a depth chart is still $O(K)$, same as any ordered structure.

---

### Subsystem 2: Currency Loophole Detector (Triangular Arbitrage)
* **Graph Representation:**  
  A directed adjacency list representing all traded currency pairs:
  $$G = (V, E)$$
  Where $V = \{\text{USD, EUR, GBP, JPY, CAD, CHF, AUD, INR}, \dots\}$
  An exchange rate $R(u, v)$ for converting currency $u$ into $v$ is transformed to:
  $$w(u, v) = -\ln(R(u, v))$$
* **Arbitrage Path Reconstruction:**
  1. Run Bellman-Ford for $|V|-1$ iterations over all directed edges.
  2. In the $|V|$-th iteration, if any edge relaxes:
     $$\text{dist}[v] > \text{dist}[u] + w(u, v)$$
     a negative cycle exists.
  3. Trace the `predecessor` array backwards to isolate the exact closed loop (e.g., $\text{USD} \to \text{EUR} \to \text{JPY} \to \text{USD}$).
  4. Compute the net compound multiplier $\prod R_i$ and the percentage yield.

---

### Subsystem 3: Account Caching & Telemetry Storage
* **Hash Map + LRU Cache (Account Cache):**
  * Stores user balances and margin limits, keyed directly by `traderId` for $O(1)$ average lookup via separate chaining.
  * An LRU doubly-linked list tracks access order; on a fixed-capacity cache, the least-recently-used account is evicted first when a new account needs a slot.
  * Whenever an order is submitted by a user, their entry is moved to the front of the LRU list in $O(1)$.
  * High-frequency market makers who submit thousands of orders enjoy strict $O(1)$ average access time, with no rebalancing overhead.
* **Trie (Prefix Tree) for Tickers:**
  * Fast validation and search of currency pair tickers (e.g., `USD/EUR`, `USD/JPY`, `EUR/GBP`).
* **Huffman Coding Log Compactor:**
  * Aggregates completed trades into structured execution text chunks.
  * Builds a dynamic Huffman Tree based on character frequencies in the trade payloads and generates compressed binary payloads for archiving.

---

## 3. Data Flow Diagram: Placing a Limit Order

```
[User submits Buy Limit Order: 50 USD at $1.08]
                     |
                     v
[Backend validates balance via Splay Tree Cache (O(log N))]
                     |
                     v
[Check best Ask in asksTree (Min node in O(1))]
                     |
         +-----------+-----------+
         |                       |
[Best Ask <= $1.08?]     [Best Ask > $1.08 or asksTree empty]
         |                               |
        YES                              NO
         |                               |
         v                               v
[Execute Trade match]           [Insert price $1.08 into bidsTree (O(log P))]
[Update Ask Queue (O(1))]       [Enqueue order to $1.08 Doubly Linked List (O(1))]
[Log trade to Huffman buffer]   [Register orderId in Hash Map (O(1))]
         |                               |
         +---------------+---------------+
                         |
                         v
     [Broadcast updated orderbook via WebSocket]
```

---

## 4. API Specification

### REST Endpoints
* `POST /api/orders`
  * **Payload:** `{ "traderId": "T101", "pair": "EUR/USD", "side": "BUY"|"SELL", "type": "LIMIT"|"MARKET", "price": 1.0850, "quantity": 100 }`
  * **Response:** `{ "status": "FILLED"|"PARTIAL"|"QUEUED", "orderId": "ORD-9821", "matchedTrades": [...] }`
* `DELETE /api/orders/:orderId`
  * **Response:** `{ "status": "CANCELLED", "orderId": "ORD-9821", "refundQuantity": 100 }`
* `GET /api/orderbook/:pair`
  * **Response:** `{ "bids": [{"price": 1.0845, "volume": 500}, ...], "asks": [{"price": 1.0850, "volume": 320}, ...] }`
* `GET /api/arbitrage/scan`
  * **Response:** `{ "hasArbitrage": true, "cycle": ["USD", "EUR", "JPY", "USD"], "multiplier": 1.0184, "profitPercent": 1.84 }`
* `POST /api/simulate`
  * **Payload:** `{ "numOrders": 5000, "rateVolatility": 0.05 }`
  * **Response:** `{ "durationMs": 14.2, "ordersPerSec": 352112, "matchesExecuted": 4820, "loopholesFound": 12 }`

### WebSocket Stream (`/ws/live-market`)
* Streams real-time JSON packets:
  * `{"type": "BOOK_UPDATE", "pair": "EUR/USD", "bids": [...], "asks": [...]}`
  * `{"type": "LOOPHOLE_ALERT", "path": ["USD", "EUR", "JPY", "USD"], "profit": 1.84, "timestamp": 1726645800}`
