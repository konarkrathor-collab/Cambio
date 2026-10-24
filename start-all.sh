#!/usr/bin/env bash
# Start the whole system on one port: backend + dashboard + feed (macOS and Linux).
#   ./start-all.sh                 http://127.0.0.1:8080
#   PORT=9000 ./start-all.sh       another port
# Environment (all optional):
#   PORT              port for the API and the dashboard (default 8080)
#   DATA_DIR          accounts, id counters and the trade archive (default backend/data)
#   FINNHUB_API_KEY   if set, the feed runs live against Finnhub; if not, it replays the
#                     committed capture (the dashboard badge then says REPLAY, never LIVE).
#                     The key is read from the environment only and is never printed.
# Steps: builds the backend if a source is newer than the build; builds the dashboard
# (npm ci && npm run build) if frontend/dist is missing; creates feed/.venv if missing;
# starts the backend with --static-dir frontend/dist --seed-demo-accounts; waits until
# /api/health is ok; starts the feed; prints the URL. Ctrl-C stops everything it started.
#
# The backend is launched through backend/run.sh, which passes the JVM
# --add-opens jdk.httpserver/sun.net.httpserver=ALL-UNNAMED. The event-stream hub needs it to
# cut off a client that stopped reading (backend/BUGS.md B16). Without it the server still
# runs, logs one warning, and a stalled client keeps a thread and a socket until TCP gives
# up. Do not start the backend with a bare `java -cp ...`.
set -eu
cd "$(dirname "$0")"
root="$(pwd)"
port="${PORT:-8080}"
data="${DATA_DIR:-$root/backend/data}"
capture="feed/samples/finnhub_ticks_2026-10-23.jsonl"

backend_pid=""
feed_pid=""
stopping=0
stop_all() {
  [ "$stopping" = 1 ] && return
  stopping=1
  echo
  echo "stopping..."
  if [ -n "$feed_pid" ]; then
    pkill -P "$feed_pid" 2>/dev/null || true # the replay loop's current feed process
    kill "$feed_pid" 2>/dev/null || true
  fi
  [ -n "$backend_pid" ] && kill "$backend_pid" 2>/dev/null || true
  [ -n "$feed_pid" ] && wait "$feed_pid" 2>/dev/null || true
  [ -n "$backend_pid" ] && wait "$backend_pid" 2>/dev/null || true
  echo "stopped."
}
trap 'stop_all; exit 0' INT TERM
trap stop_all EXIT

need() { command -v "$1" >/dev/null 2>&1 || { echo "start-all: '$1' is required ($2)" >&2; exit 1; }; }
need java "a JDK 17 or newer"
need javac "a JDK 17 or newer"
need curl "for the health check"
need python3 "for the feed"

if curl -s -o /dev/null --max-time 1 "http://127.0.0.1:$port/api/health"; then
  echo "start-all: something already answers on port $port; stop it or set PORT" >&2
  exit 1
fi

# 1. backend build, if needed
if [ ! -d backend/out/classes ] || [ -n "$(find backend/src -name '*.java' -newer backend/out/classes | head -n 1)" ]; then
  echo "building the backend..."
  backend/build.sh >/dev/null
fi

# 2. dashboard build, if missing
if [ ! -f frontend/dist/index.html ]; then
  need npm "Node.js 22.12 or newer, to build the dashboard"
  echo "building the dashboard (npm ci && npm run build)..."
  (cd frontend && npm ci --no-audit --no-fund >/dev/null && npm run build >/dev/null)
fi

# 3. the feed's virtual environment, if missing (live mode needs websocket-client)
py=feed/.venv/bin/python
if [ ! -x "$py" ]; then
  echo "creating feed/.venv..."
  if python3 -m venv feed/.venv >/dev/null 2>&1 && feed/.venv/bin/pip install -q -r feed/requirements.txt >/dev/null 2>&1; then
    :
  else
    echo "  could not install the feed's requirements (offline?)"
    if [ -n "${FINNHUB_API_KEY:-}" ]; then
      echo "start-all: live mode needs feed/.venv with websocket-client" >&2
      exit 1
    fi
    py=python3 # replay needs only the standard library
  fi
fi

# 4. the backend, serving the dashboard on the same port
mkdir -p "$data"
echo "starting the backend on port $port (data in $data)..."
backend/run.sh serve --port "$port" --data-dir "$data" --seed-demo-accounts \
  --static-dir "$root/frontend/dist" >"$data/backend.log" 2>&1 &
backend_pid=$!
tries=0
until curl -s --max-time 2 "http://127.0.0.1:$port/api/health" | grep -q '"status":"ok"'; do
  tries=$((tries + 1))
  if [ "$tries" -gt 60 ] || ! kill -0 "$backend_pid" 2>/dev/null; then
    echo "start-all: the backend did not come up; its log:" >&2
    tail -n 20 "$data/backend.log" >&2
    exit 1
  fi
  sleep 0.5
done

# 5. the feed
rates="http://127.0.0.1:$port/api/market/rates"
if [ -n "${FINNHUB_API_KEY:-}" ]; then
  echo "starting the feed: LIVE (Finnhub; the key is read from the environment and not shown)"
  "$py" feed/feed.py --backend "$rates" >"$data/feed.log" 2>&1 &
  feed_pid=$!
else
  echo "starting the feed: REPLAY of the recorded capture $capture (no FINNHUB_API_KEY set)."
  echo "  The dashboard's data-source badge shows REPLAY: this is recorded data, not the live market."
  ( while :; do "$py" feed/feed.py --replay "$capture" --speed 1 --backend "$rates" || sleep 2; done ) \
    >"$data/feed.log" 2>&1 &
  feed_pid=$!
fi

echo
echo "  Dashboard and API:  http://127.0.0.1:$port"
echo "  Logs:               $data/backend.log, $data/feed.log"
echo "  Ctrl-C stops the backend and the feed."
wait "$backend_pid"
