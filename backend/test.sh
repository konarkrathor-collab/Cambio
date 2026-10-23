#!/usr/bin/env bash
# Build and run the backend tests (plain-Java runner, no JUnit).
#   ./test.sh            parity gates: every production structure, through an adapter,
#                        must pass the benchmark's own correctness gate (plus unit tests)
#   ./test.sh --timing   the same, then production-vs-benchmark timings (same JVM,
#                        interleaved round-robin with the benchmark's harness)
#   ./test.sh --scan-timing   the same, then the scanner's per-scan time at V=12 and V=20
#   ./test.sh --only TEXT     run only the checks whose name contains TEXT
# The benchmark lab (../benchmark) is built first with its own unmodified build.sh and put
# on the test classpath: its workloads, oracles and gates are the reference.
set -euo pipefail
cd "$(dirname "$0")"

../benchmark/build.sh
./build.sh

# doc 08 §3 source rules, checked on every run
echo "== source rules (doc 08 §3) =="
rules_failed=0
# structures and the matching path: no java.util (the api/util layers may use it, rule 7)
structure_dirs="src/ads/engine src/ads/scanner src/ads/vault src/ads/directory src/ads/logbook"
if grep -rn "java\.util" $structure_dirs; then
  echo "FAIL  java.util in a structure or matching-path package"
  rules_failed=1
else
  echo "PASS  no java.util in engine, scanner, vault, directory, logbook"
fi
if grep -rnwE "double|float|Double|Float" src/ads/engine; then
  echo "FAIL  floating point in src/ads/engine (orders are priced and matched in integers)"
  rules_failed=1
else
  echo "PASS  no double/float in src/ads/engine"
fi
# an order's remaining quantity may only be written by PriceLevel.fill (and set once by
# the Order constructor)
if grep -rnE "\.remaining[[:space:]]*([-+*/]?=[^=]|\+\+|--)|(\+\+|--)[[:space:]]*[A-Za-z_.]*\.remaining" src \
    | grep -v "^src/ads/engine/PriceLevel.java:" | grep -v "this.remaining = quantity;"; then
  echo "FAIL  Order.remaining written outside PriceLevel.fill"
  rules_failed=1
else
  echo "PASS  Order.remaining is written only in PriceLevel (fill) and the Order constructor"
fi
if [ "$rules_failed" -ne 0 ]; then
  exit 1
fi

rm -rf out/test-classes
mkdir -p out/test-classes
find test -name '*.java' | sort > out/test-sources.txt
javac --release 17 -Xlint:all -Xlint:-options -Werror \
  -cp out/classes:../benchmark/out -d out/test-classes @out/test-sources.txt
echo "Built $(wc -l < out/test-sources.txt | tr -d ' ') test source files -> out/test-classes/"

exec java -cp out/classes:out/test-classes:../benchmark/out ads.test.TestRunner "$@"
