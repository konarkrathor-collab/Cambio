# API Contract (Stage 3)

**Status: FROZEN** (accepted by the supervisor, 2026-10-24). The frontend (Stage 5) builds against this version. Existing endpoints, fields, status codes and error codes do not change. Additive changes (a new endpoint, a new optional response field) are allowed only with the supervisor's approval and must be added to this document in the same commit. Every example in §7 is a real request and its real response, captured from a running server by `backend/scripts/contract_examples.sh`. Re-run that script to regenerate them. Timestamps and `uptimeMs` change between runs; nothing else does.

Server: `backend/run.sh serve [--host 127.0.0.1] [--port 8080] [--data-dir data] [--seed-demo-accounts] [--min-profit 0.02] [--port-file FILE]`. It binds to `127.0.0.1:8080` by default.

---

## 1. Conventions

| Topic | Rule |
| :--- | :--- |
| Format | JSON bodies (`Content-Type: application/json`) in both directions. Bodies are limited to 1 MB (413 above that). |
| Prices | **Decimal numbers, exact.** Send a JSON number (`1.08505`) or a numeric string (`"1.08505"`). The server never converts a price through binary floating point. Responses always write prices with all the pair's tick decimals (`1.08500`). In JavaScript, read them as numbers for display, or as strings if you need the exact value. |
| Tick size | 0.00001 of the quote currency, or 0.001 when the quote is JPY. `GET /api/tickers` reports it for every pair. A limit price that is not an exact multiple is **`REJECTED` / `BAD_PRICE`**, not rounded. |
| Pairs | `EUR/USD` in bodies and query strings, `EUR-USD` in URL paths (`/api/orderbook/EUR-USD`). Both are upper-cased, and `-` is read as `/` anywhere, so `eur-usd`, `eur/usd` and `EUR%2FUSD` all work. The universe is 12 currencies (USD, EUR, GBP, JPY, AUD, CAD, CHF, INR, CNY, HKD, SGD, NZD), giving 132 pairs. |
| Order ids | Returned as `"ORD-<n>"`; `DELETE` also accepts `<n>`. Ids come from one counter, and rejected orders take one too. |
| Trader ids | Positive integers. Display them as `T101` if you like, but send `101`. |
| Quantities, cash, margin | Integers. Cash and margin are in **notional units**, where one unit is one price tick × one unit of quantity. A trade of 60 at 1.08510 moves `108510 × 60 = 6,510,600` units from the buyer's cash to the seller's. Cash can go negative (doc 08 Appendix A, known simplification). |
| Enums | `side`: `BUY`, `SELL`; `type`: `LIMIT`, `MARKET`. They are case-insensitive on input and upper-case on output. |
| Strictness | Unknown body fields are a 400, so a typo such as `"qty"` is caught rather than ignored. |
| Time | ISO-8601 UTC strings (`"2026-10-23T20:13:43.431Z"`). The feed's tick `ts` is epoch milliseconds. |
| `seq` | The engine's sequence number. It goes up by one per order submission and per cancel, and orders all state changes. |
| Threads | Every command and every read of engine state runs on one engine thread, in arrival order, so responses are consistent snapshots. |
| CORS | Only `http://localhost:5173` and `http://127.0.0.1:5173` get `Access-Control-Allow-Origin`, on every response including errors and the event stream. A preflight `OPTIONS` from them gets 204 (`Allow-Methods: GET, POST, DELETE, OPTIONS`, `Allow-Headers: Content-Type`, `Max-Age: 600`); from any other origin it gets 403 `FORBIDDEN_ORIGIN`. |

## 2. Errors

Every error has one shape, with an HTTP status:

```json
{"error": {"code": "BAD_REQUEST", "message": "side must be BUY or SELL, got \"HOLD\""}}
```

| Status | `code` | When |
| :-: | :--- | :--- |
| 400 | `BAD_JSON` | The body is not valid JSON (the message gives the offset), or it is empty. |
| 400 | `BAD_REQUEST` | Well-formed JSON with the wrong shape: a missing field, a wrong type, an unknown enum value, an unknown field, an id or `depth` that is malformed or out of range. |
| 400 | `BAD_PAYLOAD` | `POST /api/market/rates` with an unknown `source` or the wrong shape. |
| 400 | `INCOMPLETE_BODY` | The client sent less body than `Content-Length` announced. |
| 403 | `FORBIDDEN_ORIGIN` | A CORS preflight from an origin that is not allowed. |
| 404 | `NOT_FOUND` | No such endpoint. |
| 404 | `ORDER_NOT_FOUND` | `DELETE` of an order that is not resting (unknown, filled or already cancelled). |
| 404 | `ACCOUNT_NOT_FOUND` | `GET /api/accounts/{id}` for an unknown trader. |
| 404 | `UNKNOWN_PAIR` | A pair in the **path** (order book, inspector) that is not listed. A pair in an order **body** is a business rejection instead (below). |
| 405 | `METHOD_NOT_ALLOWED` | The endpoint exists but not with this method; the `Allow` header lists the methods it supports. |
| 409 | `ACCOUNT_EXISTS` | `POST /api/accounts` for a trader that already has an account. |
| 413 | `BODY_TOO_LARGE` | The body is larger than 1 MB. |
| 500 | `INTERNAL` | An unexpected failure. It is logged on the server, and the server keeps serving. |
| 501 | `NOT_IMPLEMENTED` | `POST /api/simulate` (Stage 4). |
| 503 | `TOO_MANY_STREAMS`, `SHUTTING_DOWN` | `GET /api/stream` with 32 streams already open, or while the server is stopping. |

**Business rejections are not HTTP errors.** A well-formed order that the engine refuses returns **HTTP 200** with `"status": "REJECTED"` and a `reason`. The first failing check wins, in this order (doc 08 Appendix A):

| `reason` | Meaning |
| :--- | :--- |
| `UNKNOWN_PAIR` | The pair is not in the Directory. |
| `BAD_QUANTITY` | `quantity` is not in 1 … 1,000,000,000. |
| `BAD_PRICE` | **LIMIT orders only.** The price is ≤ 0, is not a multiple of the tick, or is above 1,000,000,000 ticks. |
| `NO_LIQUIDITY` | **MARKET orders only.** There is nothing on the opposite side. |
| `MARGIN` | There is no account, or the exposure is too high. A BUY needs `qty × tick ≤ min(cash, marginLimit)`; a SELL needs `qty × tick ≤ marginLimit`. A market order is checked at the best opposite price. |

## 3. Endpoints

| Method and path | Purpose | Success | Errors |
| :--- | :--- | :-: | :--- |
| `POST /api/orders` | Submit a limit or market order | 200 | 400 |
| `DELETE /api/orders/{id}` | Cancel a resting order | 200 | 400, 404 |
| `GET /api/orderbook/{pair}?depth=N` | Book snapshot (depth 1–50, default 10) | 200 | 400, 404 |
| `GET /api/inspector/{pair}` | Internal structure state for the under-the-hood view | 200 | 404 |
| `GET /api/arbitrage/scan` | The latest scan result | 200 | |
| `GET /api/arbitrage/graph` | Currency graph: nodes, edges, current cycle | 200 | |
| `POST /api/market/rates` | Ingestion from the feed (doc 08 Appendix B) | 200 | 400 |
| `GET /api/market/rates/status` | Feed source, freshness and scan counters | 200 | |
| `GET /api/tickers?prefix=US` | Directory search | 200 | |
| `GET /api/accounts` | List accounts | 200 | |
| `GET /api/accounts/{traderId}` | One account | 200 | 400, 404 |
| `POST /api/accounts` | Create an account | 201 | 400, 409 |
| `POST /api/simulate` | Simulator | — | 501 (until Stage 4) |
| `GET /api/stream` | Server-Sent Events | 200 (`text/event-stream`) | 503 |
| `GET /api/health` | Liveness (goes through the engine thread) | 200 | |
| `OPTIONS` (any path) | CORS preflight | 204 | 403 |

### 3.1 `POST /api/orders`

Request:

| Field | Type | Required | Notes |
| :--- | :--- | :-: | :--- |
| `traderId` | integer | yes | An unknown trader is a `MARGIN` rejection. |
| `pair` | string | yes | `EUR/USD`; case-insensitive. An unknown pair is an `UNKNOWN_PAIR` rejection. |
| `side` | string | yes | `BUY` or `SELL`. |
| `type` | string | yes | `LIMIT` or `MARKET`. |
| `price` | number or numeric string | LIMIT: yes | Ignored for MARKET orders, but it must be numeric if present. |
| `quantity` | integer | yes | Values outside 1 … 10⁹ are `BAD_QUANTITY`; a non-integer is a 400. |

Response (200): `orderId`, `status` (`FILLED`, `PARTIAL`, `QUEUED`, `REJECTED`), `reason` (null unless REJECTED), `pair`, `side`, `type`, `price` (normalized to the tick for a valid limit order; as sent for a rejected one, unless its magnitude is absurd (e.g. `1e999999`), in which case null; null for market orders), `quantity`, `filledQuantity`, `restingQuantity` (left on the book; a market order's unfilled remainder is cancelled, so it is 0), `trades` (each one: `tradeId`, `pair`, `price` = the **resting** order's price, `quantity`, `buyOrderId`, `sellOrderId`, `buyerId`, `sellerId`, `takerSide`, `seq`), and `seq`.

### 3.2 `DELETE /api/orders/{id}`

`{id}` is `ORD-<n>` or `<n>`. Response (200): `orderId`, `status: "CANCELLED"`, `pair`, `side`, `price`, `remainingQuantity` (the unfilled quantity removed from the book), `seq`. 404 `ORDER_NOT_FOUND` if the order is not resting; 400 if the id is malformed.

### 3.3 `GET /api/orderbook/{pair}?depth=N`

Response: `pair`, `tickSize`, `seq`, `bids` and `asks` (both **best first**; each level is `{price, volume, orders, cumulative}`, where `cumulative` is the running volume total from the best level), and `lastTradePrice` (null before the first trade in this session).

### 3.4 `GET /api/inspector/{pair}`

For each of `bids` and `asks`: `levelCount` (all active levels), `levels` (best first, up to 50: `{price, volume, orders, head: {orderId, traderId, remaining, seq}}`, where `head` is the order at the front of the FIFO queue), `truncated` (true if more than 50 levels exist), and `shape` (`{structure: "B+ tree", order: 32, height, internalNodes, leaves, entries}`). Plus `pair` and `seq`.

### 3.5 `GET /api/arbitrage/scan`

The latest scan; it does not run a new one. Scans run after rate updates (throttled to one per 500 ms) and whenever a quote expires. Fields: `hasArbitrage`, `cycle` (currency codes with first = last; `[]` if none), `multiplier` (product of the rates around the cycle), `profitPercent`, `minProfitPercent` (the threshold: 0.02 by default; see doc 06 §5), `edges` (rates used), `excludedEdges` (stale rates left out), `belowThreshold` (a cycle existed but earned less than the threshold), `scanCount`, `scannedAt` (null before the first scan).

### 3.6 `GET /api/arbitrage/graph`

`source` (`finnhub-ws`, `frankfurter-fallback`, or null), `nodes` (all 12 currencies, each `{id, active}`, where `active` means the currency has a known rate), `edges` (every known rate: `{from, to, rate, stale, direct}`, where `direct` means it was quoted rather than derived through USD), `cycle` (the current cycle or null), `scannedAt`.

### 3.7 `POST /api/market/rates` and `GET /api/market/rates/status`

Ingestion bodies follow doc 08 Appendix B. One optional field is added: `"replay": true` marks a recorded capture, so a replay is never presented as the live feed. An empty `ticks` list is the feed's heartbeat. Response: `{accepted, rejected, errors}`; `errors` holds up to 5 messages. A bad tick is counted, never fatal; a bad payload shape is a 400. When rates arrive from a different source than the ones held, the rate store is cleared first, so live and fallback rates are never mixed. A live quote can be used for 30 s and a fallback snapshot for 2 h.

Status fields:
- **`state`:** one of `waiting`, `live`, `connected, no ticks`, `fallback`, `disconnected`.
- **`source`:** `finnhub-ws`, `frankfurter-fallback`, `cached` or `none`.
- **Feed:** `replay`, `connected`, `lastTickAt`, `lastPayloadAt`, `tickCount`, `rejectedTicks`, `payloads`, `pairsSeen`, `staleSymbols`, `fallbackAsOf`.
- **Scanning:** `scanCount`, `lastScanAt`, `lastScanFoundCycle`, `excludedEdges`, `minProfitPercent`, `lastScan`.
- **Store:** `ratesSource`, `sourceSwitches`.

### 3.8 `GET /api/tickers?prefix=P`

`prefix` is optional; without it all 132 pairs are returned. Response: `prefix` (normalized), `count`, `tickers` (each `{pair, base, quote, tickSize}`, in alphabetical order with `/` sorting after the letters).

### 3.9 Accounts

`GET /api/accounts` returns `{count, accounts: [{traderId, cash, marginLimit}]}` in creation order. `GET /api/accounts/{traderId}` returns one account. `POST /api/accounts` takes `{traderId > 0, cash >= 0, marginLimit >= 0}` (integers, no other fields) and returns **201** with the account, or 409 if it exists. Accounts are written to `<data-dir>/accounts.jsonl` immediately on creation, within 2 s of any balance change, and on shutdown, so they survive a restart. `--seed-demo-accounts` creates the demo's 12 traders when the file is empty: ids 1–11 with cash 5,000,000,000 and margin 3,000,000,000, and trader 12 with 2,000,000 of each.

### 3.10 `GET /api/health`

`{status: "ok", seq, restingOrders, accounts, sseClients, sseDropped, uptimeMs}`. The response comes from the engine thread, so a stuck engine shows up as a `500 INTERNAL` after 10 s instead of an `ok`.

## 4. Event stream: `GET /api/stream`

Server-Sent Events (`text/event-stream`; use `new EventSource("/api/stream")`). The stream opens with `retry: 3000` and a `: connected` comment. Every event carries `id:` (strictly increasing), `event:` and one line of `data:` JSON. A `: heartbeat` comment is sent after 15 s without events.

| `event` | When | `data` |
| :--- | :--- | :--- |
| `BOOK_UPDATE` | After every order or cancel that changed a pair's book: a trade, a resting remainder, or a cancel. Rejected orders change nothing and send nothing. | The touched pair's top-10 snapshot, the same shape as `GET /api/orderbook/{pair}`. |
| `TRADE` | Each fill, before that order's `BOOK_UPDATE`. | The trade, the same shape as `trades[]` in §3.1. |
| `LOOPHOLE_ALERT` | When a scan **newly finds** a cycle, or **loses** one: on a change of state, not on every scan. | The scan (§3.5), plus `active` (true = found, false = gone). |
| `RATE_TICK` | When a rate arrives, at most 4 per second per pair (extra ticks within 250 ms send no event). | `{pair, rate, ts, source}`; `ts` is null for a fallback snapshot. |

**Back-pressure.** Each client has a queue of 256 events. The engine never waits for a client. If a client's queue fills because it reads too slowly or not at all, the server drops it: it closes that stream, and `EventSource` reconnects after `retry`. Events missed while disconnected are not replayed; after reconnecting, fetch the snapshots again (`/api/orderbook`, `/api/arbitrage/scan`). At most 32 streams can be open at once.

## 5. What is deliberately not here

- There is no `GET /api/orders/{id}` and no order history. The book and the trade stream are the views. Trades are archived in the Logbook (`<data-dir>/logbook`, Huffman segments).
- Books, order ids and trade ids are per server session. Accounts persist across a restart; resting orders do not.
- There is no authentication. The server binds to localhost.

## 6. Stage 2 payloads (unchanged)

Doc 08 Appendix B, plus the optional `replay` flag (§3.7).

## 7. Captured examples

<!-- captured by backend/scripts/contract_examples.sh on 2026-10-23T20:13:42Z -->

### 7 · Health and directory
#### Health

```http
GET /api/health
```
Response: **200**

```json
{
    "status": "ok",
    "seq": 0,
    "restingOrders": 0,
    "accounts": 12,
    "sseClients": 0,
    "sseDropped": 0,
    "uptimeMs": 135
}
```

#### Ticker search (prefix is upper-cased; '-' means '/')

```http
GET /api/tickers?prefix=eur-
```
Response: **200**

```json
{
    "prefix": "EUR/",
    "count": 11,
    "tickers": [
        {
            "pair": "EUR/AUD",
            "base": "EUR",
            "quote": "AUD",
            "tickSize": 0.00001
        },
        {
            "pair": "EUR/CAD",
            "base": "EUR",
            "quote": "CAD",
            "tickSize": 0.00001
        },
        {
            "pair": "EUR/CHF",
            "base": "EUR",
            "quote": "CHF",
            "tickSize": 0.00001
        },
        {
            "pair": "EUR/CNY",
            "base": "EUR",
            "quote": "CNY",
            "tickSize": 0.00001
        },
        {
            "pair": "EUR/GBP",
            "base": "EUR",
            "quote": "GBP",
            "tickSize": 0.00001
        },
        {
            "pair": "EUR/HKD",
            "base": "EUR",
            "quote": "HKD",
            "tickSize": 0.00001
        },
        {
            "pair": "EUR/INR",
            "base": "EUR",
            "quote": "INR",
            "tickSize": 0.00001
        },
        {
            "pair": "EUR/JPY",
            "base": "EUR",
            "quote": "JPY",
            "tickSize": 0.001
        },
        {
            "pair": "EUR/NZD",
            "base": "EUR",
            "quote": "NZD",
            "tickSize": 0.00001
        },
        {
            "pair": "EUR/SGD",
            "base": "EUR",
            "quote": "SGD",
            "tickSize": 0.00001
        },
        {
            "pair": "EUR/USD",
            "base": "EUR",
            "quote": "USD",
            "tickSize": 0.00001
        }
    ]
}
```

### 7 · Accounts
#### Create an account

```http
POST /api/accounts
```
```json
{
    "traderId": 901,
    "cash": 50000000,
    "marginLimit": 40000000
}
```
Response: **201**

```json
{
    "traderId": 901,
    "cash": 50000000,
    "marginLimit": 40000000
}
```

#### Create it again: conflict

```http
POST /api/accounts
```
```json
{
    "traderId": 901,
    "cash": 1,
    "marginLimit": 1
}
```
Response: **409**

```json
{
    "error": {
        "code": "ACCOUNT_EXISTS",
        "message": "account 901 already exists"
    }
}
```

#### Invalid account body

```http
POST /api/accounts
```
```json
{
    "traderId": 902,
    "cash": -5,
    "marginLimit": 1
}
```
Response: **400**

```json
{
    "error": {
        "code": "BAD_REQUEST",
        "message": "cash and marginLimit must be >= 0"
    }
}
```

#### One account

```http
GET /api/accounts/901
```
Response: **200**

```json
{
    "traderId": 901,
    "cash": 50000000,
    "marginLimit": 40000000
}
```

#### Unknown account

```http
GET /api/accounts/999999
```
Response: **404**

```json
{
    "error": {
        "code": "ACCOUNT_NOT_FOUND",
        "message": "no account 999999"
    }
}
```

#### List accounts

```http
GET /api/accounts
```
Response: **200**

```json
{
    "count": 13,
    "accounts": [
        {
            "traderId": 1,
            "cash": 5000000000,
            "marginLimit": 3000000000
        },
        {
            "traderId": 2,
            "cash": 5000000000,
            "marginLimit": 3000000000
        },
        {
            "traderId": 3,
            "cash": 5000000000,
            "marginLimit": 3000000000
        },
        {
            "traderId": 4,
            "cash": 5000000000,
            "marginLimit": 3000000000
        },
        {
            "traderId": 5,
            "cash": 5000000000,
            "marginLimit": 3000000000
        },
        {
            "traderId": 6,
            "cash": 5000000000,
            "marginLimit": 3000000000
        },
        {
            "traderId": 7,
            "cash": 5000000000,
            "marginLimit": 3000000000
        },
        {
            "traderId": 8,
            "cash": 5000000000,
            "marginLimit": 3000000000
        },
        {
            "traderId": 9,
            "cash": 5000000000,
            "marginLimit": 3000000000
        },
        {
            "traderId": 10,
            "cash": 5000000000,
            "marginLimit": 3000000000
        },
        {
            "traderId": 11,
            "cash": 5000000000,
            "marginLimit": 3000000000
        },
        {
            "traderId": 12,
            "cash": 2000000,
            "marginLimit": 2000000
        },
        {
            "traderId": 901,
            "cash": 50000000,
            "marginLimit": 40000000
        }
    ]
}
```

### 7 · Orders
#### Limit sell that rests (QUEUED)

```http
POST /api/orders
```
```json
{
    "traderId": 901,
    "pair": "EUR/USD",
    "side": "SELL",
    "type": "LIMIT",
    "price": 1.08510,
    "quantity": 100
}
```
Response: **200**

```json
{
    "orderId": "ORD-1",
    "status": "QUEUED",
    "reason": null,
    "pair": "EUR/USD",
    "side": "SELL",
    "type": "LIMIT",
    "price": 1.08510,
    "quantity": 100,
    "filledQuantity": 0,
    "restingQuantity": 100,
    "trades": [],
    "seq": 1
}
```

#### Limit buy that rests below it

```http
POST /api/orders
```
```json
{
    "traderId": 2,
    "pair": "EUR/USD",
    "side": "BUY",
    "type": "LIMIT",
    "price": 1.08500,
    "quantity": 50
}
```
Response: **200**

```json
{
    "orderId": "ORD-2",
    "status": "QUEUED",
    "reason": null,
    "pair": "EUR/USD",
    "side": "BUY",
    "type": "LIMIT",
    "price": 1.08500,
    "quantity": 50,
    "filledQuantity": 0,
    "restingQuantity": 50,
    "trades": [],
    "seq": 2
}
```

#### Crossing limit buy: FILLED at the resting price (lower-case input, price as a string)

```http
POST /api/orders
```
```json
{
    "traderId": 1,
    "pair": "eur/usd",
    "side": "buy",
    "type": "limit",
    "price": "1.08520",
    "quantity": 60
}
```
Response: **200**

```json
{
    "orderId": "ORD-3",
    "status": "FILLED",
    "reason": null,
    "pair": "EUR/USD",
    "side": "BUY",
    "type": "LIMIT",
    "price": 1.08520,
    "quantity": 60,
    "filledQuantity": 60,
    "restingQuantity": 0,
    "trades": [
        {
            "tradeId": 1,
            "pair": "EUR/USD",
            "price": 1.08510,
            "quantity": 60,
            "buyOrderId": "ORD-3",
            "sellOrderId": "ORD-1",
            "buyerId": 1,
            "sellerId": 901,
            "takerSide": "BUY",
            "seq": 3
        }
    ],
    "seq": 3
}
```

#### Market buy

```http
POST /api/orders
```
```json
{
    "traderId": 3,
    "pair": "EUR/USD",
    "side": "BUY",
    "type": "MARKET",
    "quantity": 10
}
```
Response: **200**

```json
{
    "orderId": "ORD-4",
    "status": "FILLED",
    "reason": null,
    "pair": "EUR/USD",
    "side": "BUY",
    "type": "MARKET",
    "price": null,
    "quantity": 10,
    "filledQuantity": 10,
    "restingQuantity": 0,
    "trades": [
        {
            "tradeId": 2,
            "pair": "EUR/USD",
            "price": 1.08510,
            "quantity": 10,
            "buyOrderId": "ORD-4",
            "sellOrderId": "ORD-1",
            "buyerId": 3,
            "sellerId": 901,
            "takerSide": "BUY",
            "seq": 4
        }
    ],
    "seq": 4
}
```

#### Market sell larger than the bids: PARTIAL, remainder cancelled

```http
POST /api/orders
```
```json
{
    "traderId": 4,
    "pair": "EUR/USD",
    "side": "SELL",
    "type": "MARKET",
    "quantity": 80
}
```
Response: **200**

```json
{
    "orderId": "ORD-5",
    "status": "PARTIAL",
    "reason": null,
    "pair": "EUR/USD",
    "side": "SELL",
    "type": "MARKET",
    "price": null,
    "quantity": 80,
    "filledQuantity": 50,
    "restingQuantity": 0,
    "trades": [
        {
            "tradeId": 3,
            "pair": "EUR/USD",
            "price": 1.08500,
            "quantity": 50,
            "buyOrderId": "ORD-2",
            "sellOrderId": "ORD-5",
            "buyerId": 2,
            "sellerId": 4,
            "takerSide": "SELL",
            "seq": 5
        }
    ],
    "seq": 5
}
```

#### Rejections (HTTP 200, status REJECTED)

#### BAD_PRICE: not a multiple of the 0.00001 tick

```http
POST /api/orders
```
```json
{
    "traderId": 1,
    "pair": "EUR/USD",
    "side": "BUY",
    "type": "LIMIT",
    "price": 1.085051,
    "quantity": 10
}
```
Response: **200**

```json
{
    "orderId": "ORD-6",
    "status": "REJECTED",
    "reason": "BAD_PRICE",
    "pair": "EUR/USD",
    "side": "BUY",
    "type": "LIMIT",
    "price": 1.085051,
    "quantity": 10,
    "filledQuantity": 0,
    "restingQuantity": 0,
    "trades": [],
    "seq": 6
}
```

#### MARGIN: exposure above the account's limit

```http
POST /api/orders
```
```json
{
    "traderId": 12,
    "pair": "EUR/USD",
    "side": "BUY",
    "type": "LIMIT",
    "price": 1.08,
    "quantity": 1000
}
```
Response: **200**

```json
{
    "orderId": "ORD-7",
    "status": "REJECTED",
    "reason": "MARGIN",
    "pair": "EUR/USD",
    "side": "BUY",
    "type": "LIMIT",
    "price": 1.08000,
    "quantity": 1000,
    "filledQuantity": 0,
    "restingQuantity": 0,
    "trades": [],
    "seq": 7
}
```

#### UNKNOWN_PAIR

```http
POST /api/orders
```
```json
{
    "traderId": 1,
    "pair": "EUR/XXX",
    "side": "BUY",
    "type": "LIMIT",
    "price": 1.08,
    "quantity": 10
}
```
Response: **200**

```json
{
    "orderId": "ORD-8",
    "status": "REJECTED",
    "reason": "UNKNOWN_PAIR",
    "pair": "EUR/XXX",
    "side": "BUY",
    "type": "LIMIT",
    "price": 1.08,
    "quantity": 10,
    "filledQuantity": 0,
    "restingQuantity": 0,
    "trades": [],
    "seq": 8
}
```

#### NO_LIQUIDITY: market order on an empty side

```http
POST /api/orders
```
```json
{
    "traderId": 1,
    "pair": "USD/JPY",
    "side": "BUY",
    "type": "MARKET",
    "quantity": 10
}
```
Response: **200**

```json
{
    "orderId": "ORD-9",
    "status": "REJECTED",
    "reason": "NO_LIQUIDITY",
    "pair": "USD/JPY",
    "side": "BUY",
    "type": "MARKET",
    "price": null,
    "quantity": 10,
    "filledQuantity": 0,
    "restingQuantity": 0,
    "trades": [],
    "seq": 9
}
```

#### BAD_QUANTITY

```http
POST /api/orders
```
```json
{
    "traderId": 1,
    "pair": "EUR/USD",
    "side": "BUY",
    "type": "LIMIT",
    "price": 1.08,
    "quantity": 0
}
```
Response: **200**

```json
{
    "orderId": "ORD-10",
    "status": "REJECTED",
    "reason": "BAD_QUANTITY",
    "pair": "EUR/USD",
    "side": "BUY",
    "type": "LIMIT",
    "price": 1.08000,
    "quantity": 0,
    "filledQuantity": 0,
    "restingQuantity": 0,
    "trades": [],
    "seq": 10
}
```

#### Malformed requests (HTTP 400)

#### Invalid JSON

```http
POST /api/orders
```
```json
{
    "traderId": 1,
    
```
Response: **400**

```json
{
    "error": {
        "code": "BAD_JSON",
        "message": "expected a string key at offset 14"
    }
}
```

#### Unknown enum value

```http
POST /api/orders
```
```json
{
    "traderId": 1,
    "pair": "EUR/USD",
    "side": "HOLD",
    "type": "LIMIT",
    "price": 1.08,
    "quantity": 1
}
```
Response: **400**

```json
{
    "error": {
        "code": "BAD_REQUEST",
        "message": "side must be BUY or SELL, got \"HOLD\""
    }
}
```

#### Missing price on a LIMIT order

```http
POST /api/orders
```
```json
{
    "traderId": 1,
    "pair": "EUR/USD",
    "side": "BUY",
    "type": "LIMIT",
    "quantity": 1
}
```
Response: **400**

```json
{
    "error": {
        "code": "BAD_REQUEST",
        "message": "price is required for a LIMIT order"
    }
}
```

#### Unknown field

```http
POST /api/orders
```
```json
{
    "traderId": 1,
    "pair": "EUR/USD",
    "side": "BUY",
    "type": "LIMIT",
    "price": 1.08,
    "qty": 1
}
```
Response: **400**

```json
{
    "error": {
        "code": "BAD_REQUEST",
        "message": "unknown field \"qty\""
    }
}
```

### 7 · Order book and inspector
#### Order book (path uses '-', depth 5)

```http
GET /api/orderbook/EUR-USD?depth=5
```
Response: **200**

```json
{
    "pair": "EUR/USD",
    "tickSize": 0.00001,
    "seq": 10,
    "bids": [],
    "asks": [
        {
            "price": 1.08510,
            "volume": 30,
            "orders": 1,
            "cumulative": 30
        }
    ],
    "lastTradePrice": 1.08500
}
```

#### Unknown pair

```http
GET /api/orderbook/XXX-YYY
```
Response: **404**

```json
{
    "error": {
        "code": "UNKNOWN_PAIR",
        "message": "no such pair: XXX/YYY"
    }
}
```

#### Depth out of range

```http
GET /api/orderbook/EUR-USD?depth=99
```
Response: **400**

```json
{
    "error": {
        "code": "BAD_REQUEST",
        "message": "depth must be an integer from 1 to 50, got \"99\""
    }
}
```

#### Inspector

```http
GET /api/inspector/eur-usd
```
Response: **200**

```json
{
    "pair": "EUR/USD",
    "seq": 10,
    "bids": {
        "levelCount": 0,
        "levels": [],
        "truncated": false,
        "shape": {
            "structure": "B+ tree",
            "order": 32,
            "height": 1,
            "internalNodes": 0,
            "leaves": 1,
            "entries": 0
        }
    },
    "asks": {
        "levelCount": 1,
        "levels": [
            {
                "price": 1.08510,
                "volume": 30,
                "orders": 1,
                "head": {
                    "orderId": "ORD-1",
                    "traderId": 901,
                    "remaining": 30,
                    "seq": 1
                }
            }
        ],
        "truncated": false,
        "shape": {
            "structure": "B+ tree",
            "order": 32,
            "height": 1,
            "internalNodes": 0,
            "leaves": 1,
            "entries": 1
        }
    }
}
```

### 7 · Cancel
#### Cancel a resting order (ORD-<n> or <n>)

```http
DELETE /api/orders/ORD-1
```
Response: **200**

```json
{
    "orderId": "ORD-1",
    "status": "CANCELLED",
    "pair": "EUR/USD",
    "side": "SELL",
    "price": 1.08510,
    "remainingQuantity": 30,
    "seq": 11
}
```

#### Cancel it again

```http
DELETE /api/orders/ORD-1
```
Response: **404**

```json
{
    "error": {
        "code": "ORDER_NOT_FOUND",
        "message": "ORD-1 is not resting (unknown, filled or already cancelled)"
    }
}
```

#### Malformed order id

```http
DELETE /api/orders/abc
```
Response: **400**

```json
{
    "error": {
        "code": "BAD_REQUEST",
        "message": "order id must be ORD-<n> or <n>, got \"abc\""
    }
}
```

### 7 · Market data and arbitrage
#### Feed ticks (finnhub-ws), one of them bad

```http
POST /api/market/rates
```
```json
{
    "source": "finnhub-ws",
    "ticks": [
        {
            "symbol": "OANDA:EUR_USD",
            "price": 1.08,
            "ts": 1792783468459
        },
        {
            "symbol": "OANDA:GBP_USD",
            "price": 1.30,
            "ts": 1792783468460
        },
        {
            "symbol": "OANDA:EUR_GBP",
            "price": 0.83409,
            "ts": 1792783468461
        },
        {
            "symbol": "OANDA:XAU_USD",
            "price": 2400,
            "ts": 1792783468462
        }
    ]
}
```
Response: **200**

```json
{
    "accepted": 3,
    "rejected": 1,
    "errors": [
        "OANDA:XAU_USD: unknown symbol"
    ]
}
```

#### Feed status

```http
GET /api/market/rates/status
```
Response: **200**

```json
{
    "state": "live",
    "source": "finnhub-ws",
    "replay": false,
    "connected": true,
    "lastTickAt": "2026-10-23T19:24:28.461Z",
    "lastPayloadAt": "2026-10-23T20:13:43.430Z",
    "tickCount": 3,
    "rejectedTicks": 1,
    "payloads": 1,
    "pairsSeen": 3,
    "staleSymbols": [],
    "fallbackAsOf": null,
    "scanCount": 1,
    "lastScanAt": "2026-10-23T20:13:43.431Z",
    "lastScanFoundCycle": true,
    "excludedEdges": 0,
    "minProfitPercent": 0.02,
    "ratesSource": "finnhub-ws",
    "sourceSwitches": 0,
    "lastScan": {
        "hasArbitrage": true,
        "cycle": [
            "GBP",
            "USD",
            "EUR",
            "GBP"
        ],
        "multiplier": 1.003997222222222,
        "profitPercent": 0.39972222222219855,
        "edges": 6,
        "excludedEdges": 0,
        "belowThreshold": false
    }
}
```

#### Latest scan

```http
GET /api/arbitrage/scan
```
Response: **200**

```json
{
    "hasArbitrage": true,
    "cycle": [
        "GBP",
        "USD",
        "EUR",
        "GBP"
    ],
    "multiplier": 1.003997222222222,
    "profitPercent": 0.39972222222219855,
    "minProfitPercent": 0.02,
    "edges": 6,
    "excludedEdges": 0,
    "belowThreshold": false,
    "scanCount": 1,
    "scannedAt": "2026-10-23T20:13:43.431Z"
}
```

#### Currency graph

```http
GET /api/arbitrage/graph
```
Response: **200**

```json
{
    "source": "finnhub-ws",
    "nodes": [
        {
            "id": "USD",
            "active": true
        },
        {
            "id": "EUR",
            "active": true
        },
        {
            "id": "GBP",
            "active": true
        },
        {
            "id": "JPY",
            "active": false
        },
        {
            "id": "AUD",
            "active": false
        },
        {
            "id": "CAD",
            "active": false
        },
        {
            "id": "CHF",
            "active": false
        },
        {
            "id": "INR",
            "active": false
        },
        {
            "id": "CNY",
            "active": false
        },
        {
            "id": "HKD",
            "active": false
        },
        {
            "id": "SGD",
            "active": false
        },
        {
            "id": "NZD",
            "active": false
        }
    ],
    "edges": [
        {
            "from": "USD",
            "to": "EUR",
            "rate": 0.9259259259259258,
            "stale": false,
            "direct": true
        },
        {
            "from": "USD",
            "to": "GBP",
            "rate": 0.7692307692307692,
            "stale": false,
            "direct": true
        },
        {
            "from": "EUR",
            "to": "USD",
            "rate": 1.08,
            "stale": false,
            "direct": true
        },
        {
            "from": "EUR",
            "to": "GBP",
            "rate": 0.83409,
            "stale": false,
            "direct": true
        },
        {
            "from": "GBP",
            "to": "USD",
            "rate": 1.3,
            "stale": false,
            "direct": true
        },
        {
            "from": "GBP",
            "to": "EUR",
            "rate": 1.198911388459279,
            "stale": false,
            "direct": true
        }
    ],
    "cycle": [
        "GBP",
        "USD",
        "EUR",
        "GBP"
    ],
    "scannedAt": "2026-10-23T20:13:43.431Z"
}
```

#### Fallback snapshot (frankfurter-fallback): the store switches source

```http
POST /api/market/rates
```
```json
{
    "source": "frankfurter-fallback",
    "base": "USD",
    "asOf": "2026-10-23",
    "rates": {
        "EUR": 0.92593,
        "GBP": 0.76923,
        "JPY": 156.87,
        "INR": 96.73,
        "BGN": 1.81
    }
}
```
Response: **200**

```json
{
    "accepted": 4,
    "rejected": 0,
    "errors": []
}
```

#### Unknown source

```http
POST /api/market/rates
```
```json
{
    "source": "bloomberg",
    "ticks": []
}
```
Response: **400**

```json
{
    "error": {
        "code": "BAD_PAYLOAD",
        "message": "unknown source \"bloomberg\" (expected \"finnhub-ws\" or \"frankfurter-fallback\")"
    }
}
```

### 7 · Other
#### Simulator (Stage 4)

```http
POST /api/simulate
```
```json
{}
```
Response: **501**

```json
{
    "error": {
        "code": "NOT_IMPLEMENTED",
        "message": "POST /api/simulate arrives in Stage 4"
    }
}
```

#### Unknown endpoint

```http
GET /api/nope
```
Response: **404**

```json
{
    "error": {
        "code": "NOT_FOUND",
        "message": "no such endpoint: /api/nope"
    }
}
```

#### Wrong method

```http
PUT /api/orders
```
```json
{}
```
Response: **405**

```json
{
    "error": {
        "code": "METHOD_NOT_ALLOWED",
        "message": "PUT is not supported on /api/orders"
    }
}
```

#### CORS preflight from the Vite dev origin

```http
OPTIONS /api/orders   (Origin: http://localhost:5173)
```
```http
HTTP/1.1 204 No Content
Access-control-allow-headers: Content-Type
Access-control-max-age: 600
Access-control-allow-methods: GET, POST, DELETE, OPTIONS
Access-control-allow-origin: http://localhost:5173
Vary: Origin

```

### 7 · Event stream

```http
GET /api/stream     (recorded while the order and market examples above ran)
```
```text
retry: 3000
: connected

id: 1
event: BOOK_UPDATE
data: {"pair":"EUR/USD","tickSize":0.00001,"seq":1,"bids":[],"asks":[{"price":1.08510,"volume":100,"orders":1,"cumulative":100}],"lastTradePrice":null}

id: 2
event: BOOK_UPDATE
data: {"pair":"EUR/USD","tickSize":0.00001,"seq":2,"bids":[{"price":1.08500,"volume":50,"orders":1,"cumulative":50}],"asks":[{"price":1.08510,"volume":100,"orders":1,"cumulative":100}],"lastTradePrice":null}

id: 3
event: TRADE
data: {"tradeId":1,"pair":"EUR/USD","price":1.08510,"quantity":60,"buyOrderId":"ORD-3","sellOrderId":"ORD-1","buyerId":1,"sellerId":901,"takerSide":"BUY","seq":3}

id: 4
event: BOOK_UPDATE
data: {"pair":"EUR/USD","tickSize":0.00001,"seq":3,"bids":[{"price":1.08500,"volume":50,"orders":1,"cumulative":50}],"asks":[{"price":1.08510,"volume":40,"orders":1,"cumulative":40}],"lastTradePrice":1.08510}

id: 5
event: TRADE
data: {"tradeId":2,"pair":"EUR/USD","price":1.08510,"quantity":10,"buyOrderId":"ORD-4","sellOrderId":"ORD-1","buyerId":3,"sellerId":901,"takerSide":"BUY","seq":4}

id: 6
event: BOOK_UPDATE
data: {"pair":"EUR/USD","tickSize":0.00001,"seq":4,"bids":[{"price":1.08500,"volume":50,"orders":1,"cumulative":50}],"asks":[{"price":1.08510,"volume":30,"orders":1,"cumulative":30}],"lastTradePrice":1.08510}

id: 7
event: TRADE
data: {"tradeId":3,"pair":"EUR/USD","price":1.08500,"quantity":50,"buyOrderId":"ORD-2","sellOrderId":"ORD-5","buyerId":2,"sellerId":4,"takerSide":"SELL","seq":5}

id: 8
event: BOOK_UPDATE
data: {"pair":"EUR/USD","tickSize":0.00001,"seq":5,"bids":[],"asks":[{"price":1.08510,"volume":30,"orders":1,"cumulative":30}],"lastTradePrice":1.08500}

id: 9
event: BOOK_UPDATE
data: {"pair":"EUR/USD","tickSize":0.00001,"seq":11,"bids":[],"asks":[],"lastTradePrice":1.08500}

id: 10
event: RATE_TICK
data: {"pair":"EUR/USD","rate":1.08,"ts":1792783468459,"source":"finnhub-ws"}

id: 11
event: RATE_TICK
data: {"pair":"GBP/USD","rate":1.3,"ts":1792783468460,"source":"finnhub-ws"}

id: 12
event: RATE_TICK
data: {"pair":"EUR/GBP","rate":0.83409,"ts":1792783468461,"source":"finnhub-ws"}

id: 13
event: LOOPHOLE_ALERT
data: {"hasArbitrage":true,"cycle":["GBP","USD","EUR","GBP"],"multiplier":1.003997222222222,"profitPercent":0.39972222222219855,"minProfitPercent":0.02,"edges":6,"excludedEdges":0,"belowThreshold":false,"scanCount":1,"scannedAt":"2026-10-23T20:13:43.431Z","active":true}

id: 14
event: RATE_TICK
data: {"pair":"USD/EUR","rate":0.92593,"ts":null,"source":"frankfurter-fallback"}

id: 15
event: RATE_TICK
data: {"pair":"USD/GBP","rate":0.76923,"ts":null,"source":"frankfurter-fallback"}

id: 16
event: RATE_TICK
data: {"pair":"USD/JPY","rate":156.87,"ts":null,"source":"frankfurter-fallback"}

id: 17
event: RATE_TICK
data: {"pair":"USD/INR","rate":96.73,"ts":null,"source":"frankfurter-fallback"}

id: 18
event: LOOPHOLE_ALERT
data: {"hasArbitrage":false,"cycle":[],"multiplier":1,"profitPercent":0,"minProfitPercent":0.02,"edges":20,"excludedEdges":0,"belowThreshold":false,"scanCount":2,"scannedAt":"2026-10-23T20:13:43.933Z","active":false}

```
