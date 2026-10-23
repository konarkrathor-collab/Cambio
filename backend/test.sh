#!/usr/bin/env bash
# Build and run the backend tests (plain-Java runner, no JUnit).
#   ./test.sh            parity gates: every production structure, through an adapter,
#                        must pass the benchmark's own correctness gate (plus unit tests)
#   ./test.sh --timing   the same, then production-vs-benchmark timings (same JVM,
#                        interleaved round-robin with the benchmark's harness)
# The benchmark lab (../benchmark) is built first with its own unmodified build.sh and put
# on the test classpath: its workloads, oracles and gates are the reference.
set -euo pipefail
cd "$(dirname "$0")"

../benchmark/build.sh
./build.sh

rm -rf out/test-classes
mkdir -p out/test-classes
find test -name '*.java' | sort > out/test-sources.txt
javac --release 17 -Xlint:all -Xlint:-options -Werror \
  -cp out/classes:../benchmark/out -d out/test-classes @out/test-sources.txt
echo "Built $(wc -l < out/test-sources.txt | tr -d ' ') test source files -> out/test-classes/"

exec java -cp out/classes:out/test-classes:../benchmark/out ads.test.TestRunner "$@"
