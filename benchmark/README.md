# ADS Benchmark Lab — Order Matching System

Research harness that resolves the **design-stage claims** in `documentation/` (especially
`05_data_structure_selection_rationale.md`, which explicitly says *"we have not yet
benchmarked any of the three"*) with **measured evidence**.

This is a lab, not shipped project code. Winning implementations graduate into the engine.

---

## Pipeline (the agreed order)

```
0. CONTRACT  -> frozen interfaces (e.g. ladder/LadderOps.java) + metric definitions
1. DATA      -> seeded generators produce ONE immutable workload every structure replays
   + ORACLE  -> obviously-correct reference (java.util only HERE) = correctness gate
2. STRUCTURES-> candidates implemented from scratch behind the contract
3. TIMING    -> warmup + reps + allocation counting (only after the oracle passes)
4. RESULTS   -> results/raw_results.csv + results/RESULTS.md narrative
```

**Rule: a structure that has not passed the oracle on the frozen workload is never timed.**

## Fairness rules (why these numbers can be trusted)

1. **Deterministic inputs** — SplitMix64 seeded RNG; every candidate replays the *identical*
   operation log (bit-for-bit), so differences are attributable to the structure alone.
2. **Correctness gate** — every candidate must match the oracle on (a) an accumulated
   checksum over all query results and (b) the final `(tick -> volume)` state.
3. **JIT discipline** — N warmup replays are run and discarded before measurement
   (Java JIT needs thousands of iterations to reach steady state). Arbitrage scans take
   microseconds, so that bench batches scans per timed rep and warms each candidate for
   ≥ 2,000 scans; its timed unit is build + detect (cycle reconstruction is verification-only).
3b. **Interleaved timing** — warmup and timed reps run round-robin across ALL verified
   candidates of a bench (rotating start, `core/Interleaved.java`), never one candidate's
   reps back-to-back. Sequential timing let JIT/profile state from earlier candidates leak
   into later ones (Johnson — pure Bellman-Ford delegate — once timed 2–3× faster than
   Bellman-Ford purely from running later; see `results/RESULTS.md` Run 12).
3c. **Cross-machine caveat** — timing gaps under ~15–20% have flipped between machines;
   treat them as ties unless they reproduce across seeds and runs. Checksums, op counts,
   ratios and rotation counts are exact and machine-independent.
4. **Reporting** — median of R timed full-replay runs + min/max spread % (the spread IS the
   empirical "Determinism" data point for the rationale doc). `System.gc()` between runs.
5. **Memory** — bytes-allocated-per-operation via the JVM's per-thread allocation counters
   (fair, GC-timing independent) instead of RSS.
6. **No `java.util` containers inside candidates.** Allowed only in the oracle and harness.
7. Single-threaded; run with nothing else heavy on the machine (M4 P/E-core scheduling adds
   noise — medians absorb it).

## Bench roadmap (doc 05 candidates + syllabus trees)

- [x] **Ladder v1** — unsorted array, sorted array, flat array, skip list, radix-16 (+ TreeMap baseline/oracle)
- [x] **Ladder v2a** — BST, AVL, splay, red-black hand-rolled (rotation counters; RB retires the TreeMap stand-in — see RESULTS.md Run 10)
- [x] **Ladder v2b** — 2-3 tree (B-tree order 3), B-tree order 32, B+ tree order 32 (see RESULTS.md Run 13a)
- [x] **Queue** — array, singly LL, DLL, DLL+hash, tree-by-ID (order queue + O(1) cancel; seeded 300k-op workload, oracle-gated — see RESULTS.md Run 6)
- [x] **Cache** — hash chaining, open addressing, hash+LRU, splay (Zipf workloads; full-residency LRU verification — see RESULTS.md Run 7)
- [x] **Arbitrage** — Bellman-Ford, SPFA, Floyd-Warshall, Johnson, Tarjan+BF (negative cycles; real Frankfurter data + synthetic, oracle-gated at small V)
- [x] **Ticker** — hash set, sorted array, TST, trie (+ autocomplete; two-phase exact/prefix workload, explicit N/A row for hash set — see RESULTS.md Run 8)
- [x] **Compression** — Huffman, RLE, LZW (+ order-0 arithmetic; byte-perfect round-trip gate — see RESULTS.md Run 9)

## Run it

```bash
./build.sh
./run.sh ladder --n 300000 --seed 42 --reps 7 --warmup 3
# ladder flags: --n --seed --reps --warmup --base --range --band --pattern --out

./run.sh ladder --pattern sorted --n 12000 --band 4000 --seed 42 --reps 7 --warmup 3
# adversarial sorted-insertion variant of the ladder bench (BST degeneration demo)

./run.sh queue --n 300000 --seed 42 --reps 7 --warmup 3
./run.sh queue --profile deep --n 300000 --seed 42 --reps 7 --warmup 3
# queue flags: --n --seed --reps --warmup --profile shallow|deep --out
# "deep" = 30% cancel rate, ~15k-order resting book (RESULTS.md Run 13b)

./run.sh cache --n 300000 --seed 42 --reps 7 --warmup 3
# cache flags: --n --seed --reps --warmup --universe --capacity --zipf --out

./run.sh ticker --n 300000 --seed 42 --reps 7 --warmup 3
# ticker flags: --n --seed --reps --warmup --out

./run.sh compression --seed 42 --reps 7 --warmup 3
# compression flags: --seed --reps --warmup --out

./run.sh arbitrage --graph real --seed 42 --reps 7 --warmup 3
./run.sh arbitrage --graph synthetic --v 8 --inject true --seed 42 --reps 7 --warmup 3
# arbitrage flags: --graph real|synthetic --v --inject true|false --seed --reps --warmup --out
# "real" uses the captured Frankfurter snapshot in data/; correctness is oracle-gated
# (independent brute-force) for V<=10, and cross-checked against Bellman-Ford's own verdict
# above that — always stated explicitly in the console output, never silently assumed.
```

Output: console table + appended rows in `results/raw_results.csv`.
