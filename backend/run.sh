#!/usr/bin/env bash
# Run the backend.
#   ./run.sh demo [--all-trades]   Stage 1 demo: 1,000 seeded orders, then an arbitrage scan
#   ./run.sh accounts [FILE]       reload an accounts file in a fresh JVM and print it
#   ./run.sh archive [DIR]         decode a Logbook archive and summarise it
#   ./run.sh structures            Stage 0 smoke run of each structure
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -d out/classes ]; then
  ./build.sh
fi

exec java -cp out/classes ads.Main "$@"
