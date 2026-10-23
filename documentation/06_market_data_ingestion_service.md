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

> **Status: partially verified.** We confirmed `/forex/symbol?exchange=oanda` is a free, working endpoint that returns the exact symbol strings (`OANDA:EUR_USD` format). What's still outstanding is calling it with a live key to get the *actual* list of OANDA pairs it returns, so the 8-currency roster can be finalized against real coverage rather than assumption. This is a 15-minute task once the (regenerated) API key is in hand — call `/forex/symbol?exchange=oanda&token=$FINNHUB_API_KEY` and check which of the 56 pairs in an 8-currency complete graph come back directly vs. need USD-derivation.

---

## 5. Config

| Parameter | Default | Meaning |
| :--- | :-: | :--- |
| `FINNHUB_API_KEY` | *(env var, required)* | Auth token for the Finnhub WebSocket (and the one-time build-time discovery REST calls). The Frankfurter fallback is keyless and does not use it |
| `SUBSCRIBE_SYMBOLS` | the ~12-16 direct pairs from §4 | Sent as `{"type":"subscribe","symbol":"OANDA:EUR_USD"}` per symbol on connect |
| `SCAN_THROTTLE_MS` | `500` | Minimum gap between consecutive Bellman-Ford scans, even under a tick burst |
| `RECONNECT_BACKOFF_MS` | `1000, 2000, 4000, 8000, 10000 (cap)` | WebSocket reconnect delay sequence on disconnect |
| `REST_FALLBACK_TRIGGER_MS` | `15000` | How long the WebSocket must stay disconnected before the Frankfurter fallback activates (avoid flapping to the fallback on a brief reconnect blip) |
| `REST_FALLBACK_POLL_MS` | `3,600,000` (1 hr) | Poll interval for the Frankfurter fallback — it's a daily-snapshot API, so polling it faster than hourly gains nothing |
| `STALE_AFTER_MS` | `30000` | If no tick (WebSocket) updates a given pair within this window and the Frankfurter fallback hasn't activated yet, mark it `stale` on the dashboard |

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
