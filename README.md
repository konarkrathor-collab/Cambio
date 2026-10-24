# Cambio

**Order Matching System with Currency Loophole Detector**: a practice currency exchange with a matching engine and a loophole scanner.

> **Course:** Advanced Data Structures (ADS – `702IT0E009`)  
> **Institution:** SVKM's NMIMS MPSTME / STME  
> **Faculty In-Charge:** Prof. Ashwini Rao  
> **Group Members:** Krishna Solanki (`I075`), Konark Rathor (`I062`), Palak Savla (`F054`)  
> **Release:** v1.1.0

A trading-exchange simulator and forex loophole scanner. Every data structure on its hot paths is written from scratch.

1. **Order matching engine (Java).** Matches orders by price-time priority. Each side of each book is a **B+ tree** of price levels, each level is a **doubly linked FIFO queue**, and one engine-wide **hash index** makes cancel-by-id O(1).
2. **Currency loophole detector (Java).** Real exchange rates become a graph with edge weights $-\ln(\text{rate})$, so a profitable conversion loop is a negative cycle. **Bellman-Ford** finds it, and an exact check covers every triangle.
3. **Auxiliary structures.**
   - A **hash ledger with an LRU cache** for trader accounts.
   - A **trie** for pair lookup and autocomplete.
   - **Huffman coding** for the trade archive.
4. **Market data feed (Python).** Streams real forex ticks from Finnhub, falls back to the Frankfurter daily snapshot, or replays a recorded capture. It only fetches and forwards; all computation is in Java.
5. **Two front ends (React + d3), one backend.**
   - **Cambio**, the consumer app at `/`: Markets, Trade (price and depth charts, order book, a plain-language order ticket), Portfolio, and Opportunities (the loophole map, with a paper run of the loop). It shows no data-source jargon. Design in [frontend/design/](./frontend/design/visual-direction.md).
   - **The demo dashboard** at `/#/demo`: the terminal-style view with the structure inspector, the engine-versus-naive simulator and the demo drawer, for explaining how it works.
   Live updates arrive by Server-Sent Events.

Each structure was chosen by building and measuring the alternatives first. Measurement replaced one pick: the price ladder was planned as a radix tree and is a B+ tree. The evidence is in [documentation/05](./documentation/05_data_structure_selection_rationale.md) and [benchmark/results/RESULTS.md](./benchmark/results/RESULTS.md).

---

## Architecture

```
  Browser: Cambio (Markets | Trade | Portfolio | Opportunities)  or  demo dashboard (#/demo)
        |  REST (fetch)                         ^  Server-Sent Events: BOOK_UPDATE,
        v                                       |  TRADE, LOOPHOLE_ALERT, RATE_TICK
 +-----------------------------------------------------------------------------+
 |  Java backend, one port (JDK HttpServer; serves the dashboard too)          |
 |    HTTP threads: parse + validate  ->  ONE engine thread:                   |
 |      MatchingEngine: per pair, bid + ask B+ tree ladders -> PriceLevel FIFO |
 |                      queues; one engine-wide OrderIndex (hash)              |
 |      MarketService -> ArbitrageScanner: Bellman-Ford + exact triangles      |
 |      Vault (hash ledger + LRU)   Directory (trie)   Logbook (Huffman)       |
 |    Simulator thread: isolated engine vs naive linear-scan matcher           |
 +-----------------------------------------------------------------------------+
        ^  POST /api/market/rates                 |  disk: Huffman trade segments,
        |                                         v  accounts.jsonl, counters.json
  Python feed: Finnhub WebSocket (live) | Frankfurter (fallback) | --replay FILE
```

Details: [documentation/02](./documentation/02_system_architecture_and_design.md). API: [documentation/09](./documentation/09_api_contract.md).

---

## Quick start

Needs a JDK 17 or newer, Node.js 22.12 or newer with npm, Python 3 and curl. macOS and Linux are supported.

```sh
git clone https://github.com/lifeknife10A/Cambio.git
cd Cambio
./start-all.sh
```

Then open **http://127.0.0.1:8080** for Cambio, or **http://127.0.0.1:8080/#/demo** for the terminal-style demo dashboard. The first start builds the backend and the dashboard and creates the feed's virtual environment, which takes a minute or two. `Ctrl-C` stops everything.

- **Without** `FINNHUB_API_KEY` in the environment, the feed replays the committed real capture (`feed/samples/finnhub_ticks_2026-10-23.jsonl`), and the demo dashboard's badge says **REPLAY**, never LIVE. `FINNHUB_API_KEY= ./start-all.sh` (an empty value) forces replay even when a `.env` file holds a key. Cambio itself shows no source badge, only a quiet "Prices paused" line when quotes are stale.
- **With** the key set, the feed is live. Either export `FINNHUB_API_KEY`, or copy `.env.example` to `.env` (git-ignored) and put the key there; `start-all.sh` reads it. Forex markets close at weekends, and then the demo dashboard's badge says WAITING ("connected, no ticks") and Cambio says prices are paused.
- `PORT=9000 ./start-all.sh` uses another port.
- A guided 10–12 minute walkthrough is in [documentation/DEMO.md](./documentation/DEMO.md).

---

## Gates: how to check everything

`scripts/verify-all.sh` runs every gate below in sequence from a fresh clone. It prints PASS or FAIL with timings and exits non-zero on any failure. `--quick` skips the three slow drills (flood, feed, SSE). A full run takes about 6 minutes.

| Gate | Run it alone | What it proves |
| :--- | :--- | :--- |
| Backend suite | `backend/test.sh` | 207 tests: the six structures against the benchmark's own oracles, the matching rules, the API, crash safety, static file serving with every path-traversal attempt |
| Feed suite | `feed/test.sh` | Reconnect and backoff, fallback, replay, malformed input, the API key never logged |
| Frontend | `cd frontend && npm ci && npm test && npm run build` | 139 tests: API wrapper, reducers, formatting, depth maths, order validation, a recorded stream replayed, the error boundary |
| Smoke | `backend/scripts/smoke.sh` | Every endpoint once, curl only |
| Replay gate | `feed/replay_check.sh` | The real capture replays end to end |
| End to end | `scripts/e2e.sh` | Orders, cancels, a loophole and both simulator profiles against a real backend and feed; balances recomputed from the trade list |
| Logbook | `scripts/logbook_check.sh` | Every trade reaches the Huffman archive, byte for byte; no unknown pair reaches the engine |
| Flood | `scripts/flood_check.sh` | 200 rate ticks/s do not slow order handling (p99 change limit +2 ms) |
| Drill 1 | `scripts/drill_kill9.sh` | `kill -9`, then restart: accounts persist, the archive decodes, ids never repeat |
| Drills 2–3 | `scripts/drill_feed.sh` | Feed killed: status `disconnected` and the scan clears within 30 s. Socket lost: Frankfurter fallback, then reconnect |
| Drill 4 | `scripts/fuzz.sh 1 5000` | Seeded fuzz of every endpoint: never a 500, never a hang, cash conserved |
| Drill 5 | `scripts/drill_sse.sh` | 32 event streams, a client that never reads, a reconnect loop: order latency stays flat |
| Links | `python3 scripts/check_links.py` | Every relative link and anchor in every Markdown file |

Four checks are outside verify-all:
* `scripts/soak.sh` (drill 6, about 11 minutes): steady load, with heap, threads and file descriptors watched for growth.
* `frontend/e2e/cambio.mjs`: Cambio end to end in a headless browser (36 checks: Markets, a trade, the portfolio, a loop, reduced motion, no console output).
* `frontend/e2e/demo-script.mjs` and `frontend/e2e/golden-path.mjs`: the demo dashboard (42 and 50 checks).

The three browser scripts need Playwright (see [frontend/README.md](./frontend/README.md)).

Details for each script: [scripts/README.md](./scripts/README.md).

---

## Headline results (measured, v1.0.0)

Every number below is from a committed file in [documentation/results/](./documentation/results/README.md). The machine was a Linux container: Xeon @ 2.10 GHz, 4 cores, OpenJDK 21.

| What | Result | Source |
| :--- | :--- | :--- |
| Engine throughput (doc 04 target ≥ 50,000 orders/s) | **933,266 to 3,643,727 orders/s** in memory across 7 simulator runs: **met** | [simulator.txt](./documentation/results/simulator.txt) |
| Cancel latency (target < 1 µs, O(1)) | Median **0.094 to 0.641 µs**: met. p99 up to **5.040 µs**: missed in 4 of 7 runs | [simulator.txt](./documentation/results/simulator.txt) |
| Loophole scan (target < 100 µs at 20 currencies) | 7 currencies (live feed): **≤ 4.30 µs**. 12 currencies: **≤ 24.98 µs**. 20 currencies: 11.61 µs with no cycle, **160.33 µs with a cycle: missed** | [scan_timing.txt](./documentation/results/scan_timing.txt) |
| Engine versus naive linear scan, deep books | **17.24×** faster with 28,382 orders resting, **91.12×** with 113,390. Identical trades and balances | [simulator.txt](./documentation/results/simulator.txt) |
| Engine versus naive linear scan, shallow books | **0.47× to 1.31×**: with about 50 orders resting, a linear scan is as fast | [simulator.txt](./documentation/results/simulator.txt) |
| Price ladder, resident memory (54,169 levels) | B+ tree **28.9 B/level**; red-black 48.0; radix 58.6; flat array 134 MB in all | [benchmark_run15.txt](./documentation/results/benchmark_run15.txt) |
| Order queue, deep book (15,397 live orders) | DLL + hash **10.24 ms** per 300k operations; plain array 100.71 ms; un-indexed list 551.23 ms | [benchmark_run15.txt](./documentation/results/benchmark_run15.txt) |
| Real market data | No loophole at the 0.02% default on the real capture. The best cycle reached 0.01652% | [threshold_study.txt](./documentation/results/threshold_study.txt) |
| Fuzzing | 7,415 hostile requests: 0 server errors, cash conserved | [drill_fuzz.txt](./documentation/results/drill_fuzz.txt) |
| Crash (`kill -9`) | 50 of 50 trades archived, balances equal seed plus archive, ids continue | [drill_kill9.txt](./documentation/results/drill_kill9.txt) |
| Soak, 10 minutes at 300 orders/s + 50 ticks/s | Flat: threads 27 → 27, file descriptors 12 → 11, live heap 4,897 → 2,926 KB, order p99 3.296 ms (first two minutes) → 3.197 ms (last two), over HTTP | [soak.txt](./documentation/results/soak.txt) |

The full evaluation is in [documentation/10_final_report.md](./documentation/10_final_report.md) and the doc 04 targets in [documentation/04 §3B](./documentation/04_project_roadmap_and_milestones.md#b-performance--stress-testing). Twenty bugs found during the build are written up in [backend/BUGS.md](./backend/BUGS.md).

---

## Limitations (stated honestly)

- **Triangular detection only, exactly.**
  - Every three-currency loop at or above the threshold is always reported.
  - Longer loops are best-effort: Bellman-Ford may return a different cycle, and an exact search over all simple cycles is NP-hard.
  - Near the threshold on real data such loops are bid/ask noise, not tradeable (BUGS.md B8, accepted).
- **Simulation units, not money.**
  - A trade moves `price tick × quantity` between two traders' cash, with no currency conversion.
  - Resting orders reserve nothing, so cash can go negative (doc 08 Appendix A).
  - It is a simulator, not a clearing house.
- **A single server.**
  - One JVM and one engine thread. There is no replication or failover, and no horizontal scaling.
  - Resting orders are kept only in memory, so a restart starts with empty books. Accounts, id counters and the trade archive persist.
- **No authentication.**
  - Anyone who can reach the port can place orders for any trader.
  - The server binds to 127.0.0.1 by default; do not expose it.
- **The live feed depends on market hours.**
  - Forex trades from Sunday evening to Friday evening (UTC). Outside those hours the live feed is connected but silent, and the dashboard says so.
  - `start-all.sh` without a key replays the recorded capture instead, labelled REPLAY.
  - INR, CNY, HKD, SGD and NZD are not streamed live; they come only from the daily fallback (doc 06 §4).
- **The 20-currency scan target is missed with a cycle present.** This is a cost of the extra passes that make the report correct (doc 04 §3B). At the 7 and 12 currencies the system really uses, the worst scan is 25 µs.

---

## Repository map

```
Cambio/
├── README.md               this file
├── start-all.sh            whole system on one port (backend + dashboard + feed)
├── backend/                Java 17, plain javac: engine, structures, scanner, gateway, simulator
│   ├── src/ads/            engine/ scanner/ vault/ directory/ logbook/ api/ sim/ util/
│   ├── test/ads/test/      plain-Java test runner (parity, engine, API, static files, ...)
│   ├── scripts/            smoke.sh, simulate.sh, contract_examples.sh
│   ├── BUGS.md             every bug found: what broke, why, the fix
│   └── README.md           build, test, run, serve options
├── feed/                   Python market-data feed (fetch and forward only)
│   ├── feed.py             Finnhub WebSocket, Frankfurter fallback, --replay
│   ├── samples/            the committed real capture (2026-10-23)
│   └── tests/              feed suite; fake_finnhub.py is the test socket server
├── frontend/               React + Vite + d3: Cambio (src/consumer), the demo dashboard, design/ docs, e2e/
├── scripts/                verify-all.sh, the drills, fuzz, soak, load generator, link checker
├── benchmark/              the benchmark lab: every candidate structure, oracle-gated
│   └── results/RESULTS.md  evidence log, Runs 1–15
└── documentation/          docs 00–10, DEMO.md, results/ (the committed measurements)
```

Start with the [documentation index](./documentation/README.md).
