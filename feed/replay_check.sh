#!/usr/bin/env bash
# Stage 2 gate, end to end with no key and no network:
#   start the backend, replay a recorded Finnhub capture through feed.py --replay, then show
#   GET /api/market/rates/status (ticks received, scans run) and check the backend survives
#   garbage input.
#   ./replay_check.sh [CAPTURE.jsonl] [SPEED]
set -euo pipefail
cd "$(dirname "$0")"
capture="${1:-samples/finnhub_ticks_2026-10-23.jsonl}"
speed="${2:-20}"

../backend/build.sh >/dev/null
port="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')"
base="http://127.0.0.1:${port}"
log="$(mktemp)"
../backend/run.sh serve --port "$port" >"$log" 2>&1 &
backend=$!
trap 'kill "$backend" 2>/dev/null || true; wait "$backend" 2>/dev/null || true; rm -f "$log"' EXIT

for _ in $(seq 1 50); do
  curl -sf "$base/api/market/rates/status" >/dev/null 2>&1 && break
  sleep 0.2
done
echo "backend: $(grep -m1 listening "$log")"
echo "status before replay:"
curl -s "$base/api/market/rates/status"; echo

echo
echo "\$ python3 feed.py --replay $capture --speed $speed --backend $base/api/market/rates"
python3 feed.py --replay "$capture" --speed "$speed" --backend "$base/api/market/rates"

echo
echo "status after replay:"
status="$(curl -s "$base/api/market/rates/status")"
python3 -c 'import json,sys; print(json.dumps(json.loads(sys.argv[1]), indent=2))' "$status"
python3 - "$status" <<'PY'
import json, sys
s = json.loads(sys.argv[1])
checks = {
    "ticks received": s["tickCount"] > 0,
    "scanner ran": s["scanCount"] > 0,
    "labelled as a replay": s["replay"] is True,
    "source finnhub-ws": s["source"] == "finnhub-ws",
}
for name, ok in checks.items():
    print(("PASS  " if ok else "FAIL  ") + name)
sys.exit(0 if all(checks.values()) else 1)
PY

echo
echo "garbage input:"
for body in '{not json' '' '[1,2,3]' '{"source":"finnhub-ws","ticks":"x"}' \
    '{"source":"finnhub-ws","ticks":[{"symbol":"OANDA:EUR_USD","price":-1,"ts":1}]}'; do
  printf '  POST %-72s -> %s\n' "'${body:0:70}'" \
    "$(curl -s -o /dev/null -w '%{http_code}' -X POST --data-binary "$body" "$base/api/market/rates")"
done
printf '  POST (2 MB of junk) -> %s\n' "$(head -c 2097152 /dev/urandom | curl -s -o /dev/null \
  -w '%{http_code}' -X POST --data-binary @- "$base/api/market/rates")"
printf '  raw bytes on the socket -> %s\n' \
  "$(printf '\x00\x01\x02 junk\r\n\r\n' | (exec 3<>"/dev/tcp/127.0.0.1/$port"; cat >&3; timeout 2 cat <&3 >/dev/null; echo sent) 2>&1 || true)"
code="$(curl -s -o /dev/null -w '%{http_code}' "$base/api/market/rates/status")"
if [ "$code" = "200" ]; then
  echo "PASS  backend still answering after garbage (status $code)"
else
  echo "FAIL  backend not answering after garbage (status $code)"
  exit 1
fi
