# Handoff Brief — Remaining Benchmark Modules

**You (the agent reading this) own this file's task list end-to-end.** The orchestrating
session (Claude) wrote this brief and will not implement any of it — that's deliberate, this
handoff is testing whether the brief alone is sufficient for another agent to produce
harness-consistent, trustworthy work without hand-holding. Read this whole file before
touching any code.

---

## 0. Required reading before you write anything

Read these, in order, before starting Task 1:

1. `benchmark/README.md` — the pipeline discipline (`CONTRACT → DATA+ORACLE → STRUCTURES → TIMING → RESULTS`) and the 7 fairness rules. These are not optional style preferences — rule 6 ("no `java.util` containers inside candidates") and rule 2 (independent correctness oracle) are load-bearing for every module below.
2. `benchmark/src/ads/bench/ladder/*.java` — the reference implementation of the pattern: `LadderOps` (contract), `LadderWorkload` (seeded deterministic data generator), `TreeMapLadder` (oracle), `RadixLadder` (a clean from-scratch candidate), `LadderBench` (verify/timed/failed harness).
3. `benchmark/src/ads/bench/arbitrage/*.java` — the second reference implementation, including `BruteForceOracle.java` (an oracle that is a genuinely *different algorithm family* from any candidate — this is the bar: an oracle that shares logic with a candidate is not a real correctness check) and `CycleTrace.java` (a small shared helper extracted once duplication appeared — that's the right instinct, don't over-abstract before you see the duplication).
4. `benchmark/results/RESULTS.md` — read the whole thing, especially **Run 4's "two real bugs found and fixed"** section. This project's standing norm: when your own code disagrees with your own oracle, that's a success of the harness, not a failure to hide. Document bugs you find the same way — what broke, why, the fix — never silently correct and move on.
5. `documentation/05_data_structure_selection_rationale.md` — sections 2, 4, 5, 6 (Order Queue, Account Cache, Ticker Lookup, Log Compression) are the four subsystems you're benchmarking. Each already has a weighted-rubric prediction of which candidate wins and why the others lose — **your job is to test those predictions against real measurements, not to re-litigate the rubric.** Sections 1 and 3 (Price Ladder, Arbitrage) already went through this exact benchmark-then-feedback cycle — read their "Benchmark Evidence (measured)" subsections as the template for what you'll write for sections 2/4/5/6.

**Also read `.gitignore`** — `benchmark/out/` and `*.class` are already excluded; don't fight that.

---

## 1. Task: Queue benchmark (`ads.bench.queue`)

**Subsystem:** doc 05 §2 — order queue per price level (FIFO execution + arbitrary cancellation by ID).

- **Contract (`QueueOps`):** `enqueue(orderId, volume)`, `dequeueHead()` (returns and removes the head), `cancel(orderId)` (removes an arbitrary order by ID), `size()`, `peekHead()`.
- **Candidates (5, matching doc 05 §2's table):** plain array/dynamic array, singly linked list, doubly linked list (no index — cancel requires an O(N) scan to find the node), balanced tree keyed by orderId, **doubly linked list + hash map** (the project's pick).
- **Oracle:** a `java.util.LinkedHashMap`-backed reference implementation is fine here — it's structurally unrelated to any hand-rolled candidate's internals, which satisfies the independence requirement even though it's a `java.util` container (oracle-only use is explicitly allowed by fairness rule 6).
- **Workload generator:** a seeded mix of enqueue / dequeue-head / cancel-by-id operations, matching the `LadderWorkload` pattern (deterministic RNG, liveness tracking so cancels always target a real order). Base the enqueue/cancel ratio on realistic order flow (most orders eventually execute via dequeue-head; a minority get cancelled before they reach the head — pick a ratio and justify it in the class doc comment, don't just guess silently).
- **Expected finding to verify:** DLL+HashMap should win cleanly (doc 05 called this the "no alternative even comes close" subsystem) — if your numbers disagree, that itself is the interesting result; report it honestly rather than tuning the workload until it agrees.

## 2. Task: Cache benchmark (`ads.bench.cache`)

**Subsystem:** doc 05 §4 — active trader / margin account cache.

- **Contract (`CacheOps`):** `access(traderId)` (read-or-touch, returns a dummy balance), `evictIfNeeded()` or have `access` handle eviction internally, `size()`, `contains(traderId)`.
- **Candidates (4):** hash map with separate chaining (no eviction), hash map with open addressing, **hash map + LRU eviction list** (the project's pick), splay tree.
- **Oracle:** the challenge here is Zipfian access needs a correctness definition beyond "did it return the right value" — what you're actually verifying is (a) every `access` returns the correct stored value, and (b) for the LRU candidate specifically, eviction order matches an independent LRU reference (e.g. a `java.util.LinkedHashMap` in access-order mode, which has this built in — legitimate oracle use).
- **Workload generator:** **this is the one place a uniform random workload would be a methodology error.** Doc 05 §4's whole justification for Splay Tree and LRU is Zipfian access (a small minority of accounts generate most traffic) — generate a Zipfian-distributed sequence of trader IDs (pick a skew parameter, document why), not uniform random. A uniform workload here would not test what doc 05 is actually claiming.
- **Expected finding to verify:** Hash Map + LRU should win on raw speed; the interesting comparison is whether Splay Tree's amortized advantage actually shows up under genuine Zipfian skew, or whether doc 05's rejection (no bounded memory, worse worst-case) holds even there.

## 3. Task: Ticker benchmark (`ads.bench.ticker`)

**Subsystem:** doc 05 §5 — currency ticker lookup & autocomplete.

- **Contract (`TickerOps`):** `insert(ticker)`, `contains(ticker)` (exact match), `prefixSearch(prefix)` (returns all tickers starting with `prefix` — this is the operation Hash Set structurally cannot perform, per doc 05's own flagged rubric limitation; make sure your contract actually forces that failure to be visible, don't design around it).
- **Candidates (4):** hash set (expect it to fail or be excluded from the prefix-search portion of the benchmark — document how you handle a candidate that can't implement part of the contract, don't just skip silently), sorted array + binary search + linear scan for the prefix range, ternary search tree, **trie** (the project's pick).
- **Oracle:** a straightforward linear scan over a `java.util.ArrayList` of all tickers (contains = linear `.contains()`, prefix search = filter by `.startsWith()`) — obviously correct, trivially independent of every candidate's indexing strategy.
- **Workload / data:** real-ish currency ticker strings over a small fixed alphabet (`A`-`Z`, `/`), e.g. `USD/EUR`, `USD/JPY`, `EUR/GBP` — doc 05 §5 already names the roster shape, generate the realistic ~hundred-pair universe and a seeded sequence of insert/contains/prefixSearch calls.
- **Expected finding to verify:** doc 05 flagged a genuine rubric limitation here — Hash Set, Sorted Array, and Ternary Search Tree all tied at 3.65 despite Hash Set being incapable of prefix search at all. Your benchmark should make that incapability *undeniable* (a FAIL row or an explicit "N/A — operation unsupported" row for Hash Set on the prefix-search workload), which is a stronger argument than the rubric note alone.

## 4. Task: Compression benchmark (`ads.bench.compression`)

**Subsystem:** doc 05 §6 — trade telemetry log compression.

This one is shaped differently from the other three — it's not a live data structure under repeated operations, it's encode-then-decode-then-measure-ratio on batches of text. Adapt the harness pattern rather than forcing it to fit `verify`/`timed` unchanged:

- **Contract (`CompressorOps`):** `byte[] encode(byte[] input)`, `byte[] decode(byte[] encoded)`.
- **Candidates (3, matching what's actually implementable from scratch in-scope — doc 05 §6 lists more as *comparison* context, not all need full implementations):** RLE, LZW, **Huffman** (the project's pick). If you have time, a 4th (simple order-0 arithmetic coding) strengthens the "Huffman trades ratio for implementation safety" argument doc 05 makes — optional, don't let it block shipping the other three.
- **Oracle / correctness gate:** `decode(encode(x)) == x` for every input, byte-for-byte — this is a stronger and simpler correctness bar than the other benchmarks' oracle-agreement pattern, use it.
- **Data:** synthetic trade-log-shaped text (structured, field-like, repetitive in places — not random bytes, which would be unfairly hostile to every compressor including the real pick) — a few batch sizes.
- **What to measure (different shape from the other benchmarks' BenchResult usage):** compression ratio (`encoded.length / input.length`), encode time, decode time. You may need a small adapter so this fits `BenchResult`'s existing columns sensibly, or a parallel lightweight result type if forcing the fit would be misleading — your call, but document which you chose and why in the class/README.
- **Expected finding to verify:** Huffman should lose on ratio to LZW (doc 05 already concedes this) but should be the only one that's trivial to get byte-perfect correct from scratch — if your from-scratch LZW or RLE has a correctness bug, that itself supports doc 05's "implementation risk" argument; don't spend excessive time debugging it into perfection if it illustrates the point by failing honestly instead.

## 5. Task: Ladder v2 (`ads.bench.ladder`, extending the existing package)

**Subsystem:** doc 05 §1, completing the candidate set + the syllabus tree requirement (BST, AVL, Splay, Red-Black, 2-3, B-tree, B+ — per `benchmark/README.md`'s existing roadmap checklist).

- You already have `LadderOps`, `LadderWorkload`, `TreeMapLadder` (oracle), and the harness — this task is purely adding candidates to the existing package, no new contract/oracle/workload needed.
- Priority order if you don't have time for all 7: **Red-Black Tree first** (it's the one doc 05 §1 directly compares Radix Tree against by name — right now that comparison uses `TreeMap` as a stand-in; a hand-rolled Red-Black Tree candidate is more defensible evidence than a stand-in), then AVL (doc 05 claims it does *more* rotations than Red-Black — verify this with an actual rotation counter, not just timing), then the rest as time allows.
- Also implement the **sorted-insertion adversarial pattern** flagged as still-open in `RESULTS.md`'s "Next" section — a workload where ticks arrive in monotonic order, which should make the Unbalanced BST candidate (if you add one) degenerate toward O(P), demonstrating the claim in doc 05 §1 ("Unbalanced BST... degenerates toward O(P) under sorted or monotonic order arrival") that the current document explicitly says it has no data to back.

---

## Non-negotiable acceptance criteria for every task above

1. **Builds clean:** `./build.sh` with zero errors, before you consider a task done.
2. **Correctness-gated:** every candidate is verified against its independent oracle before it is ever timed — a structure that fails the gate is reported FAIL, never silently excluded from the table.
3. **CLI wired into `Main.java`:** follow the existing `ladder` / `arbitrage` subcommand pattern exactly — don't invent a different CLI shape per module.
4. **`RESULTS.md` updated** with a new `## Run N — <module>` section, same format as the existing runs: command used, data description, results table, findings versus the relevant doc 05 section's claims, caveats. Use real numbers from an actual run on this machine — never fabricate or estimate a result you didn't measure.
5. **`benchmark/README.md`'s roadmap checklist** gets its `[ ]` flipped to `[x]` for whatever you complete, same style as the existing entries.
6. **`documentation/05_data_structure_selection_rationale.md`** gets a `### Benchmark Evidence (measured — see ...)` subsection added to the relevant section (2, 4, 5, or 6), in the exact structure sections 1 and 3 already use as the template — AND the Final Summary table's "Evidence" column gets updated from "Rubric only" to "**Measured**" for that row.
7. **Commit after each completed task**, not one giant commit at the end — one task, one buildable+tested state, one commit, so the history itself documents the incremental process. No attribution lines in commit messages (standing instruction for this repo).
8. **If you find a bug — in your own candidate, in the harness, in an earlier document's claim — document it in `RESULTS.md` the way Run 4 did.** Do not quietly fix and move on. The debugging trail is itself evidence this project's claims are checked, not asserted.

## What NOT to do

- Don't touch the `ladder` or `arbitrage` modules' existing passing code except to add new ladder candidates under Task 5 — they're done and verified.
- Don't soften or walk back doc 05's existing claims to make your numbers look like a cleaner win than they are — if a result is a close call or contradicts the rubric, say so, the same way doc 05 already does for Price Ladder.
- Don't skip the oracle step "because the candidate looks obviously correct" — that exact assumption is what caused the Floyd-Warshall bug in Run 4.
