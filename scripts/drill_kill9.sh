#!/usr/bin/env bash
# Stage 6 failure drill 1: kill -9 the backend mid-session, restart it on the same data.
#   scripts/drill_kill9.sh        (curl, shell, awk; macOS and Linux)
# Phase A trades, then waits 6 s (past the 2 s Vault flush and the 5 s Logbook flush);
# phase B trades 1 s before the kill, inside the loss window. After a restart: the archive
# decodes and holds every phase A trade; every balance equals the seed balance plus the
# archived trades (a balance on disk never includes a trade the archive lacks, B13); and new
# ids exceed every id issued before. Phase B may be lost: the Vault and the Logbook are
# written together every 2 s, and the Logbook at least every 5 s.
set -eu
cd "$(dirname "$0")/.."
work="$(mktemp -d "${TMPDIR:-/tmp}/ads-kill9.XXXXXX")"
server=""
cleanup() { [ -n "$server" ] && kill -9 "$server" 2>/dev/null || true; rm -rf "$work"; }
trap cleanup EXIT
passed=0; failed=0
check() { if [ "$2" = "$3" ]; then passed=$((passed + 1)); echo "PASS  $1 ($2)"; else failed=$((failed + 1)); echo "FAIL  $1: got '$2', wanted '$3'"; fi; }
start() {
  rm -f "$work/port"
  backend/run.sh serve --port 0 --port-file "$work/port" --data-dir "$work/data" --seed-demo-accounts >>"$work/server.log" 2>&1 &
  server=$!
  while [ ! -s "$work/port" ]; do sleep 0.1; done
  BASE="http://127.0.0.1:$(cat "$work/port")"
}
post() { curl -s -X POST -H 'Content-Type: application/json' --data-binary "$1" "$BASE/api/orders"; }
trade_pairs() { # N crossing pairs; prints the highest order id, counts trades into $work/trades
  n=$1; last=""
  for i in $(seq 1 "$n"); do
    post "{\"traderId\":$(( (i % 5) + 1 )),\"pair\":\"EUR/USD\",\"side\":\"SELL\",\"type\":\"LIMIT\",\"price\":\"1.08500\",\"quantity\":3}" >/dev/null
    r="$(post "{\"traderId\":$(( (i % 5) + 6 )),\"pair\":\"EUR/USD\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":\"1.08500\",\"quantity\":3}")"
    printf '%s\n' "$r" | grep -o '"tradeId":[0-9]*' >>"$work/trades"
    last="$(printf '%s' "$r" | sed -n 's/.*"orderId":"ORD-\([0-9]*\)".*/\1/p')"
  done
  echo "$last"
}
accounts() { curl -s "$BASE/api/accounts" | tr '{' '\n' | sed -n 's/.*"traderId":\([0-9]*\),"cash":\(-\{0,1\}[0-9]*\).*/\1 \2/p'; }

backend/build.sh >/dev/null
start
: >"$work/trades"
maxA="$(trade_pairs 40)"
tradesA="$(awk 'END {print NR}' "$work/trades")"
accounts >"$work/accounts_a.txt"
echo "phase A: 40 crossing pairs, $tradesA trades, last order ORD-$maxA; waiting 6 s"
sleep 6
maxB="$(trade_pairs 10)"
tradesAB="$(awk 'END {print NR}' "$work/trades")"
maxTrade="$(sed -n 's/"tradeId"://p' "$work/trades" | sort -n | tail -n 1)"
echo "phase B: 10 more pairs ($((tradesAB - tradesA)) trades, last order ORD-$maxB), then 1 s, then:"
sleep 1
echo "\$ kill -9 $server"
kill -9 "$server"; wait "$server" 2>/dev/null || true; server=""

archived_before="$(backend/run.sh archive "$work/data/logbook" --records 2>/dev/null | awk 'END {print NR}')"
start
accounts >"$work/accounts_after.txt"
check "12 accounts back after the restart" "$(awk 'END {print NR}' "$work/accounts_after.txt")" 12
# consistency (B13): every balance on disk = seed balance + the trades in the archive
backend/run.sh archive "$work/data/logbook" --records 2>/dev/null >"$work/archive.txt"
check "balances = seed balances + archived trades, every trader" "$(awk -F'[ ,]' '
  FILENAME == ARGV[1] { n = $4 * $5; cash[$8] -= n; cash[$9] += n; next }
  { start = ($1 == 12) ? 2000000 : 5000000000; if (start + cash[$1] != $2) bad++ }
  END { print bad + 0 }' "$work/archive.txt" "$work/accounts_after.txt")" 0
if cmp -s "$work/accounts_a.txt" "$work/accounts_after.txt"; then echo "      balances are phase A's: phase B's were inside the loss window"
else echo "      balances include phase B: its trades were archived with the Vault flush before the kill"; fi
check "the archive decodes after kill -9" "$(backend/run.sh archive "$work/data/logbook" >/dev/null 2>&1 && echo ok || echo broken)" ok
if [ "$archived_before" -ge "$tradesA" ]; then check "every phase A trade is archived" yes yes; else check "every phase A trade is archived ($archived_before archived)" no yes; fi
echo "      archive holds $archived_before of $tradesAB trades: $((tradesAB - archived_before)) lost from the loss window (bound: the last 2 s, 5 s at most)"
r="$(post '{"traderId":1,"pair":"EUR/USD","side":"SELL","type":"LIMIT","price":"1.08500","quantity":1}')"
r2="$(post '{"traderId":6,"pair":"EUR/USD","side":"BUY","type":"LIMIT","price":"1.08500","quantity":1}')"
newId="$(printf '%s' "$r" | sed -n 's/.*"orderId":"ORD-\([0-9]*\)".*/\1/p')"
newTrade="$(printf '%s' "$r2" | sed -n 's/.*"tradeId":\([0-9]*\).*/\1/p')"
echo "      first ids after restart: ORD-$newId, trade $newTrade (before the kill: ORD-$maxB, trade $maxTrade)"
check "new order id exceeds every earlier one" "$([ "$newId" -gt "$maxB" ] && echo yes || echo no)" yes
check "new trade id exceeds every earlier one" "$([ "$newTrade" -gt "$maxTrade" ] && echo yes || echo no)" yes
check "an order from before the kill is not resting (404)" "$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$BASE/api/orders/ORD-1")" 404
echo
echo "drill kill -9: $passed passed, $failed failed"
[ "$failed" -eq 0 ]
