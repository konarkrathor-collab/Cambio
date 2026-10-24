#!/usr/bin/env bash
# Stage 6 failure drills 2 and 3, with no key and no Finnhub (curl, shell, python3; macOS and Linux).
#   scripts/drill_feed.sh                 backend on an ephemeral port
#   PORT=8080 scripts/drill_feed.sh       backend on 8080, so `npm run dev` can watch it
# The feed runs in live mode against feed/fake_finnhub.py (a local WebSocket that streams a
# +0.4% EUR/GBP loophole). Needs feed/.venv (websocket-client); see feed/README.md.
#  drill 2, feed killed:    kill -9 the feed; within 30 s the status says disconnected and
#                           the scan clears; restart the feed; it recovers, backend untouched.
#  drill 3, network loss:   kill the socket server (the feed stays up); after 15 s the feed
#                           falls back to Frankfurter (real network; FALLBACK_OK=0 if offline);
#                           restart the socket; the feed reconnects and goes live again.
set -eu
cd "$(dirname "$0")/.."
py=feed/.venv/bin/python
[ -x "$py" ] || { echo "feed/.venv is missing: python3 -m venv feed/.venv && feed/.venv/bin/pip install -r feed/requirements.txt"; exit 2; }
work="$(mktemp -d "${TMPDIR:-/tmp}/ads-feeddrill.XXXXXX")"
server=""; ws=""; feed=""
cleanup() {
  for p in $feed $ws $server; do kill "$p" 2>/dev/null || true; done
  wait 2>/dev/null || true
  rm -rf "$work"
}
trap cleanup EXIT
passed=0; failed=0
verdict() { if [ "$2" = yes ]; then passed=$((passed + 1)); echo "PASS  $1"; else failed=$((failed + 1)); echo "FAIL  $1"; fi; }
free_port() { python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])'; }

backend/build.sh >/dev/null
backend/run.sh serve --port "${PORT:-0}" --port-file "$work/port" --data-dir "$work/data" --seed-demo-accounts >"$work/server.log" 2>&1 &
server=$!
while [ ! -s "$work/port" ]; do sleep 0.1; done
BASE="http://127.0.0.1:$(cat "$work/port")"
WSPORT="$(free_port)"
start_ws() { python3 feed/fake_finnhub.py --port "$WSPORT" --loophole >>"$work/ws.log" 2>&1 & ws=$!; sleep 0.5; }
start_feed() {
  FINNHUB_API_KEY=drill-placeholder "$py" feed/feed.py --ws-url "ws://127.0.0.1:$WSPORT" \
    --backend "$BASE/api/market/rates" >>"$work/feed.log" 2>&1 &
  feed=$!
}
j() { printf '%s' "$2" | sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p" | head -n 1; }
snapshot() { # "state source connected stale hasArbitrage"
  st="$(curl -s "$BASE/api/market/rates/status")"; sc="$(curl -s "$BASE/api/arbitrage/scan")"
  stale="$(printf '%s' "$st" | sed -n 's/.*"staleSymbols":\[\([^]]*\)\].*/\1/p' | tr ',' '\n' | grep -c . || true)"
  echo "$(j state "$st" | tr ' ,' '__') $(j source "$st") $(j connected "$st") $stale $(j hasArbitrage "$sc")"
}
# wait_for LABEL MAX_S CONDITION-ON-$s: prints a line per second, returns the seconds taken
wait_for() {
  label="$1"; max="$2"; cond="$3"; t0="$(date +%s)"
  while :; do
    s="$(snapshot)"; t=$(( $(date +%s) - t0 ))
    printf '      t=%2ss  state=%s source=%s connected=%s stale=%s hasArbitrage=%s\n' "$t" $s >>"$work/timeline.txt"
    if eval "$cond"; then echo "$t"; return 0; fi
    [ "$t" -ge "$max" ] && { echo "timeout"; return 0; }
    sleep 1
  done
}
showlog() { cat "$work/timeline.txt"; : >"$work/timeline.txt"; }

echo "backend $BASE, fake socket ws://127.0.0.1:$WSPORT"
start_ws; start_feed
t="$(wait_for "live" 30 'set -- $s; [ "$1" = live ] && [ "$5" = true ]')"
showlog
verdict "feed live and the +0.4% loophole found ($t s after start)" "$([ "$t" != timeout ] && echo yes || echo no)"

echo
echo "drill 2: \$ kill -9 $feed     (the feed process)"
now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }
kill -9 "$feed"; t_kill="$(now_ms)"; wait "$feed" 2>/dev/null || true; feed=""
last_tick="$(curl -s "$BASE/api/market/rates/status" | sed -n 's/.*"lastTickAt":"\([^"]*\)".*/\1/p')"
t_disc=""; t_clear=""
while :; do
  s="$(snapshot)"; set -- $s; t=$(( $(now_ms) - t_kill ))
  [ -z "$t_disc" ] && [ "$1" = disconnected ] && { t_disc=$t; echo "      +$(awk -v t="$t" 'BEGIN { printf "%.2f", t / 1000 }') s  state=disconnected (connected=false; scan still reports the cycle from quotes under 30 s old)"; }
  [ -z "$t_clear" ] && [ "$5" = false ] && { t_clear=$t; echo "      +$(awk -v t="$t" 'BEGIN { printf "%.2f", t / 1000 }') s  hasArbitrage=false, $4 stale symbols: the scan cleared"; }
  [ -n "$t_disc" ] && [ -n "$t_clear" ] && break
  [ "$t" -ge 45000 ] && break
  sleep 0.25
done
# time the clearing by the server's own clock (the scan's scannedAt), not by when this
# loop noticed it: the polling adds up to ~0.4 s of its own
if [ -n "$t_clear" ]; then
  scanned_at="$(curl -s "$BASE/api/arbitrage/scan" | sed -n 's/.*"scannedAt":"\([^"]*\)".*/\1/p')"
  t_clear="$(python3 -c 'import sys, datetime as d; f = lambda s: d.datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp() * 1000; print(int(f(sys.argv[1]) - int(sys.argv[2])))' "$scanned_at" "$t_kill")"
  since_tick="$(python3 -c 'import sys, datetime as d; f = lambda s: d.datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp(); print("%.3f" % (f(sys.argv[1]) - f(sys.argv[2])))' "$scanned_at" "$last_tick")"
  echo "      the clearing scan ran at $scanned_at: $(awk -v t="$t_clear" 'BEGIN { printf "%.3f", t / 1000 }') s after the kill, $since_tick s after the last tick's timestamp"
fi
echo "      (the last tick was stamped $last_tick; live quotes are valid for 30 s, contract 3.7)"
verdict "status disconnected within 30 s of the kill ($(awk -v t="${t_disc:-99999}" 'BEGIN { printf "%.2f", t / 1000 }') s)" \
  "$([ -n "$t_disc" ] && [ "$t_disc" -le 30000 ] && echo yes || echo no)"
verdict "scan cleared within 30 s of the kill ($(awk -v t="${t_clear:-99999}" 'BEGIN { printf "%.2f", t / 1000 }') s)" \
  "$([ -n "$t_clear" ] && [ "$t_clear" -le 30000 ] && echo yes || echo no)"
echo "      restart the feed (backend untouched):"
start_feed
t="$(wait_for "recover" 40 'set -- $s; [ "$1" = live ] && [ "$5" = true ]')"
showlog | tail -n 3
verdict "feed restarted: live again and the loophole found again in $t s, backend not restarted" "$([ "$t" != timeout ] && echo yes || echo no)"

echo
echo "drill 3: network loss: \$ kill $ws     (the socket server; the feed keeps running)"
kill "$ws"; wait "$ws" 2>/dev/null || true; ws=""
if [ "${FALLBACK_OK:-1}" = 1 ]; then
  t="$(wait_for "fallback" 45 'set -- $s; [ "$1" = fallback ]')"
  showlog | awk 'NR % 5 == 1 || /fallback/'
  verdict "socket lost: the feed falls back to Frankfurter after $t s (trigger 15 s)" "$([ "$t" != timeout ] && echo yes || echo no)"
else
  t="$(wait_for "disconnected" 30 'set -- $s; [ "$1" = disconnected ]')"
  showlog | tail -n 2
  verdict "socket lost: status disconnected after $t s (no network for the fallback)" "$([ "$t" != timeout ] && echo yes || echo no)"
fi
grep -E 'reconnect|socket down|fallback|Frankfurter' "$work/feed.log" | tail -n 4 | sed 's/^/      feed: /'
echo "      network back: restart the socket server"
start_ws
t="$(wait_for "relive" 40 'set -- $s; [ "$1" = live ] && [ "$2" = finnhub-ws ] && [ "$5" = true ]')"
showlog | tail -n 3
verdict "socket back: the feed reconnects by itself, live finnhub-ws again in $t s (backoff caps at 10 s)" "$([ "$t" != timeout ] && echo yes || echo no)"
echo
echo "feed drills: $passed passed, $failed failed"
[ "$failed" -eq 0 ]
