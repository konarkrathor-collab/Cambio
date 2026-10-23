#!/usr/bin/env bash
# Stage 6 failure drill 4: start a fresh seeded backend and fuzz every endpoint.
#   scripts/fuzz.sh [SEED] [REQUESTS]       default seed 1, 20000 requests
set -eu
cd "$(dirname "$0")/.."
seed="${1:-1}"
n="${2:-20000}"
work="$(mktemp -d "${TMPDIR:-/tmp}/ads-fuzz.XXXXXX")"
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
status=0
python3 scripts/fuzz.py --base "http://127.0.0.1:$(cat "$work/port")" --seed "$seed" --requests "$n" || status=$?
kill -0 "$server" 2>/dev/null && echo "PASS  the server process is still running" || { echo "FAIL  the server process died"; status=1; }
traces="$(grep -c 'Exception\|SEVERE' "$work/server.log" || true)"
echo "      server log: $traces lines with Exception/SEVERE"
grep -m 3 'Exception\|SEVERE' "$work/server.log" | cut -c1-200 | sed 's/^/      /' || true
exit "$status"
