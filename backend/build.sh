#!/usr/bin/env bash
# Build the backend with plain javac (no Gradle/Maven, no external jars).
# --release 17 pins both the language level and the JDK API to Java 17, so a newer JDK
# cannot let code through that a Java 17 toolchain would reject.
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v javac >/dev/null 2>&1; then
  echo "javac not found. Install a JDK 17 or newer." >&2
  exit 1
fi

# compile into a scratch dir and swap it in only on success, so a failed build never
# leaves a partial out/classes behind for run.sh to pick up
rm -rf out/classes out/classes.tmp
mkdir -p out/classes.tmp
# the source list lives inside out/ (gitignored): regenerating it never dirties the tree
find src -name '*.java' | sort > out/sources.txt
javac --release 17 -Xlint:all -Werror -d out/classes.tmp @out/sources.txt
mv out/classes.tmp out/classes
echo "Built $(wc -l < out/sources.txt | tr -d ' ') source files -> out/classes/"
