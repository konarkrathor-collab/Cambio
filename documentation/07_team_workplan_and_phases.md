# Team Workplan — What Was Built, and Slice Ownership

This document started as the three-person execution plan: it turned the data structure choices (doc 05) and the architecture (doc 02) into backend modules, three vertical slices and a sequence of phases. The system has since been built, stage by stage, under [08_build_plan_and_acceptance.md](./08_build_plan_and_acceptance.md) (Stages 0 to 7). This document now records **what was built and how it divides into slices**. It replaces the plan where the plan no longer applies, and marks each changed decision as *proposed* versus *built* rather than deleting the reasoning.

**Ownership is not recorded here.** Doc 00 left sub-module ownership "to be finalized after discussion", and this document does not invent it. Each slice below has an owner field marked **to be confirmed by the team**; the three members fill it in together.

---

> **Stack.** Java 17 backend (all computation), Python 3 data feed (fetch and forward only), React dashboard (Vite), Server-Sent Events from the backend to the browser (doc 08 §1). *Proposed:* each slice ships its own webpage over a WebSocket API. *Built:* one React dashboard with one view per slice, served by the Java server itself (`serve --static-dir`, Stage 7a) and fed by one SSE stream.

## 1. Data structures: closed, with the price ladder changed

Every pick was benchmarked against realistic alternatives (see [`benchmark/results/RESULTS.md`](../benchmark/results/RESULTS.md) and doc 05's "Benchmark Evidence" subsections). This plan originally listed the Radix Tree as the price ladder with an "open tension" from Run 13a. That tension was resolved in [doc 08 §3a](./08_build_plan_and_acceptance.md#3a-decision--the-price-ladder): after Runs 12 to 14 the Radix Tree had no remaining measured advantage, and the ladder became the **B+ tree (order 32)**, behind a `PriceLadder` interface so the choice stays reversible. Every other pick stands.

| Subsystem | Proposed | Built (production class) |
| :--- | :--- | :--- |
| Order book price list | Radix Tree (16-ary) | **B+ tree, order 32**: `ads.engine.BPlusTreeLadder`, used only through `ads.engine.PriceLadder` |
| Order queue (FIFO + cancel) | Doubly Linked List + Hash Map | **Doubly Linked List + Hash Map**: the FIFO queue in `ads.engine.PriceLevel`, one engine-wide `ads.engine.OrderIndex` (hand-rolled chaining hash table) |
| Currency arbitrage scanner | Directed Graph + Bellman-Ford | **Directed Graph + Bellman-Ford**: `ads.scanner.BellmanFord`, `ads.scanner.ArbitrageScanner`, plus an exact check of every triangle (doc 08 Stage 3 addendum; `backend/BUGS.md` B8) |
| Trader account cache | Hash Map + LRU | **Hash Map + LRU**: `ads.vault.LruCache`, inside `ads.vault.Vault` |
| Currency ticker lookup/autocomplete | Trie | **Trie**: `ads.directory.TickerTrie`, inside `ads.directory.Directory` |
| Trade log compression | Huffman Coding | **Huffman Coding**: `ads.logbook.HuffmanCodec`, inside `ads.logbook.Logbook` |

Stage 0 ported all six structures from `benchmark/src/` and checked each against the benchmark's own correctness gate and oracle (doc 08 Stage 0). No production structure class uses `java.util` collections (doc 08 §3 rule 7).

---

## 2. Backend modules (as built)

*Proposed:* six modules plus a Gateway, matching doc 02. *Built:* the same six, the Gateway, and two pieces the plan did not list: the simulator (doc 08 Stage 4) and the dashboard. The Live Feed is a separate Python process, and its backend half is `MarketService`.

| # | Module | Where | Owns | Talks to |
| :-: | :--- | :--- | :--- | :--- |
| 1 | **Exchange Engine** | `backend/src/ads/engine/` | B+ tree price ladders (one per side per pair), DLL order queues, the engine-wide order index, price-time matching (doc 08 Appendix A) | Directory (pair ids), Vault (margin checks, settlement), Logbook (completed trades), Gateway |
| 2 | **Scanner** | `backend/src/ads/scanner/` | Rate store, cross-rates through USD, Bellman-Ford passes plus the exact triangle check, profit threshold | MarketService (rates in, scans requested), Gateway (scan and graph out) |
| 3 | **Live Feed** | `feed/` (Python) and `backend/src/ads/api/MarketService.java` | Finnhub WebSocket and Frankfurter fallback, forwarded to `POST /api/market/rates` (doc 06); on the Java side: validation, quote expiry, source switching, the throttled scan, the status endpoint | Scanner, Gateway (events) |
| 4 | **Vault** | `backend/src/ads/vault/` | Accounts in an own hash table, the Hash Map + LRU cache, persistence. *Proposed:* a database. *Built:* a flat file (`accounts.jsonl`, written atomically) in the data directory | Exchange Engine, Gateway |
| 5 | **Directory** | `backend/src/ads/directory/` | The 12-currency pair universe in a Trie: validation, pair ids, prefix search | Gateway (pairs are validated before they reach the engine), Exchange Engine, Scanner (currency list) |
| 6 | **Logbook** | `backend/src/ads/logbook/` | Completed trades buffered and rotated into Huffman-compressed archive segments, also on a timer (Stage 6 drill 1, `backend/BUGS.md` B13) | Exchange Engine; `run.sh archive` decodes it |
| — | **Simulator** | `backend/src/ads/sim/`, run by `ads.api.SimulationRunner` | Seeded order generator (shallow and deep profiles), an isolated engine per run, the naive baseline matcher, percentiles, the trade checksum | Its own private Directory, Vault and engine only; never the live ones |
| — | **Gateway** | `backend/src/ads/api/` (`App`, `ApiServer`, `EngineThread`, `SseHub`, `StaticFiles`, `CounterStore`) and `backend/src/ads/util/` (JSON) | REST endpoints, the single engine thread that serialises every mutation and every scan, the SSE stream, persisted id counters, static serving of the built dashboard. Contract: [09_api_contract.md](./09_api_contract.md) | Everything |
| — | **Dashboard** | `frontend/` | One React app: top bar, Market, Arbitrage, Simulator and Inspector views, the DEMO drawer | The Gateway only |

---

## 3. The three vertical slices (as built; ownership to be confirmed by the team)

*Proposed:* each person owns one complete vertical slice, its backend modules *and* the view that shows it, so individual contribution is clear for grading and each person can demo something finished. The split below keeps the proposed three slices and maps them onto the modules, endpoints and views that were actually built. Pieces that every slice depends on are listed separately as shared.

### Slice A — The Exchange

**Owner: to be confirmed by the team.**

- **Backend:** Module 1 (Exchange Engine) and the Simulator.
- **Endpoints:** `POST /api/orders`, `DELETE /api/orders/{id}`, `GET /api/orderbook/{pair}`, `GET /api/trades`, `GET /api/inspector/{pair}`, `POST /api/simulate`, `GET /api/simulate/last`; events `BOOK_UPDATE` and `TRADE`.
- **Dashboard:** Market view (order book ladder, depth chart, order ticket, the session's own orders with cancel, trades feed), Simulator view (engine versus naive baseline, shallow and deep profiles), Inspector view (levels, queue heads, ladder shape).
- **Evidence:** the differential test against the reference matcher (Stage 1), the simulator's engine-versus-baseline checksum (Stage 4), `backend/BUGS.md` B11 (shallow books) and the deep profile.

### Slice B — The Scanner

**Owner: to be confirmed by the team.**

- **Backend:** Module 2 (Scanner) and Module 3 (Live Feed: `feed/` and `MarketService`).
- **Endpoints:** `GET /api/arbitrage/scan`, `GET /api/arbitrage/graph`, `POST /api/market/rates`, `GET /api/market/rates/status`; events `LOOPHOLE_ALERT` and `RATE_TICK`.
- **Dashboard:** Arbitrage view (force-directed currency graph with the current cycle highlighted, threshold and scan panel, alert list) and the data-source badge in the top bar.
- **Evidence:** the threshold study and the INR finding (doc 06 §4 and §5), the exact triangle check (B8), the feed drills (`scripts/drill_feed.sh`, B17).

### Slice C — The Vault & Directory

**Owner: to be confirmed by the team.**

- **Backend:** Module 4 (Vault), Module 5 (Directory), Module 6 (Logbook).
- **Endpoints:** `GET /api/tickers`, `GET /api/accounts`, `GET /api/accounts/{traderId}`, `POST /api/accounts`.
- **Dashboard:** the pair picker with Trie autocomplete, the account panel.
- **Evidence:** restart and `kill -9` persistence (`scripts/drill_kill9.sh`, B12, B13), byte-identical archive round trips (`scripts/logbook_check.sh`), the bounded account inputs (Stage 6 addendum, B14).

### Shared (not part of one slice)

**Owner: to be confirmed by the team.**

- **Gateway infrastructure:** `App`, `ApiServer`, `EngineThread`, `SseHub` (`GET /api/stream`), `StaticFiles`, `CounterStore`, the JSON reader/writer, `GET /api/health`.
- **Dashboard shell:** the API wrapper, the shared event stream and store, the top bar, the DEMO drawer, the error boundary.
- **Tooling and delivery:** `scripts/` (end-to-end, drills, fuzz, soak, `verify-all.sh`, `check_links.py`), `start-all.sh`, the documentation.

### Ownership table (for the team to fill in)

| Slice | Owner | Confirmed by all three (date) |
| :--- | :--- | :--- |
| A — The Exchange | *to be confirmed by the team* | |
| B — The Scanner | *to be confirmed by the team* | |
| C — The Vault & Directory | *to be confirmed by the team* | |
| Shared pieces | *to be confirmed by the team* | |

---

## 4. Phases: replaced by the build stages

*Proposed:* Phases 2 to 7 below, after Phase 1 (data structure validation). *Built:* the work followed the stages of doc 08 instead, each closed by an acceptance gate under independent verification (doc 08 §4). Every phase is listed with what replaced it.

| Phase (proposed) | Status | Replaced by |
| :--- | :--- | :--- |
| **1. Data structure validation** | Done | The benchmark lab, `benchmark/results/RESULTS.md` |
| **2. Graduate the code** from `benchmark/src/` into the application | Done | Stage 0: all six structures ported and checked against the benchmark's own gates |
| **3. Build each backend module and its API**; freeze the API before the frontend | Done | Stage 1 (engine, scanner, Directory, Vault, Logbook, command-line demo) and Stage 3 (gateway, SSE, the contract). The contract was frozen at the Stage 3 acceptance; later changes are listed in [doc 09 §6a](./09_api_contract.md#6a-change-log) |
| **4. Connect the real external pieces** | Done, changed | Stage 2: the Finnhub feed as a separate Python process (doc 06). *Proposed:* a database for accounts. *Built:* flat-file persistence (Stage 1), no database |
| **5. Build the frontend**, one webpage per slice | Done, changed | Stage 5: one React dashboard with one view per slice, built against the frozen contract with no backend changes |
| **6. Integration** | Done | Stage 6: end-to-end script, integration checks and six failure drills, every bug logged in `backend/BUGS.md` |
| **7. Polish, report, demo rehearsal** | Stage 7 | Stage 7a (one port, `start-all.sh`, `scripts/verify-all.sh`) and Stage 7b (final benchmark run, documents brought up to date, root README, demo script, final report, release tag) |

**The proposed integration checkpoints, and how each was checked (Stage 6):**

| Proposed checkpoint | How it was checked |
| :--- | :--- |
| Does an order placed in Slice A validate its ticker against Slice C's Directory? | `scripts/logbook_check.sh`: hostile and unknown pair names never reach the engine |
| Does a completed trade in Slice A reach Slice C's Logbook? | `scripts/logbook_check.sh`: the archive holds exactly the trades the API reported |
| Does Slice B's scanner run without blocking Slice A's matching loop? | *Proposed:* the scanner runs independently. *Built:* the scanner runs on the same single engine thread as matching (`EngineThread`; doc 08 §3 rule 9), throttled to one scan per 500 ms. `scripts/flood_check.sh` measures order latency with and without a tick flood and fails if the flood moves p99 by more than 2 ms |

The six failure drills, the soak and the fuzz test are described in [`scripts/README.md`](../scripts/README.md).

---

## 5. One rule worth stating explicitly

**No slice re-opens the data structure decision in §1 without new benchmark evidence.** If a real problem with a pick surfaces (not just "I'd rather use X"), the fix is to extend the benchmark harness and measure it, the same process that produced every decision in this document, not to swap structures on a hunch.

This rule was applied once: the price ladder moved from the Radix Tree to the B+ tree only on the measured evidence of Runs 12 to 14 (doc 08 §3a). It is now doc 08 §3 rules 6 and 11.
