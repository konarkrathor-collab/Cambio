#!/usr/bin/env bash
# Stage 6 failure drill 5: the event stream under stress.
#   scripts/drill_sse.sh [ORDERS_PER_SEC] [SECONDS]      default 300 orders/s, 30 s per phase
# Phase A: order latency with no stream open. Phase B: the same order flow while 30 streams
# read, one stream never reads, and one client reconnects in a loop (scripts/sse_stress.py,
# its own process). Passes if the order p99 moves by at most 2 ms, the readers all received
# the same events, the never-reading client was dropped, and sseDropped rose. Then 32 open
# streams plus one more: the extra one must get 503 TOO_MANY_STREAMS.
set -eu
cd "$(dirname "$0")/.."
rate="${1:-300}"
secs="${2:-30}"
work="$(mktemp -d "${TMPDIR:-/tmp}/ads-sse.XXXXXX")"
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
health() { curl -s "$BASE/api/health" | sed -n "s/.*\"$1\":\([0-9]*\).*/\1/p"; }
p99() { sed -n 's/.*"total": {[^}]*"p99_ms": \([0-9.]*\).*/\1/p' "$1"; }

python3 scripts/load.py orders --base "$BASE" --rate "$rate" --duration 10 --report-every 100 --seed 9 >/dev/null
echo "phase A: $rate orders/s for $secs s, no streams"
python3 scripts/load.py orders --base "$BASE" --rate "$rate" --duration "$secs" --report-every 10 --seed 1 --json "$work/a.json"
dropped0="$(health sseDropped)"
echo "phase B: the same, with 30 reading streams, 1 that never reads, 1 that reconnects in a loop"
python3 scripts/sse_stress.py --base "$BASE" --duration "$((secs + 4))" --readers 30 >"$work/stress.txt" &
stress=$!
sleep 2
echo "      streams open: $(health sseClients)"
( t=0; while [ "$t" -lt "$((secs + 4))" ]; do
    d="$(health sseDropped)"; [ "$d" != "$dropped0" ] && { echo "      sseDropped rose to $d at t=${t}s of phase B"; break; }
    sleep 1; t=$((t + 1)); done ) &
watch=$!
python3 scripts/load.py orders --base "$BASE" --rate "$rate" --duration "$secs" --report-every 10 --seed 2 --json "$work/b.json"
wait "$stress"
wait "$watch" 2>/dev/null || true
sed 's/^/      /' "$work/stress.txt"
dropped1="$(health sseDropped)"
echo "      sseDropped: $dropped0 -> $dropped1"
fails=0
a="$(p99 "$work/a.json")"; b="$(p99 "$work/b.json")"
v="$(awk -v a="$a" -v b="$b" 'BEGIN { printf "%s %.3f", (b - a <= 2.0 ? "PASS" : "FAIL"), b - a }')"
echo "${v%% *}  order p99 $a ms without streams, $b ms under stream stress (change ${v#* } ms; limit +2 ms)"
[ "${v%% *}" = PASS ] || fails=$((fails + 1))
if grep -q 'never-reading client dropped by the server: True' "$work/stress.txt" && [ "$dropped1" -gt "$dropped0" ]; then
  echo "PASS  the never-reading client was dropped and sseDropped rose"
else echo "FAIL  the never-reading client was not dropped"; fails=$((fails + 1)); fi
if awk '/^readers:/ { split($0, f, /min |, max |;/); exit !(f[2] == f[3]) }' "$work/stress.txt"; then
  echo "PASS  all 30 readers received the same number of events"
else echo "FAIL  the readers disagree"; fails=$((fails + 1)); fi
echo "limit: 32 streams held open, then one more"
python3 scripts/sse_stress.py --base "$BASE" --duration 4 --readers 32 >/dev/null &
hold=$!
sleep 2
code="$(curl -s -o "$work/extra" -w '%{http_code}' --max-time 3 "$BASE/api/stream" || true)"
wait "$hold"
if [ "$code" = 503 ] && grep -q TOO_MANY_STREAMS "$work/extra"; then echo "PASS  the extra stream got 503 TOO_MANY_STREAMS"
else echo "FAIL  the extra stream got $code"; fails=$((fails + 1)); fi
echo "      server alive: $(curl -s "$BASE/api/health" | sed -n 's/.*"status":"\([a-z]*\)".*/\1/p')"
echo
echo "drill sse: $((5 - fails - 1)) of 4 checks passed"
[ "$fails" -eq 0 ]
