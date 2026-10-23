# Market Data Ingestion Service — Specification

This document specs the service that bridges real-world currency data into the Currency Loophole Detector. It does **not** feed the Order Matching Engine — per the architectural discussion that produced this doc, the matching engine operates on synthetic/simulated order flow by design (it *is* the exchange; it doesn't consume one), so this ingestion service is scoped entirely to the arbitrage-detection subsystem.

**Stack revision (faculty-confirmed):** the ingestion service is now a standalone **Python process** (`feed/feed.py`) that only fetches Finnhub ticks and Frankfurter snapshots and **forwards** them to the Java backend by `POST /api/market/rates` (payloads in doc 08, Appendix B). All computation described below — cross-rate derivation, graph construction, staleness decisions, the throttled Bellman-Ford scan, the status endpoint — happens in the **Java** backend. Reconnect/backoff and the Frankfurter fallback live in the Python feed, with the thresholds in §5 unchanged. See [08_build_plan_and_acceptance.md](./08_build_plan_and_acceptance.md) Stage 2.

**Revision note:** the original version of this spec used a free daily-snapshot API (Frankfurter) plus a synthetic "jitter" layer to fake movement between snapshots. We've replaced that with **Finnhub's real-time forex WebSocket feed** (sourced from OANDA), which pushes genuine live ticks — so the synthetic jitter engine, mean-reversion math, and bid/ask-spread modeling from the earlier version are **no longer needed and have been removed**. The live movement on the dashboard is now real market data, not a simulation of it.

---

## 1. Responsibility

Maintain a live, continuously-updating set of currency exchange rates, sourced from Finnhub's real-time forex WebSocket stream, and feed every update into the existing graph/Bellman-Ford pipeline described in [02_system_architecture_and_design.md](./02_system_architecture_and_design.md).

---

## 2. Data Source

| Role | Provider | Endpoint | Auth | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **Primary (real-time)** | Finnhub | `wss://ws.finnhub.io?token=$FINNHUB_API_KEY` | API key (query param) | Push-based trade ticks, sourced from OANDA; free tier includes real-time (not delayed) forex |
| **Discovery (one-time, at build time)** | Finnhub REST | `GET /forex/symbol?exchange=oanda`, `GET /forex/exchange` | API key | **Free.** Used once during development to confirm exact symbol strings (e.g. `OANDA:EUR_USD`) — not called at runtime |
| **Fallback (degraded)** | Frankfurter | `GET https://api.frankfurter.dev/v1/latest?base=USD` | None | Only kicks in if the Finnhub WebSocket stays disconnected past `REST_FALLBACK_TRIGGER_MS` |

> **Verified against Finnhub's live docs** (not assumed): `/forex/exchange` confirms the available exchanges are `oanda`, `fxcm`, `forex.com`, `ic markets`, `fxpro`. Finnhub's own WebSocket documentation states *"The following FX brokers do not support streaming: FXCM, Forex.com, FHFX"* — OANDA is not on that list, so it does support live streaming, which is why it's our source. **Important correction from an earlier draft of this spec:** Finnhub's `/forex/candle` and `/forex/rates` ("All Rates") REST endpoints are both marked **Premium Access Required** — free tier has *no* REST forex data beyond the symbol/exchange discovery endpoints. That means Finnhub's own REST API cannot serve as a fallback. We use **Frankfurter** for that instead (same free, keyless API from the earlier version of this document) — it's lower-resolution (daily snapshot) but it's a genuine safety net rather than nothing.

**Credential handling:** the API key is read from the environment variable `FINNHUB_API_KEY` at process startup. It is never hardcoded, never logged, and never committed — it lives in a `.env` file listed in `.gitignore`. Regenerate the key immediately if it is ever exposed (e.g. pasted into a chat, screenshot, or committed by accident) via the Finnhub dashboard's "Regenerate" action.

**Symbol format:** Finnhub's forex symbols are prefixed by source, e.g. `OANDA:EUR_USD` — confirmed directly from Finnhub's own `/forex/candle` documentation example, not assumed. We subscribe to the direct pairs needed to build our currency graph (see §4) — not every possible cross-pair is directly streamed, so cross-rates for pairs without a direct feed are derived, same as before. The free-tier WebSocket allows roughly 50 concurrent symbol subscriptions, comfortably above the ~12-16 pairs this project needs.

---

## 3. Architecture

```
          wss://ws.finnhub.io  (persistent connection, auto-reconnect)
                     |
                     v
          +----------------------+
          |   WebSocketListener  |   subscribes to ~12-16 OANDA:* symbols on connect
          |  (event-driven, not  |   re-subscribes automatically after any reconnect
          |   polled)            |
          +----------------------+
                     |  on each {"type":"trade", data:[{s,p,t,v}, ...]} message
                     v
          +----------------------+
          |      RateStore       |   liveRates: Map<(from,to), R>  — updated in place,
          |  (in-memory)         |   per symbol, as ticks arrive — no fixed tick interval
          +----------------------+
                     |
          +----------+-----------+
          |                      |
          v                      v
  Graph edge update:      WS broadcast to frontend:
  w = -ln(R)  (only       {"type":"RATE_TICK", "pair":"EUR/USD",
   the changed edge)       "rate":1.0852, "timestamp":...}
          |
          v
  Bellman-Ford scan        <- throttled to at most once per THROTTLE_MS
  (existing engine)           (see §5) so a burst of ticks doesn't trigger
          |                   redundant full graph scans
          v
  negative cycle found? --> WS "LOOPHOLE_ALERT"
```

Unlike the earlier design, there is no scheduled jitter loop — the `RateStore` updates reactively, exactly when Finnhub pushes a tick. The only scheduled thing left is the Bellman-Ford scan throttle, which exists purely so a burst of simultaneous ticks (common right after connecting) doesn't trigger dozens of redundant graph scans in the same second.

---

## 4. Currency Coverage & Graph Construction

Pick a fixed roster of currencies for the demo graph, e.g.: `USD, EUR, GBP, JPY, AUD, CAD, CHF, INR` (8 currencies → up to 56 directed edges for a complete graph). Subscribe to the direct OANDA pairs Finnhub streams for this roster (e.g. `EUR_USD`, `GBP_USD`, `USD_JPY`, `USD_INR`, `AUD_USD`, `USD_CAD`, `USD_CHF`, `EUR_GBP`, `EUR_JPY`, ...).

Not every cross-pair in an 8-currency complete graph is directly streamed by Finnhub. For pairs without a direct feed, derive the rate from two direct-quoted legs through USD (`R(a,b) = R(USD,b) / R(USD,a)`), same approach as the previous Frankfurter-based design — except now the *inputs* to that derivation are live ticks instead of a daily snapshot, so the derived cross-rate is live too.

> **Status: verified (2026-10-23).** `feed/feed.py --discover` called `/forex/symbol?exchange=oanda` once with a live key and recorded the result in `feed/symbols.json`. OANDA lists 120 symbols. Of the 28 currency pairs in the 8-currency roster, **21 are streamed directly**: every pair among USD, EUR, GBP, JPY, AUD, CAD and CHF. **OANDA has no INR pair at all**, not even USD/INR, so the 7 INR pairs cannot be streamed or derived through USD.
>
> **Consequence: INR is fallback-only.** In live mode the scanner's graph has **7 currencies and 21 pairs (42 directed edges)**. INR appears only while the Frankfurter fallback snapshot is the source, because that snapshot quotes every roster currency against USD. Order trading is unaffected: the Directory still lists the INR pairs, and the matching engine trades them like any other pair. Only the arbitrage graph lacks a live INR rate.

---

## 5. Config

| Parameter | Default | Meaning |
| :--- | :-: | :--- |
| `FINNHUB_API_KEY` | *(env var, required)* | Auth token for the Finnhub WebSocket (and the one-time build-time discovery REST calls). The Frankfurter fallback is keyless and does not use it |
| `SUBSCRIBE_SYMBOLS` | the ~12-16 direct pairs from §4 | Sent as `{"type":"subscribe","symbol":"OANDA:EUR_USD"}` per symbol on connect |
| `SCAN_THROTTLE_MS` | `500` | Minimum gap between consecutive Bellman-Ford scans, even under a tick burst |
| `MIN_PROFIT_PERCENT` | `0.02` | Smallest cycle profit reported as arbitrage. Set it with `serve --min-profit` or the environment variable. The default is data-driven: see the table below |
| `RECONNECT_BACKOFF_MS` | `1000, 2000, 4000, 8000, 10000 (cap)` | WebSocket reconnect delay sequence on disconnect |
| `REST_FALLBACK_TRIGGER_MS` | `15000` | How long the WebSocket must stay disconnected before the Frankfurter fallback activates (avoid flapping to the fallback on a brief reconnect blip) |
| `REST_FALLBACK_POLL_MS` | `3,600,000` (1 hr) | Poll interval for the Frankfurter fallback — it's a daily-snapshot API, so polling it faster than hourly gains nothing |
| `STALE_AFTER_MS` | `30000` | If no tick (WebSocket) updates a given pair within this window and the Frankfurter fallback hasn't activated yet, mark it `stale` on the dashboard |

**Choosing the profit threshold.** Finnhub's OANDA ticks alternate between bid and ask, so cycles of about 0.01% appear constantly in real data and cannot be traded. Replaying the committed capture (`feed/samples/finnhub_ticks_2026-10-23.jsonl`: 3,690 ticks over 299 s, 432 scans throttled to 500 ms, each quote usable for 30 s) through `backend/run.sh threshold-study` gives:

| Threshold | Scanner reports a cycle | Exhaustive search: some cycle reaches it | Triangles only: some triangle reaches it |
| :-: | :-: | :-: | :-: |
| 0.001% | 429 / 432 (99.31%) | 429 / 432 (99.31%) | 429 / 432 (99.31%) |
| 0.005% | 427 / 432 (98.84%) | 427 / 432 (98.84%) | 427 / 432 (98.84%) |
| 0.010% | 58 / 432 (13.43%) | 286 / 432 (66.20%) | 4 / 432 (0.93%) |
| 0.020% | 0 / 432 (0.00%) | 0 / 432 (0.00%) | 0 / 432 (0.00%) |
| 0.050% | 0 / 432 (0.00%) | 0 / 432 (0.00%) | 0 / 432 (0.00%) |

Best-cycle profit per scan, exhaustive: p50 0.01070%, p90 0.01314%, p99 0.01562%, max 0.01652%. The default is the smallest candidate for which fewer than 1% of scans report a cycle: **0.02%**. At 0.010% the scanner reports far fewer scans than actually contain a qualifying cycle. That is the Bellman-Ford detection gap documented in `backend/BUGS.md` B4 and B8. Since the Stage 3 addendum the scanner also checks every triangle exactly, so a qualifying triangle is never missed. The "triangles only" column shows that the real near-threshold cycles are almost never triangles: in the 286 scans at 0.010%, the best cycle has 5 legs in 4 of them, 6 legs in 38 and 7 legs in 244. The gap that remains at 0.010% is therefore all longer cycles. It does not affect the 0.02% default, because no real cycle reached 0.02%. **Decision (doc 08 Stage 4 addendum):** this is an accepted, documented limitation. Triangles, the project's definition of the opportunity, are detected exactly. Longer loops are best-effort, because near the threshold they are bid/ask noise accumulated over many legs, not tradeable opportunities. No exhaustive search is built.

---

## 6. Failure Handling

| Failure | Behavior |
| :--- | :--- |
| WebSocket disconnects | Reconnect with exponential backoff (`RECONNECT_BACKOFF_MS`); on reconnect, immediately re-send all `SUBSCRIBE_SYMBOLS` subscriptions (Finnhub does not remember prior subscriptions across a new connection) |
| WebSocket down past `REST_FALLBACK_TRIGGER_MS` | Switch to polling **Frankfurter** (not Finnhub REST — see §2 correction) hourly, so rates keep updating at a coarser resolution instead of freezing entirely; clearly flag `source: "frankfurter-fallback"` on the dashboard so it's never silently presented as the live feed; switch back to WebSocket-driven updates the moment Finnhub reconnects |
| Both Finnhub and Frankfurter unreachable | Keep serving the last-known-good `liveRates`; mark affected pairs `stale: true` once `STALE_AFTER_MS` elapses — surfaced on the dashboard, never failed silently |
| Malformed/partial trade message | Log and discard that single message; never let one bad tick crash the listener |
| `FINNHUB_API_KEY` missing or invalid at startup | Fail fast with a clear error at boot — don't start the backend in a state where arbitrage detection silently does nothing |

---

## 7. API Surface Changes

Same shape as the previous version of this spec, with the status endpoint updated to reflect push-based sourcing:

- `POST /api/market/rates` — unchanged; still available as a manual admin override for testing specific scenarios on demand.
- `GET /api/market/rates/status` → `{ "source": "finnhub-ws" | "frankfurter-fallback" | "cached", "connected": true, "lastTickAt": "...", "staleSymbols": [] }` — lets the frontend (and your faculty demo) show, honestly, that the feed is live and where each number came from.
- WebSocket to the frontend gains `{"type": "RATE_TICK", "pair": "EUR/USD", "rate": 1.0852, "timestamp": ...}`, broadcast on every real tick received from Finnhub — no fixed interval, because the source itself isn't on a fixed interval.

---

## 8. Testing Additions (for Phase 3 of the roadmap)

- **Reconnect test:** simulate a WebSocket drop, assert the listener reconnects within the backoff schedule and re-subscribes to every symbol in `SUBSCRIBE_SYMBOLS`.
- **Fallback activation test:** simulate a WebSocket that never reconnects, assert the service switches to Frankfurter polling once `REST_FALLBACK_TRIGGER_MS` elapses, and that `source` correctly reports `frankfurter-fallback`.
- **Staleness test:** stop feeding ticks for one symbol, assert it's flagged `stale` after `STALE_AFTER_MS` and not before.
- **Throttle test:** feed 50 ticks within 100ms, assert Bellman-Ford is invoked at most `ceil(100 / SCAN_THROTTLE_MS)` times, not 50 times.
- **Cross-rate derivation test:** for a pair with no direct feed, assert the derived rate matches `R(USD,b) / R(USD,a)` computed from the two live legs at that instant.
- **Malformed message test:** feed a deliberately corrupt trade payload, assert the listener logs and continues rather than crashing.

---

## 9. What This Deliberately Does Not Do

- No synthetic data of any kind — this version's entire point is that the movement on screen is real, which is both simpler to build and more credible to present than the earlier jitter-based design.
- It does not feed the Order Matching Engine — that remains fully synthetic by design, per §1.
- It does not persist historical tick-level data to the database spec'd earlier in this project — only the current live rate matters for cycle detection; archiving is reserved for completed *trades*, not raw ticks, to keep the persistence layer's scope narrow.
- It does not attempt to handle Finnhub plan upgrades, multi-region failover, or anything beyond a single WebSocket connection — appropriately scoped for a semester project, not a production trading desk.
