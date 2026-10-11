#!/usr/bin/env bash
# Run the ADS benchmark lab.
#   ./run.sh ladder --n 300000 --seed 42 --reps 7 --warmup 3
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -d out ]; then
  ./build.sh
fi

exec java -cp out ads.bench.Main "$@"
