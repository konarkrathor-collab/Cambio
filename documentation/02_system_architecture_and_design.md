# System Architecture & Design Specification

> **Status.** This page describes the system as built (doc 08 Stage 7). The original design sketch had one process with a WebSocket server and a radix-tree price ladder. The built system is a **Java** backend, a separate **Python** feed that only fetches and forwards rates, and a **React** dashboard that receives updates by **Server-Sent Events**. The price ladder is a **B+ tree**. Where the original design differs, the text says *proposed: …; built: …* and gives the reason. The API is specified, with captured examples, in [09_api_contract.md](./09_api_contract.md); the matching rules in [08 Appendix A](./08_build_plan_and_acceptance.md#appendix-a--matching-rules-normative).

## 1. End-to-End System Architecture

The system has three processes:
1. **Dashboard (`frontend/`).** A React single-page application built with Vite. It shows the order book, the currency graph, the simulator and the structure inspector. It calls the REST API with `fetch` and keeps one shared `EventSource` on `GET /api/stream` for live updates.
2. **Backend (`backend/`).** One Java 17 process with no external libraries. The gateway (`ads.api.App` and `ApiServer`) runs on the JDK's built-in HTTP server (`com.sun.net.httpserver`). Every command and every read of engine state runs on **one engine thread** (`EngineThread`), which owns the matching engine, the scanner and the stores. The backend can also serve the built dashboard on the same port (`serve --static-dir`, `StaticFiles`).
3. **Market-data feed (`feed/feed.py`).** A Python process that fetches rates from Finnhub (or Frankfurter as a fallback, or a recorded capture in replay mode) and forwards them to `POST /api/market/rates`. It computes nothing: cross-rates, staleness and arbitrage are all computed in Java.

```
 +------------------------------------------------------------------------------------+
 |  DASHBOARD  (React + Vite; d3-force / d3-scale / d3-shape / d3-selection)          |
 |  Top bar (source badge, health, stream state, seq)                                 |
 |  Market | Arbitrage | Simulator | Inspector tabs        DEMO drawer                |
 |  api.js (fetch wrapper)        stream.js (ONE shared EventSource)                  |
 +--------------+---------------------------------------------^-----------------------+
                |  REST / JSON                                 |  Server-Sent Events
                |  (dev: Vite proxy 5173 -> 8080;              |  GET /api/stream
                |   demo: same port via --static-dir)          |  BOOK_UPDATE, TRADE,
                v                                              |  LOOPHOLE_ALERT, RATE_TICK
 +-------------------------------------------------------------+----------------------+
 |  JAVA BACKEND   (one JVM, JDK only)                                                |
 |                                                                                    |
 |  ApiServer (com.sun.net.httpserver, pool of HTTP threads)                          |
 |   - parses and validates the request (malformed -> 400) ............. HTTP thread  |
 |   - StaticFiles: dashboard for every non-/api path (optional)                      |
 |   - SseHub: one bounded queue per stream client; a full queue drops the client     |
 |        |  thread.call(...)                               ^ publish(event)          |
 |        v                                                 |                         |
 |  EngineThread  (ONE thread: every command and every read of engine state)          |
 |   +----------------------------+   +--------------------------------------------+  |
 |   | MatchingEngine             |   | MarketService -> ArbitrageScanner          |  |
 |   |  OrderBook per pair (array |   |  rate matrix, direct + USD-derived crosses |  |
 |   |  by Directory pair id):    |   |  quote validity 30 s live / 2 h fallback   |  |
 |   |   bids: BPlusTreeLadder    |   |  Bellman-Ford (shifted passes)             |  |
 |   |   asks: BPlusTreeLadder    |   |  + exact check of every triangle           |  |
 |   |   leaf -> PriceLevel (DLL) |   |  scan throttled to one per 500 ms          |  |
 |   |  OrderIndex (engine-wide)  |   +--------------------------------------------+  |
 |   +----------------------------+                                                   |
 |   | Vault (hash ledger + LRU)  |   Directory: TickerTrie, 12 currencies, 132 pairs |
 |   | Logbook (Huffman segments) |   Trade ring: last 500 trades (GET /api/trades)   |
 |   +----------------------------+                                                   |
 |                                                                                    |
 |  SimulationRunner thread: POST /api/simulate, ISOLATED engine + naive baseline     |
 +------+-------------------------------------------------------------^---------------+
        |  timed flushes (engine thread)                              |
        v                                                             | POST /api/market/rates
 +----------------------------------------------+     +---------------+---------------+
 |  DISK  (--data-dir, default backend/data)    |     |  PYTHON FEED  (feed/feed.py)  |
 |   logbook/trades-NNNNNN.huf  (written first) |     |   Finnhub WebSocket (OANDA)   |
 |   accounts.jsonl             (Vault)         |     |   -> Frankfurter snapshot     |
 |   counters.json   (order/trade ids, seq)     |     |      after 15 s down          |
 +----------------------------------------------+     |   --replay FILE (replay:true) |
                                                      +-------------------------------+
```

*Proposed: one gateway with a WebSocket endpoint `/ws/live-market`, and rates posted to `POST /api/market/rates` from a source the design did not specify. Built: Server-Sent Events, because all live traffic flows from server to browser and the JDK's HTTP server can serve SSE without a library; and a separate Python feed, because the stack was fixed as Java for computation, Python for data acquisition only, React for the dashboard ([08 §1](./08_build_plan_and_acceptance.md#1-stack-confirmed-with-the-course-faculty)).*

### Threads

| Thread | What runs on it |
| :--- | :--- |
| HTTP threads (`ApiServer`) | Request parsing and validation, JSON encoding, static files. They never touch engine state; they hand each command to the engine thread with `EngineThread.call` and wait for the answer. |
| Engine thread (`EngineThread`) | Every order, cancel and read of the books, accounts, scanner and status, in arrival order; the throttled scans; the periodic Vault, counter and Logbook flushes. One thread mutates the engine, so the engine needs no locks and the same command log always gives the same trades ([08 §3](./08_build_plan_and_acceptance.md#3-rules-for-everyone-writing-code-here), rule 9). |
| Stream clients (`SseHub`) | The HTTP thread serving each open `GET /api/stream` drains that client's bounded queue to the socket. The engine thread only *offers* events to the queues and never waits. A separate reaper thread closes the socket of a dropped client ([backend/BUGS.md](../backend/BUGS.md) B16). |
| Simulator thread (`SimulationRunner`) | `POST /api/simulate`, one at a time (409 `SIMULATION_RUNNING` otherwise), on its own Directory, Vault and books, with no Logbook and no event stream. It never touches the live state. |

How to start everything: `start-all.sh` at the repository root (backend with `--static-dir` and `--seed-demo-accounts`, then the feed, live if `FINNHUB_API_KEY` is set and a labelled replay otherwise). The `serve` options are listed in [backend/README.md](../backend/README.md).

---

## 2. Core Subsystems Breakdown

### Subsystem 1: Order Matching Engine (`ads.engine`)

* **Rules.** Price-time priority, fills at the resting order's price, the margin rule and the validation precedence are fixed in [08 Appendix A](./08_build_plan_and_acceptance.md#appendix-a--matching-rules-normative) and implemented verbatim by `MatchingEngine`. Prices are exact decimals at the API and integer ticks (`long`) inside the engine. The conversion (`ads.api.Prices`) uses exact decimal arithmetic; a price that is not a whole number of ticks is rejected `BAD_PRICE`, never rounded. Nothing in the engine package uses floating point.
* **Books.** One `OrderBook` per pair, held in an array indexed by the Directory's dense pair id (no map lookup on the hot path). Each book has a bid ladder (best = highest) and an ask ladder (best = lowest).
* **Price ladder: B+ tree of order 32 (`BPlusTreeLadder`).** All (price tick, `PriceLevel`) entries live in the leaves, which are doubly linked in price order; internal nodes hold separator keys only. A node holds at most 32 keys and, unless it is the root, at least 16.
  * Best bid or best ask (`highest()`, `lowest()`): one root-to-leaf descent, $O(\log P)$ for $P$ active levels. The best level is not cached; the descent is short because each node fans out up to 32 ways.
  * Insert or remove a level (`getOrCreate`, `remove`): $O(\log P)$, with splits on insert and borrow or merge on delete.
  * Depth for $K$ levels (`walkUp`, `walkDown`): one descent, then a scan along the leaf chain, $O(\log P + K)$.
  * The engine reaches the ladder only through the `PriceLadder` interface, so another ordered structure can replace it without touching the matching loop.

  *Proposed: a 16-ary radix tree over the price tick, with $O(1)$ level operations for a fixed key width and a child bitmap for the best-price peek. Built: the B+ tree. The benchmark lab found the radix tree tied with the balanced trees on speed and robust to wide tick ranges, but it held roughly twice the resident memory per level of the B+ tree ([benchmark/results/RESULTS.md](../benchmark/results/RESULTS.md) Runs 10–14). The decision and its reasoning are in [08 §3a](./08_build_plan_and_acceptance.md#3a-decision--the-price-ladder); the structure-by-structure comparison is in [05_data_structure_selection_rationale.md](./05_data_structure_selection_rationale.md) §1.*
* **Order queue per level: doubly linked list (`PriceLevel`).** Each level holds its tick, its total resting volume, an order count, and the head and tail of a FIFO list of `Order` nodes (oldest at the head). The matching loop fills the head in $O(1)$; a resting remainder is appended at the tail in $O(1)$. Every change to an order's remaining quantity goes through one `PriceLevel` method that also updates the level's volume, so the two cannot drift apart.
* **Cancel index: one engine-wide hash table (`OrderIndex`).** `orderId → Order`, for every resting order of every pair, because `DELETE /api/orders/{id}` carries no pair. It uses separate chaining through the orders themselves (an intrusive `hnext` field), so indexing allocates nothing beyond table growth; the table doubles above load 0.75. A cancel is a hash lookup and an unlink, $O(1)$ on average; if the level becomes empty it is removed from its ladder in $O(\log P)$.
* **Accounts at match time.** The margin check and each settlement read the account through the Vault's LRU cache (Subsystem 3). If a fill would push a resting order's balance outside the range of a 64-bit integer, that resting order is withdrawn instead of filled ([backend/BUGS.md](../backend/BUGS.md) B14).

### Subsystem 2: Currency Loophole Detector (`ads.scanner`, `ads.api.MarketService`)

* **Rate store.** `ArbitrageScanner` keeps a rate matrix over the Directory's 12 currencies: `rate[from][to]` = units of `to` per one `from`. A feed tick sets both directions (the reverse is the reciprocal) and marks them direct. Every other pair is derived through USD when both USD legs are known, $R(a,b) = R(\text{USD},b) / R(\text{USD},a)$; a derived rate never overwrites a direct one. In live mode only 7 currencies have rates, because OANDA streams no INR pair; the other currencies are dimmed in the dashboard ([06_market_data_ingestion_service.md](./06_market_data_ingestion_service.md) §4, [feed/README.md](../feed/README.md)).
* **Validity.** A live quote may be used for 30 s after it arrives, a fallback snapshot's quotes for 2 h (`MarketService`). Expired quotes, and every cross derived from one, are left out of the scan, so a feed that stops makes its alerts stop instead of freezing them. When the source switches between live and fallback, the store is cleared first, so the two never meet in one graph.
* **Graph.** Each known rate $R(u,v)$ becomes a directed edge with weight $w(u,v) = -\ln(R(u,v))$, so a loop whose rates multiply to more than 1 is a negative cycle.
* **Scan.**
  1. One Bellman-Ford run (`BellmanFord`) on the plain weights: $|V|-1$ relaxation passes over all edges from a virtual source, then one more pass to find an edge that still relaxes. If none does, there is no profitable loop and the scan stops: $O(V \cdot E)$.
  2. Otherwise, shifted passes: the $L$-th run raises every weight by $\ln(1+t)/L$ for $L = 2, \dots, V+1$, where $t$ is the minimum profit, so that a cycle just below the threshold cannot hide a real one ([backend/BUGS.md](../backend/BUGS.md) B4). Each returned cycle is traced back through the predecessor array (`CycleTrace`), and its exact multiplier $\prod R_i$ is checked against $1 + t$.
  3. An exact check of every directed triangle. The scan reports the more profitable of the best Bellman-Ford cycle and the best triangle at or above the threshold.

  So every triangle at or above the threshold is always reported. Loops of 4 or more legs are reported when the Bellman-Ford passes find them and may otherwise be missed; this is an accepted limitation ([backend/BUGS.md](../backend/BUGS.md) B8). The minimum profit defaults to 0.02%, chosen from a study of the committed real capture, and can be changed with `serve --min-profit` ([backend/README.md](../backend/README.md), doc 06).
* **Throttle and events.** `MarketService` runs on the engine thread. It scans at most once per 500 ms (a burst of ticks schedules one trailing scan), publishes `RATE_TICK` at most 4 times per second per pair, and publishes `LOOPHOLE_ALERT` only when a scan newly finds a cycle or loses one.

*Proposed: one Bellman-Ford run per scan, $O(V \cdot E)$. Built: the shifted passes and the triangle check above, because one run returns some negative cycle rather than the most profitable one, and on real bid/ask ticks it missed most cycles near the threshold.*

### Subsystem 3: Accounts, Directory and Trade Archive

* **Vault (`ads.vault`).** Two layers. The ledger holds every account in a hand-written separate-chaining hash table (plus an array in opening order). In front of it, `LruCache` is a hash map whose nodes also form a most-recently-used list: a hit moves the entry to the front in $O(1)$, and the least recently used entry is evicted when the cache is full. The engine's margin checks and settlements go through the cache; cached and ledger entries are the same objects, so an eviction never loses a balance. The ledger is persisted to `accounts.jsonl`, rewritten atomically (temporary file, `fsync`, rename). The API limits account creation so that no balance can overflow ([09 §3](./09_api_contract.md#3-endpoints), accounts).
* **Directory (`ads.directory`).** `TickerTrie` is a fixed-alphabet trie (A–Z and `/`) with a child bitmap per node. It holds the 12-currency, 132-pair universe and gives each pair a small dense id, which the engine uses as an array index. It validates pair names (an unknown pair is rejected `UNKNOWN_PAIR` before it reaches a book) and answers prefix searches for the dashboard's pair picker (`GET /api/tickers`). The Directory also holds each pair's tick size.
* **Logbook (`ads.logbook`).** Each trade is appended in memory as one text record while the order is matched; no file I/O happens inside the matching loop. Between commands, and on timers, the buffered batch is Huffman-coded (`HuffmanCodec`: byte frequencies, a tree built greedily from a min-heap, canonical codes) and written as the next segment file `logbook/trades-NNNNNN.huf` (temporary file, `fsync`, rename). `run.sh archive` decodes the segments back to the exact bytes that were buffered.

### Subsystem 4: Simulator (`ads.sim`)

`POST /api/simulate` generates a seeded order stream (`OrderGenerator`) and replays it through a fresh `MatchingEngine` on its own Directory and in-memory Vault, timing each submit and cancel inside the engine (no HTTP in the measurement). Optionally the same stream is replayed through `NaiveMatcher`, a baseline that keeps each pair's resting orders in a plain array and scans it on every order. The two must agree on every trade and every trader's final cash (`checksumMatch`). There are two profiles: `shallow` (the default; a narrow band where orders cross constantly, so books stay small) and `deep` (most orders rest, so books grow to thousands of orders). Request and response fields are in [09 §3.12](./09_api_contract.md#312-post-apisimulate-additive-stage-4); the measured results, including the shallow case where the baseline is as fast as the engine, are in [backend/BUGS.md](../backend/BUGS.md) B11.

### Subsystem 5: Market-Data Feed (`feed/`)

Specified in [06_market_data_ingestion_service.md](./06_market_data_ingestion_service.md); run instructions in [feed/README.md](../feed/README.md).

* **Live:** a Finnhub WebSocket subscribed to the OANDA symbols listed in `feed/symbols.json`; ticks are batched and forwarded as `source: "finnhub-ws"`. On a disconnect it reconnects with backoff and re-subscribes.
* **Fallback:** once the socket has been down for 15 s, it forwards a Frankfurter snapshot (`source: "frankfurter-fallback"`, USD-based daily reference rates) until the socket returns.
* **Replay:** `--replay FILE` forwards a recorded capture with `"replay": true`, so the backend reports the source as a replay and the dashboard never shows it as live.

The payload shapes are in [08 Appendix B](./08_build_plan_and_acceptance.md#appendix-b--feed--backend-payloads).

### Subsystem 6: Dashboard (`frontend/`)

Vite + React (JavaScript) with the d3 modules `d3-force`, `d3-scale`, `d3-shape` and `d3-selection`; no UI or CSS framework and no request except to the backend. Tabs: **Market** (pair picker with Trie search, order book ladder, depth chart, order ticket, own resting orders with cancel, trades feed, accounts), **Arbitrage** (the currency graph with the cycle highlighted, scanner status, recent alerts), **Simulator** (engine versus baseline, with the doc 04 targets marked met or missed), **Inspector** (levels, queue heads and the B+ tree's shape), and a **DEMO** drawer (seed crossing orders; inject a loophole sent as a replay). Details in [frontend/README.md](../frontend/README.md).

---

## 3. Data Flow Diagram: Placing a Limit Order (as built)

Example: trader 101 sends a BUY LIMIT for 50 EUR/USD at 1.08500.

```
[Browser: POST /api/orders {"traderId":101,"pair":"eur/usd","side":"BUY",
                            "type":"LIMIT","price":"1.08500","quantity":50}]
                     |
                     v
[HTTP thread: parse JSON; unknown field, wrong type, missing price -> 400;
 upper-case the pair; keep the price as an exact decimal]
                     |  EngineThread.call(...)
                     v
[Engine thread: Directory trie -> pair id (unknown -> no id);
 exact decimal -> integer ticks (not a whole tick -> no valid tick)]
                     |
                     v
[MatchingEngine.submit: take the next order id and seq;
 validate in Appendix A order; the first failure is REJECTED with its reason:
 UNKNOWN_PAIR, BAD_QUANTITY, BAD_PRICE, NO_LIQUIDITY, MARGIN
 (margin check reads the account through the Vault's LRU cache, O(1) avg)]
                     |
                     v
[Best ask = asks.lowest()  (B+ tree descent, O(log P))]
                     |
         +-----------+-------------------------+
         |                                     |
[best ask <= limit tick]            [no ask, or best ask > limit tick]
         |                                     |
         v                                     |
[Fill the head order of that level (O(1)),     |
 at the RESTING order's price;                 |
 settle cash between buyer and seller (Vault); |
 append the trade to the Logbook buffer (RAM); |
 a filled maker leaves the list and OrderIndex;|
 an emptied level is removed (O(log P));       |
 repeat while quantity remains and the next    |
 best ask still crosses]                       |
         |                                     |
         +------------------+------------------+
                            |
                            v
[Remainder > 0?  yes: bids.getOrCreate(tick) (O(log P)), append at the tail of the
 level's list (O(1)), OrderIndex.put (O(1) avg).  Status FILLED / PARTIAL / QUEUED]
                            |
                            v
[App, still on the engine thread: record trades in the recent-trade ring;
 SseHub.publish TRADE for each fill, then BOOK_UPDATE (top-10 snapshot of the pair);
 rotate the Logbook if a batch is due; renew the id reserve in counters.json if needed]
                            |
                            v
[HTTP thread: 200 {"orderId":"ORD-<n>","status":...,"trades":[...],"seq":...}]
```

A well-formed order that the engine refuses is still HTTP 200, with `status: "REJECTED"` and a reason; only a malformed request is a 400. A MARKET order follows the same path without the limit test, and its unfilled remainder is cancelled instead of resting. Cancel (`DELETE /api/orders/{id}`) takes the order from `OrderIndex`, unlinks it from its level, removes the level if it is now empty, and publishes `BOOK_UPDATE`. The response shapes are in [09_api_contract.md](./09_api_contract.md) §3.1 and §3.2.

### Rates and scans

```
[feed.py] --POST /api/market/rates--> [HTTP thread: parse] --call--> [engine thread: MarketService]
     -> clear the store if the source changed (live <-> fallback)
     -> update direct rates and validity; derive crosses through USD
     -> RATE_TICK (at most 4 per second per pair)
     -> scan now, or one trailing scan at the end of the 500 ms window
     -> LOOPHOLE_ALERT only when a cycle appears or disappears
A timer re-scans at the instant the next quote expires (a one-second sweep is the backstop),
so a dead feed clears its alert (backend/BUGS.md B17).
```

---

## 3a. The Event Stream (`GET /api/stream`)

`SseHub` implements Server-Sent Events on the JDK HTTP server. The engine thread formats each event once and offers it to every client's bounded queue without blocking; the HTTP thread serving each client writes it to the socket.

| Event | Sent when |
| :--- | :--- |
| `BOOK_UPDATE` | After an order or cancel that changed a pair's book (a trade, a resting remainder or a cancel); carries that pair's top-10 snapshot |
| `TRADE` | For each fill, before that order's `BOOK_UPDATE` |
| `LOOPHOLE_ALERT` | When a scan newly finds a cycle, or loses one |
| `RATE_TICK` | When a rate arrives, rate-limited per pair |

Every event has an increasing `id:`. The stream sends `retry:` first and a comment heartbeat when idle. A client whose queue fills (slow, or not reading at all) is dropped, so a stalled browser can never block matching; `EventSource` then reconnects. Missed events are not replayed, so the dashboard refetches its snapshots (book, trades, status, scan, graph, accounts) every time the stream opens. Limits and exact payloads: [09 §4](./09_api_contract.md#4-event-stream-get-apistream).

*Proposed: a WebSocket stream `/ws/live-market` with `BOOK_UPDATE` and `LOOPHOLE_ALERT`. Built: SSE with four event types.*

---

## 3b. Persistence and Crash Safety

| File (in `--data-dir`) | Contents | When it is written |
| :--- | :--- | :--- |
| `logbook/trades-NNNNNN.huf` | Huffman-coded trade segments | When a batch is due after a command, on a timer whenever trades are buffered, before every Vault flush, and at shutdown |
| `accounts.jsonl` | Every account (Vault ledger), one per line | On a timer when something changed, and at shutdown; atomic rename |
| `counters.json` | Next order id, next trade id, `seq`, plus a reserve | At startup before the first command, with every flush, and whenever half the reserve is used |

* **Order of writes:** trades first, then balances. A balance on disk never includes a trade the archive lacks; if the Logbook cannot be written, the Vault is not written either ([backend/BUGS.md](../backend/BUGS.md) B13, with a fault-injection test).
* **Ids never repeat.** Order ids, trade ids and `seq` continue across restarts. After a crash they skip ahead by the reserve rather than reissue an id that may already be in the archive ([backend/BUGS.md](../backend/BUGS.md) B12; [09 §5](./09_api_contract.md#5-what-is-deliberately-not-here)).
* **Not persisted:** resting orders and the books. A restart starts with empty books, so cancelling an order from an earlier session is a 404.

---

## 4. API Summary

The authoritative specification, with every request and response captured from a running server, is **[09_api_contract.md](./09_api_contract.md)**. This section only lists the endpoints; it does not restate payloads. *The payloads that the original design listed here (for example a string `traderId` such as `"T101"`, `refundQuantity` on cancel, and a WebSocket stream) are superseded by doc 09.*

| Method and path | Purpose |
| :--- | :--- |
| `POST /api/orders` | Submit a LIMIT or MARKET order; status `FILLED`, `PARTIAL`, `QUEUED` or `REJECTED` with a reason |
| `DELETE /api/orders/{id}` | Cancel a resting order (`ORD-<n>` or `<n>`) |
| `GET /api/orderbook/{pair}?depth=N` | Bids and asks with volume, order count and cumulative volume (path form `EUR-USD`) |
| `GET /api/trades?pair=P&limit=N` | Recent trades, newest first |
| `GET /api/inspector/{pair}` | Internal state: levels, queue heads, the B+ tree's shape |
| `GET /api/arbitrage/scan` | The latest scan result |
| `GET /api/arbitrage/graph` | Currencies, edges with rates and staleness, the current cycle |
| `POST /api/market/rates` | Ingestion from the feed |
| `GET /api/market/rates/status` | Feed source, freshness and scan counters |
| `GET /api/tickers?prefix=P` | Directory prefix search, with each pair's tick size |
| `GET /api/accounts`, `GET /api/accounts/{traderId}`, `POST /api/accounts` | List, read and create accounts (`traderId` is a positive integer) |
| `POST /api/simulate`, `GET /api/simulate/last` | Run a simulation on an isolated engine; the most recent result |
| `GET /api/stream` | Server-Sent Events (§3a) |
| `GET /api/health` | Liveness, through the engine thread |

Conventions that apply to every endpoint (exact decimal prices, pair spelling, the error shape `{"error":{"code":…,"message":…}}`, CORS for the Vite dev origin only, binding to `127.0.0.1` by default) are in [09 §1 and §2](./09_api_contract.md#1-conventions).
