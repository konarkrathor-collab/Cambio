# Market Data Ingestion Service — Specification and As-Built Record

This document describes the service that brings real-world currency data into the Currency Loophole Detector. It does **not** feed the Order Matching Engine: the matching engine runs on synthetic or user-submitted order flow by design (it *is* the exchange; it does not consume one), so ingestion is scoped entirely to the arbitrage-detection subsystem.

It started as a design specification and now records the system **as built** (doc 08 Stages 2 to 6). Where the build changed a proposed decision, the section says so with a **Proposed / Built** note instead of deleting the original reasoning.

**Stack (faculty-confirmed, doc 08 §1).** Ingestion is split in two:

- **`feed/feed.py`** (Python 3) only fetches and forwards. It reads Finnhub WebSocket ticks (or, during an outage, a Frankfurter snapshot) and posts them to the Java backend's `POST /api/market/rates`. It never derives cross-rates, builds the graph, decides staleness or computes arbitrage.
- **`backend/src/ads/api/MarketService.java`** (Java) does everything computed: it applies each payload to the scanner's rate store (`backend/src/ads/scanner/ArbitrageScanner.java`, which derives cross-rates), decides which quotes are still usable, runs the throttled scan, publishes events and answers the status endpoint.

Payload shapes: [doc 08 Appendix B](./08_build_plan_and_acceptance.md#appendix-b--feed--backend-payloads) and [doc 09 §3.7](./09_api_contract.md#37-post-apimarketrates-and-get-apimarketratesstatus).

**Revision history.** The first version of this spec used a free daily-snapshot API (Frankfurter) plus a synthetic "jitter" layer to fake movement between snapshots. That was replaced by **Finnhub's real-time forex WebSocket** (sourced from OANDA), so the jitter engine, mean-reversion maths and bid/ask-spread modelling were removed. The only synthetic market data left in the repository is test tooling that is clearly labelled as such: `feed/fake_finnhub.py` (a local stand-in socket for drills) and the dashboard's DEMO "Inject loophole" action, which is sent with `"replay": true` so it is never shown as live.

---

## 1. Responsibility

Keep a continuously updated set of currency exchange rates, sourced from Finnhub's real-time forex WebSocket stream, and feed every update into the graph/Bellman-Ford pipeline described in [02_system_architecture_and_design.md](./02_system_architecture_and_design.md). When the stream is unavailable, fall back to a coarser source and say so; when there is no usable data at all, report no arbitrage rather than a frozen one.

---

## 2. Data Source

| Role | Provider | Endpoint | Auth | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **Primary (real-time)** | Finnhub | `wss://ws.finnhub.io?token=…` | API key (query parameter; the WebSocket has no other way to pass it) | Push-based trade ticks, sourced from OANDA |
| **Discovery (one-time, at build time)** | Finnhub REST | `GET /forex/symbol?exchange=oanda` | API key, sent as the `X-Finnhub-Token` header | Run once with `feed.py --discover`; the result is committed as `feed/symbols.json`. Not called at runtime |
| **Fallback (degraded)** | Frankfurter | `GET https://api.frankfurter.dev/v1/latest?base=USD` | None | Used only after the WebSocket has been down for more than 15 s (§5) |

> **Checked against Finnhub's documentation when this spec was written** (not re-checked since): `/forex/exchange` lists `oanda`, `fxcm`, `forex.com`, `ic markets`, `fxpro`. Finnhub's WebSocket documentation states *"The following FX brokers do not support streaming: FXCM, Forex.com, FHFX"*. OANDA is not on that list, which is why it is the source. Finnhub's `/forex/candle` and `/forex/rates` REST endpoints are marked **Premium Access Required**, so the free tier has no REST forex data beyond the symbol/exchange discovery endpoints, and Finnhub's REST API cannot be the fallback. **Frankfurter** is used instead: a daily snapshot, lower resolution, but a real safety net.

**Built: credential handling.** The key is read only from the environment variable `FINNHUB_API_KEY`. It is never hard-coded, never written to a file, and redacted from every log line: `feed.py` installs a logging filter (`RedactingFilter`) that replaces the key wherever it appears in a log record, and the discovery call sends it as a header rather than in the URL. `.env` files are listed in `.gitignore`. Feed tests check that the key never reaches the log and that `--discover` never writes it (`feed/tests/test_feed.py`). `start-all.sh` runs the feed live only if the variable is set and never prints it. Regenerate the key in the Finnhub dashboard if it is ever exposed.

**Built: symbols.** Finnhub's forex symbols are prefixed by source, for example `OANDA:EUR_USD`. The feed subscribes to exactly the `subscribe` list in `feed/symbols.json`: **21 symbols** (§4). *Proposed:* "roughly 12-16 pairs", against the free tier's limit of roughly 50 concurrent subscriptions as stated in Finnhub's documentation at the time. *Built:* 21, still below that limit.

---

## 3. Architecture (as built)

```
   wss://ws.finnhub.io            https://api.frankfurter.dev  (only after 15 s without the socket)
           |                                  |
           v                                  v
 +-------------------------------------------------------------+
 |  feed/feed.py  (Python, fetch-and-forward only)             |
 |  - subscribes to the 21 symbols in feed/symbols.json,       |
 |    again after every reconnect                              |
 |  - batches ticks: at most 250 ms per batch                  |
 |  - empty "heartbeat" batch every 5 s while connected        |
 |  - reconnect backoff 1, 2, 4, 8, 10 s (cap)                 |
 |  - fallback snapshot when the socket is down > 15 s,        |
 |    then hourly                                              |
 +-------------------------------------------------------------+
           |  POST /api/market/rates   (doc 08 Appendix B)
           v
 +-------------------------------------------------------------+
 |  MarketService  (Java, on the single engine thread)         |
 |  - validates the payload; bad ticks counted, never fatal    |
 |  - source switch (live <-> fallback): clears the rate store |
 |  - each quote valid 30 s (live) or 2 h (fallback)           |
 |  - throttled scan: at most one per 500 ms, plus one         |
 |    trailing scan so the newest rates are always scanned     |
 |  - re-scan at the instant the next quote expires            |
 +-------------------------------------------------------------+
           |                                    |
           v                                    v
 ArbitrageScanner                         SSE GET /api/stream:
 - direct quotes set both directions      RATE_TICK (at most 4/s per pair),
 - other pairs derived through USD        LOOPHOLE_ALERT (when a cycle is newly
 - edge weight -ln(rate)                  found or lost, not on every scan)
 - Bellman-Ford passes + exact check
   of every triangle
```

*Proposed:* a `WebSocketListener` and `RateStore` inside the backend, and a WebSocket broadcast to the frontend. *Built:* the socket lives in the separate Python process, the rate store is inside `ArbitrageScanner`, and the browser receives **Server-Sent Events** (doc 08 §1 replaced the WebSocket server). The motivation is unchanged: there is no scheduled jitter loop, rates change exactly when Finnhub pushes a tick, and the only timers are the scan throttle (so a burst of ticks does not trigger dozens of redundant scans) and the expiry re-scan.

---

## 4. Currency Coverage & Graph Construction

*Proposed:* a fixed roster `USD, EUR, GBP, JPY, AUD, CAD, CHF, INR` (8 currencies), subscribing to whatever direct OANDA pairs exist for it, including `USD_INR`, and deriving the rest through USD (`R(a,b) = R(USD,b) / R(USD,a)`).

> **Status: verified (2026-10-23).** `feed/feed.py --discover` called `/forex/symbol?exchange=oanda` once with a live key and recorded the result in `feed/symbols.json`. OANDA lists 120 symbols. Of the 28 currency pairs in the 8-currency roster, **21 are streamed directly**: every pair among USD, EUR, GBP, JPY, AUD, CAD and CHF. **OANDA has no INR pair at all**, not even USD/INR, so the 7 INR pairs cannot be streamed or derived through USD.
>
> **Consequence: INR is fallback-only.** In live mode the scanner's graph has **7 currencies and 21 pairs (42 directed edges)**. INR appears only while the Frankfurter fallback snapshot is the source, because that snapshot quotes every roster currency against USD. Order trading is unaffected: the Directory still lists the INR pairs, and the matching engine trades them like any other pair. Only the arbitrage graph lacks a live INR rate.

**Built: how the graph is formed.**

- The scanner's universe is the Directory's 12 currencies (`backend/src/ads/directory/Directory.java`; doc 09 §3.6 lists all 12 as graph nodes, with `active` marking those that have a rate). Currencies with no rate simply have no edges.
- A direct quote sets both directions (the reverse is the reciprocal) and is marked `direct`. Every other pair is derived through USD when both USD legs are known, and a derived rate never overwrites a direct one (`ArbitrageScanner` class comment).
- **Live mode:** all 21 pairs among the 7 streamed currencies are quoted directly, so no live edge needs deriving.
- **Fallback mode:** the backend turns the Frankfurter snapshot into `USD/<code>` quotes for every currency of the 12 that the snapshot contains, ignores the rest, and derives every cross through USD. A graph built only from USD legs is consistent by construction, so it cannot show a real loophole; the fallback keeps the graph and the dashboard populated, not the detector busy.
- A derived cross is usable only while both USD legs it came from are usable (§6).

**Note on `feed/symbols.json`:** its `note` field used to say the pairs in `notStreamedDirectly` "are derived through USD by the Java backend". That was true for the 7 INR pairs only in fallback mode, because in live mode there is no USD/INR leg to derive from. It was corrected in Stage 7b, both in the file and in the `--discover` code that writes it.

---

## 5. Config

*Proposed:* every row below as a configuration parameter. *Built:* only `FINNHUB_API_KEY` and `MIN_PROFIT_PERCENT` are read from the environment; the rest are named constants in the file given, with the proposed values kept. The constants without a proposed counterpart were added during the build.

| Parameter (proposed name) | Built as | Value | Meaning |
| :--- | :--- | :-: | :--- |
| `FINNHUB_API_KEY` | environment only (`feed/feed.py`) | *(required for live mode)* | Auth for the WebSocket and the one-time discovery call. Not needed for `--replay`; the Frankfurter fallback is keyless |
| `SUBSCRIBE_SYMBOLS` | `subscribe` list in `feed/symbols.json` | 21 symbols | Sent as `{"type":"subscribe","symbol":"OANDA:EUR_USD"}` per symbol on every connect |
| — | `BATCH_S` (`feed/feed.py`) | 0.25 s | Ticks are forwarded at least every 250 ms |
| — | `HEARTBEAT_S` (`feed/feed.py`) | 5 s | An empty `finnhub-ws` batch while connected and idle, so the backend can tell "connected, no ticks" (weekends) from "disconnected" |
| `SCAN_THROTTLE_MS` | `MarketService.SCAN_THROTTLE_MS` | 500 ms | Minimum gap between scans; changes inside the window schedule one trailing scan |
| `MIN_PROFIT_PERCENT` | `serve --min-profit` or the `MIN_PROFIT_PERCENT` environment variable; default `ArbitrageScanner.DEFAULT_MIN_PROFIT_PERCENT` | 0.02 | Smallest cycle profit, in percent, reported as arbitrage. *Proposed:* 0.001. The 0.02 default is data-driven: see the study below |
| `RECONNECT_BACKOFF_MS` | `RECONNECT_BACKOFF_S` (`feed/feed.py`) | 1, 2, 4, 8, 10 s (cap) | Reconnect delays; every further attempt waits 10 s |
| `REST_FALLBACK_TRIGGER_MS` | `FALLBACK_TRIGGER_S` (`feed/feed.py`) | 15 s | How long the socket must stay down before the Frankfurter fallback starts (no flapping on a brief blip) |
| `REST_FALLBACK_POLL_MS` | `FALLBACK_POLL_S` (`feed/feed.py`) | 3,600 s (1 h) | Fallback poll interval; Frankfurter is a daily snapshot, so faster polling gains nothing |
| — | `FALLBACK_RETRY_S` (`feed/feed.py`) | 60 s | A failed fallback fetch is retried sooner than the hourly poll |
| `STALE_AFTER_MS` | `MarketService.STALE_AFTER_MS` | 30,000 ms | How long a live quote may be used. *Proposed:* only mark the pair stale on the dashboard. *Built:* an expired quote, and every cross derived from it, is **left out of the scan** (doc 08 Stage 2 addendum), so a feed that stops makes its alerts stop instead of freezing them |
| — | `MarketService.FALLBACK_VALID_MS` | 2 h | How long a fallback snapshot's quotes may be used (two hourly polls) |
| — | `MarketService.FEED_SILENT_MS` | 15,000 ms | No `finnhub-ws` payload (ticks or heartbeat) for this long: the status reports the feed as not connected |
| — | `MarketService.RATE_TICK_MIN_GAP_MS` | 250 ms | At most one `RATE_TICK` event per pair per 250 ms (4 per second) |
| — | `MarketService.SWEEP_MS` | 1,000 ms | Backstop check for expired quotes; the main mechanism is the expiry timer (§6, B17) |

**Choosing the profit threshold.** Finnhub's OANDA ticks alternate between bid and ask, so cycles of about 0.01% appear constantly in real data and cannot be traded. Replaying the committed capture (`feed/samples/finnhub_ticks_2026-10-23.jsonl`: 3,690 ticks over 299 s, 432 scans throttled to 500 ms, each quote usable for 30 s) through `backend/run.sh threshold-study` gives the table below. It was re-run unchanged on the final code: [results/threshold_study.txt](./results/threshold_study.txt).

| Threshold | Scanner reports a cycle | Exhaustive search: some cycle reaches it | Triangles only: some triangle reaches it |
| :-: | :-: | :-: | :-: |
| 0.001% | 429 / 432 (99.31%) | 429 / 432 (99.31%) | 429 / 432 (99.31%) |
| 0.005% | 427 / 432 (98.84%) | 427 / 432 (98.84%) | 427 / 432 (98.84%) |
| 0.010% | 58 / 432 (13.43%) | 286 / 432 (66.20%) | 4 / 432 (0.93%) |
| 0.020% | 0 / 432 (0.00%) | 0 / 432 (0.00%) | 0 / 432 (0.00%) |
| 0.050% | 0 / 432 (0.00%) | 0 / 432 (0.00%) | 0 / 432 (0.00%) |

Best-cycle profit per scan, exhaustive: p50 0.01070%, p90 0.01314%, p99 0.01562%, max 0.01652%. The default is the smallest candidate for which fewer than 1% of scans report a cycle: **0.02%**. At 0.010% the scanner reports far fewer scans than actually contain a qualifying cycle. That is the Bellman-Ford detection gap documented in `backend/BUGS.md` B4 and B8. Since the Stage 3 addendum the scanner also checks every triangle exactly, so a qualifying triangle is never missed. The "triangles only" column shows that the real near-threshold cycles are almost never triangles: in the 286 scans at 0.010%, the best cycle has 5 legs in 4 of them, 6 legs in 38 and 7 legs in 244. The gap that remains at 0.010% is therefore all longer cycles. It does not affect the 0.02% default, because no real cycle reached 0.02%. **Decision (doc 08 Stage 4 addendum):** this is an accepted, documented limitation. Triangles, the project's definition of the opportunity, are detected exactly. Longer loops are best-effort, because near the threshold they are bid/ask noise accumulated over many legs, not tradeable opportunities. No exhaustive search is built.

---

## 6. Failure Handling (as built)

| Failure | Behaviour |
| :--- | :--- |
| WebSocket disconnects | The feed flushes its batch, then reconnects with backoff 1, 2, 4, 8, 10 s (cap). On every connect it re-sends all 21 subscriptions (Finnhub does not remember subscriptions across connections) and posts an empty batch so the backend knows it is live again |
| WebSocket down more than 15 s | The feed fetches the **Frankfurter** snapshot (not Finnhub REST, see §2) and posts it as `source: "frankfurter-fallback"`, then repeats hourly (60 s after a failed fetch). The backend clears the rate store on the source switch, so live and snapshot rates never meet in one graph, and the status reports `state: "fallback"`. On reconnect the next live ticks switch the store back, and a later outage falls back 15 s after *it* starts |
| Feed process dead, or both sources unreachable | No payload arrives. After 15 s of silence the status reports `state: "disconnected"` (`connected: false`; `source: "cached"` while old quotes are held). Live quotes expire 30 s after they arrived and fallback quotes after 2 h; an expired quote and every cross derived from it leave the scan, and the affected pairs are listed in `staleSymbols`. The backend re-scans at the exact instant the next quote expires, so a dead feed's loophole clears then instead of being frozen on screen |
| Market closed (weekends) | Finnhub sends only `{"type":"ping"}`; the feed counts it as a keep-alive and keeps sending 5 s heartbeats, so the status reports `state: "connected, no ticks"` rather than an error |
| Malformed or partial message | The feed logs and skips it (a deeply nested message is caught too, `backend/BUGS.md` B6); good ticks in the same message still flow. The backend counts each bad tick in `rejectedTicks` and returns up to 5 error messages; a payload of the wrong shape is a 400. Neither process stops |
| Backend down or rejecting | The feed logs the failed post, drops that batch and keeps running |
| `FINNHUB_API_KEY` missing | `feed.py` in live or discover mode exits at once (exit code 2) with a clear message; `--replay` needs no key. *Proposed:* refuse to start the backend. *Built:* the backend is a separate process and starts regardless (its status says `waiting` until a payload arrives); `start-all.sh` runs a clearly labelled replay of the committed capture when no key is set |
| Frankfurter rejects the request | Its CDN answered 403 to Python's default `User-Agent`, so the fallback could never activate (`backend/BUGS.md` B5). Every request now sends `ads-feed/1.0 …` as its agent |

**Measured: the feed-kill drill (`backend/BUGS.md` B17, Stage 6 drill 2).** `scripts/drill_feed.sh` kills the feed and times the status and the scan. The status said `disconnected` at 15.0 s. Before the fix the scan still reported the loophole until 30.52 s after the kill, because the expiry re-scan came from the 1 s sweep. After the fix (a one-shot timer at the next quote's expiry instant) the scan cleared at 29.82 s and 29.88 s after the kill. The same script runs drill 3 (network loss: the fake socket is killed while the feed stays up; the feed falls back to Frankfurter after the 15 s trigger, then reconnects and goes live by itself when the socket returns).

---

## 7. API Surface (as built)

The exact contract is [doc 09 §3.5-3.7 and §4](./09_api_contract.md#37-post-apimarketrates-and-get-apimarketratesstatus); this is a summary.

- **`POST /api/market/rates`.** *Proposed:* "a manual admin override for testing". *Built:* it is the feed's ingestion endpoint, taking the two Appendix B shapes (`finnhub-ws` with `ticks`, `frankfurter-fallback` with `base` and `rates`). An empty `ticks` list is a heartbeat. The optional `"replay": true` marks a recorded capture (`feed.py --replay` and the dashboard's DEMO injection set it), so a replay is never presented as the live feed. Response: `{accepted, rejected, errors}`.
- **`GET /api/market/rates/status`.** *Proposed:* `{source, connected, lastTickAt, staleSymbols}`. *Built:* `state` (`waiting`, `live`, `connected, no ticks`, `fallback`, `disconnected`), `source` (`finnhub-ws`, `frankfurter-fallback`, `cached`, `none`), `replay`, `connected`, `lastTickAt`, `lastPayloadAt`, `tickCount`, `rejectedTicks`, `payloads`, `pairsSeen`, `staleSymbols`, `fallbackAsOf`, the scan fields (`scanCount`, `lastScanAt`, `lastScanFoundCycle`, `excludedEdges`, `minProfitPercent`, `lastScan`) and `ratesSource`, `sourceSwitches`. The dashboard's source badge (LIVE, FALLBACK, REPLAY, WAITING, DISCONNECTED) is driven by it.
- **`GET /api/arbitrage/scan`** and **`GET /api/arbitrage/graph`**: the latest scan, and the nodes, edges (with `stale` and `direct`) and current cycle.
- **Events.** *Proposed:* a WebSocket `RATE_TICK` on every tick. *Built:* Server-Sent Events on `GET /api/stream`: `RATE_TICK` `{pair, rate, ts, source}` at most 4 per second per pair (`ts` is null for a fallback snapshot), and `LOOPHOLE_ALERT` only when a scan newly finds or loses a cycle.

---

## 8. Tests and Checks (as built)

*Proposed:* reconnect, fallback activation, staleness, throttle, cross-rate derivation and malformed-message tests "for Phase 3 of the roadmap". All six exist; the build stages of doc 08 replaced the roadmap phases.

| Proposed test | Where it is now |
| :--- | :--- |
| Reconnect and re-subscribe | `feed/test.sh`: `test_backoff_is_1_2_4_8_10_10_and_every_reconnect_resubscribes` (fake socket, fake clock) |
| Fallback activation | `feed/test.sh`: fallback after 15 s down, hourly polls, stop on reconnect; no fallback for a short blip; a second outage; a failing fallback fetch. Backend: `ApiTests` "frankfurter-fallback sets the USD legs … state fallback" |
| Staleness | `ApiTests`: a pair is stale after 30 s without a tick and not before; quotes older than 30 s leave the scan; a dead feed's alert goes away with no new payload; the drill 2 expiry-instant test (B17) |
| Throttle | `ApiTests`: "throttle: 50 ticks in 100 ms scan once in the window, plus one trailing scan" |
| Cross-rate derivation | `ApiTests` "finnhub ticks update the rate store; crosses derive through USD"; `ServiceTests` derived-cross checks; "a derived cross dies with its USD leg" |
| Malformed message | `feed/test.sh`: garbage is logged and skipped while good ticks still flow; `ApiTests`: bad ticks are counted, bad shapes are 400, garbage never kills the server |

Added during the build:

- `feed/test.sh` also covers 250 ms batching, the connect heartbeat, a backend that is down, the missing key, key redaction in logs and in `--discover`, the Frankfurter request and payload, replay batching and the `replay` flag, and that the committed capture parses cleanly.
- `feed/replay_check.sh`: the Stage 2 gate end to end with no key and no network (backend, replay of the committed capture, status, garbage input).
- `feed/live_check.sh`: needs the real key and an open market; live, then an outage that falls back to Frankfurter after 15 s, then recovery.
- `scripts/drill_feed.sh`: Stage 6 drills 2 and 3 against `feed/fake_finnhub.py`, a local stand-in for Finnhub's socket (standard library only; `--loophole` streams a +0.4% EUR/GBP cycle).
- `backend/run.sh threshold-study`: the replay study in §5.
- `feed/samples/finnhub_ticks_2026-10-23.jsonl`: the committed real capture that the replay checks, the threshold study and `start-all.sh`'s keyless mode use.

---

## 9. What This Deliberately Does Not Do

- No synthetic market data in the live path: the movement on screen in live mode is real. The fake socket and the DEMO injection are test and demo tools, and the injection is labelled REPLAY, never LIVE.
- It does not feed the Order Matching Engine, which stays independent of market data by design (§1).
- It does not persist ticks. *Proposed:* "the database spec'd earlier". *Built:* the backend persists only accounts, id counters and the Huffman trade archive, as flat files in the data directory; rates live in memory, and only the current rates matter for cycle detection.
- It does not handle Finnhub plan upgrades, multi-region failover, or more than one WebSocket connection: scoped for a semester project, not a production trading desk.
- It does not stream INR (§4) and does not report arbitrage from the fallback snapshot in any meaningful sense (§4: a USD-derived graph is consistent by construction).
