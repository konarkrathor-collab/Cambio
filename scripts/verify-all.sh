#!/usr/bin/env bash
# Every gate of the project, in sequence, from a fresh clone (macOS and Linux).
#   scripts/verify-all.sh            everything (about 12 minutes)
#   scripts/verify-all.sh --quick    skips the slow timing drills (flood, feed, SSE)
# Prints PASS or FAIL with the time of each step and a final table, and exits non-zero if
# anything failed. Each step's full output is kept in a log directory it names at the end.
# Needs: a JDK 17+, Node.js 22.12+ with npm, Python 3, curl. The feed drill also needs
# network access for the real Frankfurter fallback (FALLBACK_OK=0 runs it without);
# feed/.venv is created if missing (pip needs the network once).
set -u
cd "$(dirname "$0")/.."
quick=0
[ "${1:-}" = "--quick" ] && quick=1
logs="$(mktemp -d "${TMPDIR:-/tmp}/ads-verify.XXXXXX")"

names=()
results=()
times=()
step() { # step NAME COMMAND...
  name="$1"; shift
  log="$logs/$(printf '%02d' "$((${#names[@]} + 1))")-$(printf '%s' "$name" | tr ' /' '__').log"
  printf '%-34s ' "$name"
  t0="$(date +%s)"
  if "$@" >"$log" 2>&1; then r=PASS; else r=FAIL; fi
  t=$(( $(date +%s) - t0 ))
  printf '%s  %4ss\n' "$r" "$t"
  if [ "$r" = FAIL ]; then
    echo "      last lines of $log:"
    tail -n 15 "$log" | sed 's/^/      | /'
  fi
  names+=("$name"); results+=("$r"); times+=("$t")
}
skip() {
  printf '%-34s SKIP  (--quick)\n' "$1"
  names+=("$1"); results+=("SKIP"); times+=("0")
}

frontend() ( # a subshell: the cd must not leak into the later steps
  cd frontend || exit 1
  if [ ! -d node_modules ]; then npm ci --no-audit --no-fund || exit 1; fi
  npm test && npm run build
)
feed_venv() {
  [ -x feed/.venv/bin/python ] && return 0
  python3 -m venv feed/.venv && feed/.venv/bin/pip install -q -r feed/requirements.txt
}

echo "verify-all: $( [ "$quick" = 1 ] && echo quick || echo full ) run, logs in $logs"
echo
step "backend suite (test.sh)"        backend/test.sh
step "feed suite (test.sh)"           feed/test.sh
step "frontend tests and build"       frontend
step "smoke (every endpoint)"         backend/scripts/smoke.sh
step "replay gate"                    feed/replay_check.sh
step "end to end (e2e.sh)"            scripts/e2e.sh
step "logbook check"                  scripts/logbook_check.sh
if [ "$quick" = 1 ]; then skip "flood check"; else step "flood check" scripts/flood_check.sh; fi
step "drill 1: kill -9"               scripts/drill_kill9.sh
if [ "$quick" = 1 ]; then skip "drills 2-3: feed"; else
  step "feed venv"                    feed_venv
  step "drills 2-3: feed"             scripts/drill_feed.sh
fi
step "drill 4: fuzz (seed 1, 5000)"   scripts/fuzz.sh 1 5000
if [ "$quick" = 1 ]; then skip "drill 5: SSE"; else step "drill 5: SSE" scripts/drill_sse.sh; fi
step "documentation links"            python3 scripts/check_links.py

echo
echo "step                               result  time"
failed=0
total=0
i=0
while [ "$i" -lt "${#names[@]}" ]; do
  printf '%-34s %-6s %4ss\n' "${names[$i]}" "${results[$i]}" "${times[$i]}"
  [ "${results[$i]}" = FAIL ] && failed=$((failed + 1))
  total=$((total + ${times[$i]}))
  i=$((i + 1))
done
echo
echo "verify-all: $failed failed, ${total}s in all; logs in $logs"
[ "$failed" -eq 0 ]
