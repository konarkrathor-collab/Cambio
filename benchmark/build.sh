#!/usr/bin/env bash
# Build the ADS benchmark lab (plain javac - no external build tools needed).
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v javac >/dev/null 2>&1; then
  echo "javac not found. Install a JDK (e.g. 'brew install openjdk@17')." >&2
  exit 1
fi

rm -rf out
mkdir -p out
find src -name '*.java' > .sources.txt
javac -d out @.sources.txt
echo "Built $(wc -l < .sources.txt | tr -d ' ') source files -> out/"
