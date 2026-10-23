#!/usr/bin/env bash
# Run the backend.
#   ./run.sh demo [--all-trades]   Stage 1 demo: 1,000 seeded orders, then an arbitrage scan
#   ./run.sh accounts [FILE]       reload an accounts file in a fresh JVM and print it
#   ./run.sh archive [DIR] [--records]   decode a Logbook archive: a summary, or every record
#   ./run.sh structures            Stage 0 smoke run of each structure
#   ./run.sh serve [...]           the API server (see documentation/09_api_contract.md)
# JAVA_OPTS (optional) adds JVM flags, e.g. JAVA_OPTS="-Xlog:gc:file=gc.log". The JVM always gets
# --add-opens jdk.httpserver/sun.net.httpserver=ALL-UNNAMED so the event-stream hub can cut off
# a client that stopped reading (backend/BUGS.md B16).
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -d out/classes ]; then
  ./build.sh
fi

exec java ${JAVA_OPTS:-} --add-opens jdk.httpserver/sun.net.httpserver=ALL-UNNAMED -cp out/classes ads.Main "$@"
