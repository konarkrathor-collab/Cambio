#!/usr/bin/env bash
# Stage 6 integration check: every trade reaches the Logbook, and no unknown pair reaches
# the engine. curl, shell and awk (macOS and Linux).
#   scripts/logbook_check.sh [ORDERS]      default 1200 orders (600 trades, past the 500 ring)
# Sends ORDERS crossing orders plus orders with unknown, malformed and unicode pair names,
# stops the server cleanly, decodes the archive with `run.sh archive DIR --records`, and
# checks: the archive holds exactly the trades the API reported (GET /api/trades' 500 plus
# the ring's overflow); its newest 500 records equal GET /api/trades byte for byte; every
# archived pair is a Directory pair; every unknown pair was rejected UNKNOWN_PAIR.
set -eu
cd "$(dirname "$0")/.."
orders="${1:-1200}"
work="$(mktemp -d "${TMPDIR:-/tmp}/ads-logbook.XXXXXX")"
server=""
cleanup() {
  if [ -n "$server" ]; then kill "$server" 2>/dev/null || true; wait "$server" 2>/dev/null || true; fi
  rm -rf "$work"
}
trap cleanup EXIT
passed=0
failed=0
check() { if [ "$2" = "$3" ]; then passed=$((passed + 1)); echo "PASS  $1 ($2)"; else failed=$((failed + 1)); echo "FAIL  $1: got '$2', wanted '$3'"; fi; }

backend/build.sh >/dev/null
backend/run.sh serve --port 0 --port-file "$work/port" --data-dir "$work/data" --seed-demo-accounts >"$work/server.log" 2>&1 &
server=$!
while [ ! -s "$work/port" ]; do sleep 0.1; done
BASE="http://127.0.0.1:$(cat "$work/port")"
post() { curl -s --max-time 10 -X POST -H 'Content-Type: application/json' --data-binary "$1" "$BASE/api/orders"; }

# crossing pairs: a sell, then a buy that takes it, on four pairs in turn
: >"$work/responses.txt"
i=0
while [ "$i" -lt "$orders" ]; do
  case $(( (i / 2) % 4 )) in
    0) pair="EUR/USD"; px="1.08500" ;;
    1) pair="USD/JPY"; px="156.870" ;;
    2) pair="GBP/USD"; px="1.30000" ;;
    *) pair="EUR/GBP"; px="0.83400" ;;
  esac
  if [ $((i % 2)) -eq 0 ]; then side=SELL; t=$(( (i % 11) + 1 )); else side=BUY; t=$(( ((i + 5) % 11) + 1 )); fi
  post "{\"traderId\":$t,\"pair\":\"$pair\",\"side\":\"$side\",\"type\":\"LIMIT\",\"price\":\"$px\",\"quantity\":$(( (i % 7) + 1 ))}" >>"$work/responses.txt"
  echo >>"$work/responses.txt"
  i=$((i + 1))
done
# names the Directory does not list: every one must be rejected before the engine
: >"$work/unknown.txt"
for p in 'EUR/XXX' 'XAU/USD' 'EURUSD' 'EUR//USD' '' ' ' 'ÉUR/USD' 'EUR/УSD' '欧元/美元' 'EUR/USD/GBP' 'usd-usd' 'USD/USD' '../../etc' '<script>'; do
  post "{\"traderId\":1,\"pair\":\"$p\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":\"1.08600\",\"quantity\":1}" >>"$work/unknown.txt"
  echo >>"$work/unknown.txt"
done
check "unknown pairs rejected UNKNOWN_PAIR" "$(grep -c '"reason":"UNKNOWN_PAIR"' "$work/unknown.txt")" 14
check "no unknown pair traded or rested" "$(grep -c '"status":"\(FILLED\|PARTIAL\|QUEUED\)"' "$work/unknown.txt" || true)" 0

reported="$(grep -o '"tradeId":' "$work/responses.txt" | wc -l | tr -d ' ')"
curl -s "$BASE/api/trades?limit=500" >"$work/ring.json"
ring="$(sed -n 's/.*"count":\([0-9]*\).*/\1/p' "$work/ring.json")"
overflow=$((reported - ring))
echo "      $orders orders -> $reported trades reported; GET /api/trades holds $ring, ring overflow $overflow"
curl -s "$BASE/api/tickers" | tr '{' '\n' | sed -n 's/.*"pair":"\([A-Z]*\/[A-Z]*\)".*/\1/p' >"$work/pairs.txt"

# a clean stop writes the last segment; then decode it
kill "$server"; wait "$server" 2>/dev/null || true; server=""
backend/run.sh archive "$work/data/logbook" 2>/dev/null | head -n 1
backend/run.sh archive "$work/data/logbook" --records 2>/dev/null >"$work/archive.txt"
archived="$(awk 'END {print NR}' "$work/archive.txt")"
check "archived trades = GET /api/trades + ring overflow" "$archived" "$((ring + overflow))"
check "archived trades = trades reported by POST /api/orders" "$archived" "$reported"

# GET /api/trades rewritten in the archive's record format, oldest first:
# tradeId,seq,pair,priceTick,quantity,buyOrderId,sellOrderId,buyerId,sellerId,takerSide
tr '{' '\n' <"$work/ring.json" | grep '"tradeId"' | awk '
  function get(k,   i, v) {           # the value of "k": in this flat object, quotes removed
    i = index($0, "\"" k "\":"); v = substr($0, i + length(k) + 3)
    sub(/[,}].*/, "", v); gsub(/"/, "", v); return v
  }
  { p = get("price"); sub(/\./, "", p); sub(/^0+/, "", p)
    b = get("buyOrderId"); sub(/^ORD-/, "", b); s = get("sellOrderId"); sub(/^ORD-/, "", s)
    print get("tradeId") "," get("seq") "," get("pair") "," p "," get("quantity") "," b "," s "," get("buyerId") "," get("sellerId") "," get("takerSide") }' \
  | sort -t, -k1,1n >"$work/expected.txt"
tail -n "$ring" "$work/archive.txt" >"$work/newest.txt"
if cmp -s "$work/expected.txt" "$work/newest.txt"; then
  check "newest $ring archive records = GET /api/trades, byte for byte" same same
else
  check "newest $ring archive records = GET /api/trades, byte for byte" differ same
  diff "$work/expected.txt" "$work/newest.txt" | head -5
fi
check "archive trade ids are 1..$archived in order" \
  "$(awk -F, '$1 != NR {bad++} END {print bad + 0}' "$work/archive.txt")" 0
check "every archived pair is a Directory pair" \
  "$(awk -F, 'NR == FNR {ok[$1] = 1; next} !($3 in ok) {bad++} END {print bad + 0}' "$work/pairs.txt" "$work/archive.txt")" 0

echo
echo "logbook: $passed passed, $failed failed"
[ "$failed" -eq 0 ]
