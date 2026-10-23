#!/usr/bin/env bash
# Stage 6 end-to-end check against the real built system (curl, shell, awk; macOS and Linux).
#   scripts/e2e.sh
# 1. a seeded backend on an ephemeral port with a temporary data directory, and a recording of
#    GET /api/stream for the whole run;
# 2. the feed in --replay mode against it (a recorded Finnhub capture, no key, no network);
# 3. orders and cancels over the API, then the contract's +0.4% tick set (marked replay) so a
#    loophole is found;
# 4. both simulator profiles;
# then it asserts, from the API alone: the status shows replayed ticks and scans; the stream
# delivered BOOK_UPDATE, TRADE and LOOPHOLE_ALERT in that order; and every final balance
# equals the starting balance recomputed from the trade list.
set -eu
cd "$(dirname "$0")/.."
root="$(pwd)"

work="$(mktemp -d "${TMPDIR:-/tmp}/ads-e2e.XXXXXX")"
server=""
sse=""
cleanup() {
  [ -n "$sse" ] && kill "$sse" 2>/dev/null || true
  if [ -n "$server" ]; then kill "$server" 2>/dev/null || true; wait "$server" 2>/dev/null || true; fi
  rm -rf "$work"
}
trap cleanup EXIT

passed=0
failed=0
ok() { passed=$((passed + 1)); echo "PASS  $1"; }
bad() { failed=$((failed + 1)); echo "FAIL  $1"; }
check() { if [ "$2" = "$3" ]; then ok "$1 ($2)"; else bad "$1: got '$2', wanted '$3'"; fi; }

# ---------------------------------------------------------------- 1. backend + stream recording
backend/build.sh >/dev/null
backend/run.sh serve --port 0 --port-file "$work/port" --data-dir "$work/data" --seed-demo-accounts \
  >"$work/server.log" 2>&1 &
server=$!
tries=0
while [ ! -s "$work/port" ]; do
  tries=$((tries + 1))
  if [ "$tries" -gt 150 ]; then echo "backend did not start:"; cat "$work/server.log"; exit 1; fi
  sleep 0.1
done
BASE="http://127.0.0.1:$(cat "$work/port")"
echo "backend: $BASE (data dir $work/data)"
curl -s -N "$BASE/api/stream" >"$work/sse.txt" 2>/dev/null &
sse=$!
sleep 0.5

post() { curl -s --max-time 30 -X POST -H 'Content-Type: application/json' --data-binary "$2" "$BASE$1"; }
field() { printf '%s' "$2" | sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p" | head -n 1; }

# the starting balances, before anything trades
curl -s "$BASE/api/accounts" | tr '{' '\n' | sed -n 's/.*"traderId":\([0-9]*\),"cash":\(-\{0,1\}[0-9]*\).*/\1 \2/p' >"$work/start.txt"
check "seeded accounts" "$(awk 'END {print NR}' "$work/start.txt")" 12

# ---------------------------------------------------------------- 2. the feed, replaying
capture="feed/samples/finnhub_ticks_2026-10-23.jsonl"
echo "\$ python3 feed/feed.py --replay $capture --speed 0 --backend $BASE/api/market/rates"
python3 feed/feed.py --replay "$capture" --speed 0 --backend "$BASE/api/market/rates" >"$work/feed.log" 2>&1 \
  || { bad "feed replay exited non-zero"; tail -5 "$work/feed.log"; }
status="$(curl -s "$BASE/api/market/rates/status")"
check "status: replay flag" "$(field replay "$status")" true
ticks="$(field tickCount "$status")"
scans="$(field scanCount "$status")"
if [ "${ticks:-0}" -gt 0 ] && [ "${scans:-0}" -gt 0 ]; then ok "status: $ticks replayed ticks, $scans scans"; else bad "status: ticks=$ticks scans=$scans"; fi

# ---------------------------------------------------------------- 3. orders, cancels, a loophole
# a resting book on two pairs, then crossing orders, then cancels
i=0
for t in 1 2 3 4 5; do
  post /api/orders "{\"traderId\":$t,\"pair\":\"EUR/USD\",\"side\":\"SELL\",\"type\":\"LIMIT\",\"price\":\"1.0851$t\",\"quantity\":$((t * 10))}" >/dev/null
  post /api/orders "{\"traderId\":$((t + 5)),\"pair\":\"EUR/USD\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":\"1.0849$t\",\"quantity\":$((t * 8))}" >/dev/null
  post /api/orders "{\"traderId\":$t,\"pair\":\"USD/JPY\",\"side\":\"SELL\",\"type\":\"LIMIT\",\"price\":\"156.87$t\",\"quantity\":$((t * 3))}" >/dev/null
done
r="$(post /api/orders '{"traderId":7,"pair":"EUR/USD","side":"BUY","type":"LIMIT","price":"1.08513","quantity":70}')"
check "crossing limit buy" "$(field status "$r")" PARTIAL
r="$(post /api/orders '{"traderId":8,"pair":"EUR/USD","side":"SELL","type":"MARKET","quantity":30}')"
check "market sell" "$(field status "$r")" FILLED
r="$(post /api/orders '{"traderId":9,"pair":"USD/JPY","side":"BUY","type":"MARKET","quantity":20}')"
check "market buy on USD/JPY" "$(field status "$r")" FILLED
r="$(post /api/orders '{"traderId":10,"pair":"EUR/USD","side":"BUY","type":"LIMIT","price":"1.08400","quantity":5}')"
resting="$(field orderId "$r")"
code="$(curl -s -o "$work/c1" -w '%{http_code}' -X DELETE "$BASE/api/orders/$resting")"
check "cancel $resting" "$code" 200
code="$(curl -s -o "$work/c2" -w '%{http_code}' -X DELETE "$BASE/api/orders/$resting")"
check "cancel it again" "$code" 404
r="$(post /api/orders '{"traderId":12,"pair":"EUR/USD","side":"BUY","type":"LIMIT","price":"1.08","quantity":1000}')"
check "margin rejection" "$(field reason "$r")" MARGIN
now_ms="$(($(date +%s) * 1000))"
r="$(post /api/market/rates "{\"source\":\"finnhub-ws\",\"replay\":true,\"ticks\":[{\"symbol\":\"OANDA:EUR_USD\",\"price\":1.08,\"ts\":$now_ms},{\"symbol\":\"OANDA:GBP_USD\",\"price\":1.30,\"ts\":$now_ms},{\"symbol\":\"OANDA:EUR_GBP\",\"price\":0.83409,\"ts\":$now_ms}]}")"
check "loophole ticks accepted" "$(field accepted "$r")" 3
sleep 1
scan="$(curl -s "$BASE/api/arbitrage/scan")"
check "scan finds the loophole" "$(field hasArbitrage "$scan")" true

# ---------------------------------------------------------------- 4. both simulator profiles
for profile in shallow deep; do
  r="$(post /api/simulate "{\"numOrders\":20000,\"seed\":7,\"profile\":\"$profile\"}")"
  check "simulate $profile: checksumMatch" "$(field checksumMatch "$r")" true
done
check "health: hasSimulation" "$(field hasSimulation "$(curl -s "$BASE/api/health")")" true

# ---------------------------------------------------------------- assertions from the API
sleep 0.5
kill "$sse" 2>/dev/null || true
sse=""
seq_of() { grep -n "^event: $1\$" "$work/sse.txt" | head -n 1 | cut -d: -f1; }
b="$(seq_of BOOK_UPDATE)"; t="$(seq_of TRADE)"; l="$(seq_of LOOPHOLE_ALERT)"
if [ -n "$b" ] && [ -n "$t" ] && [ -n "$l" ] && [ "$b" -lt "$t" ] && [ "$t" -lt "$l" ]; then
  ok "stream order: BOOK_UPDATE (line $b) < TRADE (line $t) < LOOPHOLE_ALERT (line $l)"
else
  bad "stream order: BOOK_UPDATE=$b TRADE=$t LOOPHOLE_ALERT=$l"
fi
# every TRADE is followed by a BOOK_UPDATE before the next order's events (contract §4)
awk '/^event: /{print $2}' "$work/sse.txt" >"$work/events.txt"
orphan="$(awk 'prev == "TRADE" && $1 != "TRADE" && $1 != "BOOK_UPDATE" {n++} {prev = $1} END {print n + 0}' "$work/events.txt")"
check "every TRADE run ends in a BOOK_UPDATE" "$orphan" 0
check "stream events carry strictly increasing ids" \
  "$(awk '/^id: /{ if ($2 <= last) bad++; last = $2 } END {print bad + 0}' "$work/sse.txt")" 0
stream_trades="$(grep -c '^event: TRADE$' "$work/sse.txt" || true)"

# balances: start + recomputation from GET /api/trades == GET /api/accounts
curl -s "$BASE/api/trades?limit=500" >"$work/trades.json"
count="$(field count "$(cat "$work/trades.json")")"
check "trades in GET /api/trades = TRADE events" "$count" "$stream_trades"
tr '{' '\n' <"$work/trades.json" | grep '"tradeId"' | sed -n \
  's/.*"price":\([0-9.]*\),"quantity":\([0-9]*\),.*"buyerId":\([0-9]*\),"sellerId":\([0-9]*\),.*/\1 \2 \3 \4/p' \
  >"$work/fills.txt"
check "parsed every trade" "$(awk 'END {print NR}' "$work/fills.txt")" "$count"
curl -s "$BASE/api/accounts" | tr '{' '\n' | sed -n 's/.*"traderId":\([0-9]*\),"cash":\(-\{0,1\}[0-9]*\).*/\1 \2/p' >"$work/end.txt"
# notional = price in ticks x quantity; a price printed with its tick decimals is its tick
# count once the point is removed (contract §1)
mismatch="$(awk '
  FNR == NR && FILENAME == ARGV[1] { cash[$1] = $2; next }
  FILENAME == ARGV[2] { p = $1; sub(/\./, "", p); n = (p + 0) * $2; cash[$3] -= n; cash[$4] += n; next }
  { if (cash[$1] != $2) { bad++; print "trader " $1 ": recomputed " cash[$1] ", API " $2 > "/dev/stderr" } }
  END { print bad + 0 }' "$work/start.txt" "$work/fills.txt" "$work/end.txt")"
check "final balances = start - buys + sells, recomputed from $count trades" "$mismatch" 0

echo
echo "e2e: $passed passed, $failed failed"
[ "$failed" -eq 0 ]
