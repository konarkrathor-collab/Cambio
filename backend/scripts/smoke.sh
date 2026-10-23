#!/usr/bin/env bash
# Stage 3 smoke test: exercises every endpoint with curl only (macOS and Linux; no jq, no
# python, no GNU-only tools).
#   backend/scripts/smoke.sh                 start a fresh server (temp data dir, demo accounts)
#   BASE=http://127.0.0.1:8080 backend/scripts/smoke.sh   use a running one (needs trader ids 1-12)
set -eu
cd "$(dirname "$0")/.."

work="$(mktemp -d "${TMPDIR:-/tmp}/ads-smoke.XXXXXX")"
server=""
cleanup() {
  if [ -n "$server" ]; then
    kill "$server" 2>/dev/null || true
    wait "$server" 2>/dev/null || true
  fi
  rm -rf "$work"
}
trap cleanup EXIT

if [ -z "${BASE:-}" ]; then
  ./build.sh >/dev/null
  ./run.sh serve --port 0 --port-file "$work/port" --data-dir "$work/data" \
    --seed-demo-accounts >"$work/server.log" 2>&1 &
  server=$!
  tries=0
  while [ ! -s "$work/port" ]; do
    tries=$((tries + 1))
    if [ "$tries" -gt 100 ]; then
      echo "server did not start:"; cat "$work/server.log"; exit 1
    fi
    sleep 0.1
  done
  BASE="http://127.0.0.1:$(cat "$work/port")"
fi
echo "smoke test against $BASE"

passed=0
failed=0
body="$work/body"

# req METHOD PATH EXPECTED_STATUS [JSON_BODY] [GREP_PATTERN]
req() {
  method="$1"; path="$2"; want="$3"; data="${4:-}"; pattern="${5:-}"
  if [ -n "$data" ]; then
    code="$(curl -s --max-time 10 -o "$body" -w '%{http_code}' -X "$method" \
      -H 'Content-Type: application/json' --data-binary "$data" "$BASE$path")"
  else
    code="$(curl -s --max-time 10 -o "$body" -w '%{http_code}' -X "$method" "$BASE$path")"
  fi
  ok=1
  [ "$code" = "$want" ] || ok=0
  if [ "$ok" = 1 ] && [ -n "$pattern" ] && ! grep -q -- "$pattern" "$body"; then ok=0; fi
  if [ "$ok" = 1 ]; then
    passed=$((passed + 1))
    printf 'PASS  %-6s %-42s %s\n' "$method" "$path" "$code"
  else
    failed=$((failed + 1))
    printf 'FAIL  %-6s %-42s %s (wanted %s %s)\n      %s\n' "$method" "$path" "$code" "$want" \
      "$pattern" "$(head -c 300 "$body")"
  fi
}

field() { # the first "name":"value" or "name":number in the last response
  sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p" "$body" | head -n 1
}

# --- the event stream, recorded while the rest of the script runs
curl -s -N --max-time 12 "$BASE/api/stream" >"$work/sse.txt" 2>/dev/null &
sse=$!
sleep 0.5

req GET  /api/health                         200 "" '"status":"ok"'
req GET  "/api/tickers?prefix=usd"           200 "" '"count":11'
req GET  /api/accounts                       200 "" '"accounts":\['
req POST /api/accounts                       201 '{"traderId":901,"cash":50000000,"marginLimit":40000000}' '"traderId":901'
req POST /api/accounts                       409 '{"traderId":901,"cash":1,"marginLimit":1}' '"ACCOUNT_EXISTS"'
req GET  /api/accounts/901                   200 "" '"cash":50000000'
req GET  /api/accounts/999999                404 "" '"ACCOUNT_NOT_FOUND"'

req POST /api/orders 200 '{"traderId":901,"pair":"EUR/USD","side":"SELL","type":"LIMIT","price":1.08510,"quantity":100}' '"status":"QUEUED"'
resting="$(field orderId)"
req POST /api/orders 200 '{"traderId":1,"pair":"eur/usd","side":"buy","type":"LIMIT","price":"1.08520","quantity":60}' '"status":"FILLED"'
req POST /api/orders 200 '{"traderId":2,"pair":"EUR/USD","side":"BUY","type":"MARKET","quantity":10}' '"takerSide":"BUY"'
req POST /api/orders 200 '{"traderId":1,"pair":"EUR/USD","side":"BUY","type":"LIMIT","price":1.085051,"quantity":10}' '"reason":"BAD_PRICE"'
req POST /api/orders 200 '{"traderId":12,"pair":"EUR/USD","side":"BUY","type":"LIMIT","price":1.08,"quantity":1000}' '"reason":"MARGIN"'
req POST /api/orders 400 '{"traderId":1,"pair":"EUR/USD","side":"HOLD","type":"LIMIT","price":1.08,"quantity":1}' '"BAD_REQUEST"'
req POST /api/orders 400 '{not json' '"BAD_JSON"'

req GET  "/api/orderbook/EUR-USD?depth=5"    200 "" '"asks":\[{"price":1.08510,"volume":30'
req GET  /api/orderbook/eur-usd              200 "" '"lastTradePrice":1.08510'
req GET  /api/orderbook/XXX-YYY              404 "" '"UNKNOWN_PAIR"'
req GET  /api/inspector/EUR-USD              200 "" '"structure":"B+ tree"'

req DELETE "/api/orders/$resting"            200 "" '"remainingQuantity":30'
req DELETE "/api/orders/$resting"            404 "" '"ORDER_NOT_FOUND"'
req DELETE /api/orders/not-an-id             400 "" '"BAD_REQUEST"'

req POST /api/market/rates 200 '{"source":"finnhub-ws","ticks":[{"symbol":"OANDA:EUR_USD","price":1.08,"ts":1792783468459},{"symbol":"OANDA:GBP_USD","price":1.30,"ts":1792783468460},{"symbol":"OANDA:EUR_GBP","price":0.83409,"ts":1792783468461}]}' '"accepted":3'
req GET  /api/market/rates/status            200 "" '"tickCount":3'
req GET  /api/arbitrage/scan                 200 "" '"hasArbitrage":true'
req GET  /api/arbitrage/graph                200 "" '"cycle":\['
req POST /api/simulate                       501 '{}' '"NOT_IMPLEMENTED"'
req GET  /api/nope                           404 "" '"NOT_FOUND"'

# CORS preflight from the Vite dev origin, and from an origin that is not allowed
code="$(curl -s --max-time 5 -o /dev/null -w '%{http_code}' -X OPTIONS \
  -H 'Origin: http://localhost:5173' -H 'Access-Control-Request-Method: POST' "$BASE/api/orders")"
if [ "$code" = 204 ]; then passed=$((passed + 1)); echo "PASS  OPTIONS /api/orders (Vite origin)                    204"
else failed=$((failed + 1)); echo "FAIL  OPTIONS /api/orders (Vite origin) -> $code"; fi
code="$(curl -s --max-time 5 -o /dev/null -w '%{http_code}' -X OPTIONS \
  -H 'Origin: http://evil.example' "$BASE/api/orders")"
if [ "$code" = 403 ]; then passed=$((passed + 1)); echo "PASS  OPTIONS /api/orders (other origin)                   403"
else failed=$((failed + 1)); echo "FAIL  OPTIONS /api/orders (other origin) -> $code"; fi

# --- what the stream saw
wait "$sse" 2>/dev/null || true
echo
echo "event stream (GET /api/stream):"
for event in BOOK_UPDATE TRADE LOOPHOLE_ALERT RATE_TICK; do
  n="$(grep -c "^event: $event\$" "$work/sse.txt" || true)"
  if [ "$n" -gt 0 ]; then
    passed=$((passed + 1)); printf 'PASS  %-15s x %s\n' "$event" "$n"
  else
    failed=$((failed + 1)); printf 'FAIL  %-15s never seen\n' "$event"
  fi
done
grep -q '^retry: ' "$work/sse.txt" && echo "      (stream opened with a retry: field; events carry id:)"
echo "      first events:"
grep '^event: ' "$work/sse.txt" | head -n 6 | sed 's/^/        /'

req GET /api/health 200 "" '"status":"ok"'   # still alive after all of the above
echo
echo "smoke: $passed passed, $failed failed"
[ "$failed" -eq 0 ]
