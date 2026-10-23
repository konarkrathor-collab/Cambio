#!/usr/bin/env bash
# Run the ADS benchmark lab.
#   ./run.sh ladder --n 300000 --seed 42 --reps 7 --warmup 3
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -d out ]; then
  ./build.sh
fi

# --add-opens lets the footprint bench walk java.util.TreeMap and its boxed Longs (oracle row only)
exec java --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED -cp out ads.bench.Main "$@"
