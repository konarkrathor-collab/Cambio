#!/usr/bin/env bash
# Stage 2 live check (needs FINNHUB_API_KEY in the environment and network access; run it
# during forex market hours, Sunday evening to Friday evening UTC):
#   1. live:     the real Finnhub socket feeds the backend          -> source finnhub-ws
#   2. outage:   the socket URL points at a dead local port, so after 15 s down the feed
#                forwards the real Frankfurter snapshot                -> frankfurter-fallback
#   3. recovery: the real socket again                                -> back to finnhub-ws
set -euo pipefail
cd "$(dirname "$0")"
if [ -z "${FINNHUB_API_KEY:-}" ]; then
  echo "FINNHUB_API_KEY is not set" >&2
  exit 2
fi
py="python3"
[ -x .venv/bin/python ] && py=".venv/bin/python"

../backend/build.sh >/dev/null
port="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')"
base="http://127.0.0.1:${port}"
../backend/run.sh serve --port "$port" >/dev/null 2>&1 &
backend=$!
trap 'kill "$backend" 2>/dev/null || true; wait "$backend" 2>/dev/null || true' EXIT
for _ in $(seq 1 50); do
  curl -sf "$base/api/market/rates/status" >/dev/null 2>&1 && break
  sleep 0.2
done

show() {
  curl -s "$base/api/market/rates/status" | python3 -c '
import json, sys
s = json.load(sys.stdin)
keys = ("state", "source", "replay", "connected", "lastTickAt", "tickCount", "pairsSeen",
        "staleSymbols", "fallbackAsOf", "scanCount", "lastScanFoundCycle")
print("  " + json.dumps({k: s[k] for k in keys}))'
}

echo "1. live for ${LIVE_S:-30}s"
"$py" feed.py --backend "$base/api/market/rates" --duration "${LIVE_S:-30}" 2>&1 | sed 's/^/   feed: /'
show
echo "2. outage: socket unreachable for ${OUTAGE_S:-25}s"
"$py" feed.py --backend "$base/api/market/rates" --ws-url wss://127.0.0.1:9 \
  --duration "${OUTAGE_S:-25}" 2>&1 | sed 's/^/   feed: /'
show
echo "3. recovery for ${RECOVER_S:-15}s"
"$py" feed.py --backend "$base/api/market/rates" --duration "${RECOVER_S:-15}" 2>&1 | sed 's/^/   feed: /'
show
