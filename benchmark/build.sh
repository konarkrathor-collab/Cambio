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
# the source list lives inside out/ (gitignored): regenerating it never dirties the tree
find src -name '*.java' | sort > out/sources.txt
javac -d out @out/sources.txt
echo "Built $(wc -l < out/sources.txt | tr -d ' ') source files -> out/"
