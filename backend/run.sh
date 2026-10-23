#!/usr/bin/env bash
# Run the backend.  Stage 0: ./run.sh [structures]  (a short smoke run of each structure)
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -d out/classes ]; then
  ./build.sh
fi

exec java -cp out/classes ads.Main "$@"
