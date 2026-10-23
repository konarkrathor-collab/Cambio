#!/usr/bin/env bash
# Captures real requests and responses from a fresh server, as Markdown, for
# documentation/09_api_contract.md. Every example in that document comes from this script.
#   backend/scripts/contract_examples.sh > examples.md
# curl and awk only.
set -eu
cd "$(dirname "$0")/.."

work="$(mktemp -d "${TMPDIR:-/tmp}/ads-contract.XXXXXX")"
server=""
cleanup() {
  if [ -n "$server" ]; then kill "$server" 2>/dev/null || true; wait "$server" 2>/dev/null || true; fi
  rm -rf "$work"
}
trap cleanup EXIT

./build.sh >/dev/null
./run.sh serve --port 0 --port-file "$work/port" --data-dir "$work/data" --seed-demo-accounts \
  >"$work/server.log" 2>&1 &
server=$!
while [ ! -s "$work/port" ]; do sleep 0.1; done
BASE="http://127.0.0.1:$(cat "$work/port")"

# Re-indents JSON without re-parsing any value (a JSON library would rewrite 1.08520 as
# 1.0852, and prices keep their tick decimals). Plain awk: present on macOS and Linux.
pretty() {
  awk '
    function pad(n,   s, k) { s = ""; for (k = 0; k < n; k++) s = s "    "; return s }
    {
      line = $0; out = ""; ind = 0; instr = 0; esc = 0
      for (i = 1; i <= length(line); i++) {
        c = substr(line, i, 1)
        if (instr) {
          out = out c
          if (esc) esc = 0; else if (c == "\\") esc = 1; else if (c == "\"") instr = 0
          continue
        }
        if (c == "\"") { instr = 1; out = out c; continue }
        if (c == "{" || c == "[") {
          nxt = substr(line, i + 1, 1)
          if ((c == "{" && nxt == "}") || (c == "[" && nxt == "]")) { out = out c nxt; i++; continue }
          ind++; out = out c "\n" pad(ind); continue
        }
        if (c == "}" || c == "]") { ind--; out = out "\n" pad(ind) c; continue }
        if (c == ",") { out = out ",\n" pad(ind); continue }
        if (c == ":") { out = out ": "; continue }
        out = out c
      }
      print out
    }' "$1"
}

# ex "Title" METHOD PATH [JSON_BODY]
ex() {
  title="$1"; method="$2"; path="$3"; data="${4:-}"
  echo "#### $title"
  echo
  echo '```http'
  echo "$method $path"
  echo '```'
  if [ -n "$data" ]; then
    printf '%s' "$data" >"$work/req"
    echo '```json'
    pretty "$work/req"
    echo '```'
    code="$(curl -s -o "$work/res" -w '%{http_code}' -X "$method" -H 'Content-Type: application/json' \
      --data-binary "$data" "$BASE$path")"
  else
    code="$(curl -s -o "$work/res" -w '%{http_code}' -X "$method" "$BASE$path")"
  fi
  echo "Response: **$code**"
  echo
  echo '```json'
  pretty "$work/res"
  echo '```'
  echo
}

echo "<!-- captured by backend/scripts/contract_examples.sh on $(date -u '+%Y-%m-%dT%H:%M:%SZ') -->"
echo
echo "### Health and directory"
ex "Health" GET /api/health
ex "Ticker search (prefix is upper-cased; '-' means '/')" GET "/api/tickers?prefix=eur-"
echo "### Accounts"
ex "Create an account" POST /api/accounts '{"traderId":901,"cash":50000000,"marginLimit":40000000}'
ex "Create it again: conflict" POST /api/accounts '{"traderId":901,"cash":1,"marginLimit":1}'
ex "Invalid account body" POST /api/accounts '{"traderId":902,"cash":-5,"marginLimit":1}'
ex "One account" GET /api/accounts/901
ex "Unknown account" GET /api/accounts/999999
ex "List accounts" GET /api/accounts

curl -s -N --max-time 6 "$BASE/api/stream" >"$work/sse.txt" 2>/dev/null &
sse=$!
sleep 0.5

echo "### Orders"
ex "Limit sell that rests (QUEUED)" POST /api/orders '{"traderId":901,"pair":"EUR/USD","side":"SELL","type":"LIMIT","price":1.08510,"quantity":100}'
ex "Limit buy that rests below it" POST /api/orders '{"traderId":2,"pair":"EUR/USD","side":"BUY","type":"LIMIT","price":1.08500,"quantity":50}'
ex "Crossing limit buy: FILLED at the resting price (lower-case input, price as a string)" POST /api/orders '{"traderId":1,"pair":"eur/usd","side":"buy","type":"limit","price":"1.08520","quantity":60}'
ex "Market buy" POST /api/orders '{"traderId":3,"pair":"EUR/USD","side":"BUY","type":"MARKET","quantity":10}'
ex "Market sell larger than the bids: PARTIAL, remainder cancelled" POST /api/orders '{"traderId":4,"pair":"EUR/USD","side":"SELL","type":"MARKET","quantity":80}'
echo "#### Rejections (HTTP 200, status REJECTED)"
echo
ex "BAD_PRICE: not a multiple of the 0.00001 tick" POST /api/orders '{"traderId":1,"pair":"EUR/USD","side":"BUY","type":"LIMIT","price":1.085051,"quantity":10}'
ex "MARGIN: exposure above the account's limit" POST /api/orders '{"traderId":12,"pair":"EUR/USD","side":"BUY","type":"LIMIT","price":1.08,"quantity":1000}'
ex "UNKNOWN_PAIR" POST /api/orders '{"traderId":1,"pair":"EUR/XXX","side":"BUY","type":"LIMIT","price":1.08,"quantity":10}'
ex "NO_LIQUIDITY: market order on an empty side" POST /api/orders '{"traderId":1,"pair":"USD/JPY","side":"BUY","type":"MARKET","quantity":10}'
ex "BAD_QUANTITY" POST /api/orders '{"traderId":1,"pair":"EUR/USD","side":"BUY","type":"LIMIT","price":1.08,"quantity":0}'
echo "#### Malformed requests (HTTP 400)"
echo
ex "Invalid JSON" POST /api/orders '{"traderId":1,'
ex "Unknown enum value" POST /api/orders '{"traderId":1,"pair":"EUR/USD","side":"HOLD","type":"LIMIT","price":1.08,"quantity":1}'
ex "Missing price on a LIMIT order" POST /api/orders '{"traderId":1,"pair":"EUR/USD","side":"BUY","type":"LIMIT","quantity":1}'
ex "Unknown field" POST /api/orders '{"traderId":1,"pair":"EUR/USD","side":"BUY","type":"LIMIT","price":1.08,"qty":1}'
echo "### Recent trades"
ex "Recent trades, newest first (pair optional, limit 1-500)" GET "/api/trades?pair=EUR-USD&limit=5"
ex "Limit out of range" GET "/api/trades?limit=501"
echo "### Order book and inspector"
ex "Order book (path uses '-', depth 5)" GET "/api/orderbook/EUR-USD?depth=5"
ex "Unknown pair" GET /api/orderbook/XXX-YYY
ex "Depth out of range" GET "/api/orderbook/EUR-USD?depth=99"
ex "Inspector" GET /api/inspector/eur-usd
echo "### Cancel"
ex "Cancel a resting order (ORD-<n> or <n>)" DELETE /api/orders/ORD-1
ex "Cancel it again" DELETE /api/orders/ORD-1
ex "Malformed order id" DELETE /api/orders/abc
echo "### Market data and arbitrage"
ex "Feed ticks (finnhub-ws), one of them bad" POST /api/market/rates '{"source":"finnhub-ws","ticks":[{"symbol":"OANDA:EUR_USD","price":1.08,"ts":1792783468459},{"symbol":"OANDA:GBP_USD","price":1.30,"ts":1792783468460},{"symbol":"OANDA:EUR_GBP","price":0.83409,"ts":1792783468461},{"symbol":"OANDA:XAU_USD","price":2400,"ts":1792783468462}]}'
ex "Feed status" GET /api/market/rates/status
ex "Latest scan" GET /api/arbitrage/scan
ex "Currency graph" GET /api/arbitrage/graph
ex "Fallback snapshot (frankfurter-fallback): the store switches source" POST /api/market/rates '{"source":"frankfurter-fallback","base":"USD","asOf":"2026-10-23","rates":{"EUR":0.92593,"GBP":0.76923,"JPY":156.87,"INR":96.73,"BGN":1.81}}'
ex "Unknown source" POST /api/market/rates '{"source":"bloomberg","ticks":[]}'
echo "### Simulator"
ex "Last result before any simulation" GET /api/simulate/last
ex "Simulation with every default (numOrders 5000, seed 42, baseline, a scan every 100)" POST /api/simulate '{}'
ex "Without the baseline, scans off, no rate volatility" POST /api/simulate '{"numOrders":20000,"seed":7,"baseline":false,"scanEvery":0,"rateVolatility":0}'
ex "Deep profile (books grow deep; the baseline's linear scan pays for it)" POST /api/simulate '{"numOrders":20000,"seed":42,"profile":"deep","scanEvery":0}'
ex "Out-of-range parameter" POST /api/simulate '{"numOrders":0}'
ex "Deep with the baseline above its cap" POST /api/simulate '{"numOrders":200001,"profile":"deep"}'
ex "Last result (the most recent simulation, as returned)" GET /api/simulate/last
echo "### Other"
ex "Unknown endpoint" GET /api/nope
ex "Wrong method" PUT /api/orders '{}'
echo "#### CORS preflight from the Vite dev origin"
echo
echo '```http'
echo "OPTIONS /api/orders   (Origin: http://localhost:5173)"
echo '```'
echo '```http'
curl -s -i -X OPTIONS -H 'Origin: http://localhost:5173' -H 'Access-Control-Request-Method: POST' \
  "$BASE/api/orders" | tr -d '\r' | grep -iv '^date:'
echo '```'
echo
wait "$sse" 2>/dev/null || true
echo "### Event stream"
echo
echo '```http'
echo "GET /api/stream     (recorded while the order and market examples above ran)"
echo '```'
echo '```text'
cat "$work/sse.txt"
echo '```'
