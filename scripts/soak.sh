#!/usr/bin/env bash
# Stage 6 failure drill 6: the soak.
#   scripts/soak.sh [SECONDS]        default 600 (10 minutes)
# A steady 300 orders/s (scripts/load.py: crossing orders, cancels, a bounded book) plus 50
# rate ticks/s, with one event stream open as the dashboard would have. Reports the live heap
# (after a forced GC) at the start, middle and end; per minute: order latency p50/p99/max,
# threads, open file descriptors (sockets included), heap in use, heap after the last GC (from
# -Xlog:gc), resting orders and streams. Flat is the pass: threads and descriptors must not
# grow, the live heap must not grow by more than 25%, and the last minutes' p99 must stay
# within 2 ms of the first minutes'. Needs a JDK's jcmd on the PATH. The per-minute jcmd
# samples (Thread.print, GC.heap_info) stop the JVM briefly, which shows in that minute's max.
set -eu
cd "$(dirname "$0")/.."
secs="${1:-600}"
work="$(mktemp -d "${TMPDIR:-/tmp}/ads-soak.XXXXXX")"
server=""; ticks=""; sse=""
cleanup() {
  for p in $ticks $sse; do kill "$p" 2>/dev/null || true; done
  if [ -n "$server" ]; then kill "$server" 2>/dev/null || true; wait "$server" 2>/dev/null || true; fi
  rm -rf "$work"
}
trap cleanup EXIT
backend/build.sh >/dev/null
JAVA_OPTS="-Xlog:gc:file=$work/gc.log" backend/run.sh serve --port 0 --port-file "$work/port" \
  --data-dir "$work/data" --seed-demo-accounts >"$work/server.log" 2>&1 &
server=$!
while [ ! -s "$work/port" ]; do sleep 0.1; done
BASE="http://127.0.0.1:$(cat "$work/port")"
pid=$server   # run.sh execs java, so this is the JVM

fds() { if [ -d "/proc/$pid/fd" ]; then ls "/proc/$pid/fd" | wc -l | tr -d ' '; else lsof -p "$pid" 2>/dev/null | awk 'NR > 1' | wc -l | tr -d ' '; fi; }
threads() { jcmd "$pid" Thread.print 2>/dev/null | grep -c '^"' || true; }
heap_used_kb() { jcmd "$pid" GC.heap_info 2>/dev/null | sed -n 's/.*total [0-9]*K, used \([0-9]*\)K.*/\1/p' | head -n 1; }
live_heap_kb() { jcmd "$pid" GC.run >/dev/null 2>&1; sleep 1; heap_used_kb; }
after_gc() { sed -n 's/.*Pause Young.*->\([0-9]*\)M(.*/\1/p' "$work/gc.log" | tail -n 1; }
hfield() { curl -s "$BASE/api/health" | sed -n "s/.*\"$1\":\([0-9]*\).*/\1/p"; }

curl -s -N "$BASE/api/stream" >/dev/null 2>&1 &
sse=$!
echo "warm-up: 30 s of the same load"
python3 scripts/load.py ticks --base "$BASE" --rate 50 --batch 5 --duration "$((secs + 40))" --seed 4 >"$work/ticks.txt" 2>&1 &
ticks=$!
python3 scripts/load.py orders --base "$BASE" --rate 300 --duration 30 --report-every 100 --seed 8 >/dev/null
live_start="$(live_heap_kb)"; thr_start="$(threads)"; fd_start="$(fds)"
echo "start: live heap ${live_start} KB, threads $thr_start, file descriptors $fd_start"
echo "soak: $secs s at 300 orders/s + 50 ticks/s, one event stream open"
python3 scripts/load.py orders --base "$BASE" --rate 300 --duration "$secs" --report-every 60 --seed 1 \
  --json "$work/orders.json" >"$work/orders.txt" 2>&1 &
load=$!
: >"$work/minutes.txt"
m=0
live_mid=""
while kill -0 "$load" 2>/dev/null; do
  sleep 60
  m=$((m + 1))
  [ "$m" -eq "$((secs / 120))" ] && live_mid="$(live_heap_kb)"
  printf '%s %s %s %s %s %s %s\n' "$m" "$(threads)" "$(fds)" "$(heap_used_kb)" "$(after_gc)" \
    "$(hfield restingOrders)" "$(hfield sseClients)" >>"$work/minutes.txt"
done
wait "$load" || true
wait "$ticks" || true; ticks=""
live_end="$(live_heap_kb)"; thr_end="$(threads)"; fd_end="$(fds)"
st="$(curl -s "$BASE/api/market/rates/status")"

echo
echo "minute  orders p50/p99/max (ms)      threads  fds  heap used  heap after GC  resting  streams"
grep '^  t=' "$work/orders.txt" | sed 's/.*p50=\([0-9.]*\) ms p99=\([0-9.]*\) ms max=\([0-9.]*\) ms/\1 \2 \3/' >"$work/lat.txt"
# minute k: the k-th latency interval and the k-th monitoring sample
awk 'NR == FNR { lat[FNR] = $0; n = FNR; next }
  FNR <= n { split(lat[FNR], l, " "); printf "%6s  %7s / %7s / %8s  %7s  %4s  %7s KB  %10s MB  %7s  %7s\n", $1, l[1], l[2], l[3], $2, $3, $4, $5, $6, $7 }' \
  "$work/lat.txt" "$work/minutes.txt"
grep '^orders total\|HTTP statuses' "$work/orders.txt" | sed 's/^/  /'
sed 's/^/  /' "$work/ticks.txt"
echo "  backend: $(printf '%s' "$st" | sed -n 's/.*"tickCount":\([0-9]*\).*/\1/p') ticks received, $(printf '%s' "$st" | sed -n 's/.*"scanCount":\([0-9]*\).*/\1/p') scans run"
echo "live heap (after a forced GC): start ${live_start} KB, middle ${live_mid:-n/a} KB, end ${live_end} KB"
echo "threads: start $thr_start, end $thr_end; file descriptors: start $fd_start, end $fd_end"
echo "GC log: $(grep -c 'Pause' "$work/gc.log") pauses; longest $(sed -n 's/.* \([0-9.]*\)ms$/\1/p' "$work/gc.log" | sort -n | tail -n 1) ms"

fails=0
verdict() { if [ "$2" = yes ]; then echo "PASS  $1"; else echo "FAIL  $1"; fails=$((fails + 1)); fi; }
verdict "threads flat ($thr_start -> $thr_end)" "$([ "$thr_end" -le "$((thr_start + 2))" ] && echo yes || echo no)"
verdict "file descriptors flat ($fd_start -> $fd_end)" "$([ "$fd_end" -le "$((fd_start + 3))" ] && echo yes || echo no)"
verdict "live heap within +25% ($live_start -> $live_end KB)" "$([ "$live_end" -le "$((live_start * 125 / 100))" ] && echo yes || echo no)"
first="$(head -n 2 "$work/lat.txt" | awk '{ s += $2 } END { printf "%.3f", s / NR }')"
last="$(tail -n 2 "$work/lat.txt" | awk '{ s += $2 } END { printf "%.3f", s / NR }')"
verdict "order p99 flat: first two minutes $first ms, last two $last ms" "$(awk -v a="$first" -v b="$last" 'BEGIN { print (b - a <= 2.0 ? "yes" : "no") }')"
kill -0 "$server" 2>/dev/null && echo "      server alive at the end: $(curl -s "$BASE/api/health")"
[ "$fails" -eq 0 ]
