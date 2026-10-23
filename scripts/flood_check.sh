#!/usr/bin/env bash
# Stage 6 integration check: scanning never blocks matching.
#   scripts/flood_check.sh [ORDERS_PER_SEC] [SECONDS]      default 200 orders/s for 30 s per phase
# Times every order request (scripts/load.py, one keep-alive connection) with no rate flood,
# then again while a separate process floods POST /api/market/rates at 200 ticks per second,
# and fails if the flood moves the order p99 by more than 2 ms.
set -eu
cd "$(dirname "$0")/.."
rate="${1:-200}"
secs="${2:-30}"
work="$(mktemp -d "${TMPDIR:-/tmp}/ads-flood.XXXXXX")"
server=""
cleanup() {
  if [ -n "$server" ]; then kill "$server" 2>/dev/null || true; wait "$server" 2>/dev/null || true; fi
  rm -rf "$work"
}
trap cleanup EXIT
backend/build.sh >/dev/null
backend/run.sh serve --port 0 --port-file "$work/port" --data-dir "$work/data" --seed-demo-accounts >"$work/server.log" 2>&1 &
server=$!
while [ ! -s "$work/port" ]; do sleep 0.1; done
BASE="http://127.0.0.1:$(cat "$work/port")"
scans() { curl -s "$BASE/api/market/rates/status" | sed -n 's/.*"scanCount":\([0-9]*\).*/\1/p'; }

echo "warm-up: 10 s at $rate orders/s"
python3 scripts/load.py orders --base "$BASE" --rate "$rate" --duration 10 --report-every 100 --seed 9 >/dev/null
echo "phase A: $rate orders/s for $secs s, no rate flood"
python3 scripts/load.py orders --base "$BASE" --rate "$rate" --duration "$secs" --report-every 10 --seed 1 --json "$work/a.json"
before="$(scans)"
echo "phase B: $rate orders/s for $secs s, and 200 ticks/s to POST /api/market/rates from another process"
python3 scripts/load.py ticks --base "$BASE" --rate 200 --batch 2 --duration "$((secs + 2))" --seed 3 &
flood=$!
sleep 1
python3 scripts/load.py orders --base "$BASE" --rate "$rate" --duration "$secs" --report-every 10 --seed 2 --json "$work/b.json"
wait "$flood"
after="$(scans)"
echo "scans run during the flood: $((after - before))"
p99a="$(sed -n 's/.*"total": {[^}]*"p99_ms": \([0-9.]*\).*/\1/p' "$work/a.json")"
p99b="$(sed -n 's/.*"total": {[^}]*"p99_ms": \([0-9.]*\).*/\1/p' "$work/b.json")"
verdict="$(awk -v a="$p99a" -v b="$p99b" 'BEGIN { d = b - a; printf "%s %.3f", (d <= 2.0 ? "PASS" : "FAIL"), d }')"
echo "$verdict  order p99 without flood $p99a ms, with flood $p99b ms (change ${verdict#* } ms; limit +2 ms)"
[ "$((after - before))" -gt 0 ] || { echo "FAIL  no scans ran during the flood"; exit 1; }
[ "${verdict%% *}" = PASS ]
