#!/usr/bin/env bash
# Stage 4: runs one simulation and prints the engine against the naive baseline as a table.
# curl and sed only (macOS and Linux).
#   backend/scripts/simulate.sh [numOrders] [seed]            start a fresh server for it
#   BASE=http://127.0.0.1:8080 backend/scripts/simulate.sh 1000000   use a running one
# Optional: PROFILE (shallow or deep, default shallow; deep with the baseline is capped at
# 200000 orders), SCAN_EVERY (default 100), VOLATILITY (percent per step, default 0.01).
set -eu
cd "$(dirname "$0")/.."

orders="${1:-5000}"
seed="${2:-42}"
scan_every="${SCAN_EVERY:-100}"
volatility="${VOLATILITY:-0.01}"
profile="${PROFILE:-shallow}"

work="$(mktemp -d "${TMPDIR:-/tmp}/ads-sim.XXXXXX")"
server=""
cleanup() {
  if [ -n "$server" ]; then kill "$server" 2>/dev/null || true; wait "$server" 2>/dev/null || true; fi
  rm -rf "$work"
}
trap cleanup EXIT

if [ -z "${BASE:-}" ]; then
  ./build.sh >/dev/null
  ./run.sh serve --port 0 --port-file "$work/port" --data-dir "$work/data" >"$work/server.log" 2>&1 &
  server=$!
  tries=0
  while [ ! -s "$work/port" ]; do
    tries=$((tries + 1))
    if [ "$tries" -gt 100 ]; then echo "server did not start:"; cat "$work/server.log"; exit 1; fi
    sleep 0.1
  done
  BASE="http://127.0.0.1:$(cat "$work/port")"
fi

request="{\"numOrders\":$orders,\"seed\":$seed,\"profile\":\"$profile\",\"baseline\":true,\"scanEvery\":$scan_every,\"rateVolatility\":$volatility}"
echo "POST $BASE/api/simulate $request"
code="$(curl -s --max-time 600 -o "$work/res" -w '%{http_code}' -X POST \
  -H 'Content-Type: application/json' --data-binary "$request" "$BASE/api/simulate")"
json="$(cat "$work/res")"
if [ "$code" != 200 ]; then echo "HTTP $code: $json"; exit 1; fi

engine="${json%%\"baseline\":*}"
baseline="${json#*\"baseline\":}"
# The response is one line of JSON; these read it with awk, no JSON tool needed.
first_obj() { # first_obj NAME TEXT: the body of the first flat object "NAME":{...}
  printf '%s' "$2" | awk -v k="\"$1\":{" '{ i = index($0, k); if (i) { s = substr($0, i + length(k)); print substr(s, 1, index(s, "}") - 1) } }'
}

row() { printf '%-26s %18s %18s\n' "$1" "$2" "$3"; }
es="$(first_obj submit "$engine")"; ec="$(first_obj cancel "$engine")"
bs="$(first_obj submit "$baseline")"; bc="$(first_obj cancel "$baseline")"
field() { # field NAME TEXT: the first "NAME":value (quotes stripped)
  printf '%s' "$2" | awk -v k="\"$1\":" \
    '{ i = index($0, k); if (i) { s = substr($0, i + length(k)); gsub(/^"/, "", s); n = match(s, /[",}]/); print (n ? substr(s, 1, n - 1) : s) } }'; }

echo
row "" "engine" "naive baseline"
row "profile" "$(field profile "$engine")" ""
row "orders (all operations)" "$(field numOrders "$engine")" "$(field numOrders "$baseline")"
row "duration (ms)" "$(field durationMs "$engine")" "$(field durationMs "$baseline")"
row "orders/s" "$(field ordersPerSec "$engine")" "$(field ordersPerSec "$baseline")"
row "trades" "$(field trades "$engine")" "$(field trades "$baseline")"
row "rejects" "$(field rejects "$engine")" "$(field rejects "$baseline")"
row "cancels" "$(field cancels "$engine")" "$(field cancels "$baseline")"
row "resting at end" "$(field restingOrders "$engine")" "$(field restingOrders "$baseline")"
for p in p50 p90 p99 max; do
  row "submit $p (us)" "$(field $p "$es")" "$(field $p "$bs")"
done
for p in p50 p90 p99 max; do
  row "cancel $p (us)" "$(field $p "$ec")" "$(field $p "$bc")"
done
row "trade checksum" "$(field tradeChecksum "$engine")" "$(field tradeChecksum "$baseline")"
row "final cash, all traders" "$(field finalCashTotal "$engine")" "$(field finalCashTotal "$baseline")"
echo
scans="$(first_obj scans "$engine")"
echo "tradesMatch: $(field tradesMatch "$baseline")  cashMatch (every trader): $(field cashMatch "$baseline")  checksumMatch: $(field checksumMatch "$baseline")"
echo "speedup (engine / baseline orders/s): $(field speedup "$baseline")x"
echo "scans: $(field count "$scans") on synthetic rates, p50 $(field p50Us "$scans") us, p99 $(field p99Us "$scans") us, loopholes found in $(field loopholesFound "$scans")"
[ "$(field checksumMatch "$baseline")" = true ]
