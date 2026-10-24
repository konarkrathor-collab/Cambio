# Committed measurements (v1.0.0)

The README, the demo script, doc 04 §3B and the final report quote numbers only from these files, plus `benchmark/results/RESULTS.md`. Every file is the raw console output of the command shown. Nothing was edited except the removal of the JVM's `Picked up JAVA_TOOL_OPTIONS` line.

**Run:** 2026-10-24, on the Stage 7a commit (`8f09b5d`) plus the Stage 7b working tree. At that point the working tree differed from 7a only in documentation and in `ScannerTiming`, which gained the V=7 case. The machine is described in [environment.txt](./environment.txt): Linux container, Intel Xeon @ 2.10 GHz, 4 cores visible, 15.7 GB, OpenJDK 21.0.12, Node 22.22, Python 3.13. Steps ran one after another on an otherwise idle machine. Timings depend on the machine; counts, checksums, ratios and pass/fail results do not.

| File | Command (from the repository root) | Used for |
| :--- | :--- | :--- |
| [environment.txt](./environment.txt) | `uname`, `java -version`, … | The machine |
| [scan_timing.txt](./scan_timing.txt) | `backend/test.sh --scan-timing` | Scan time at V = 7, 12, 20 (doc 03 §3, doc 04 §3B) |
| [parity_timing.txt](./parity_timing.txt) | `backend/test.sh --timing` | Production versus benchmark per structure (doc 03 §8) |
| [parity_gates.txt](./parity_gates.txt) | `backend/test.sh`, the parity PASS lines and the RESULT line | The six structures against the benchmark's own oracles; 207 passed, 0 failed |
| [benchmark_run15.txt](./benchmark_run15.txt) | the ten `benchmark/run.sh …` commands listed in it, `--reps 7 --warmup 3`, seed 42 | Run 15 in [RESULTS.md](../../benchmark/results/RESULTS.md#run-15--final-run-on-the-finished-code-stage-7b) |
| [simulator.txt](./simulator.txt) | `backend/scripts/simulate.sh N` for N = 5,000, 50,000, 200,000, 1,000,000, and `PROFILE=deep backend/scripts/simulate.sh N` for N = 5,000, 50,000, 200,000 | Throughput, cancel latency, engine versus naive baseline (doc 04 §3B) |
| [threshold_study.txt](./threshold_study.txt) | `backend/run.sh threshold-study "$PWD/feed/samples/finnhub_ticks_2026-10-23.jsonl"` | The 0.02% threshold (doc 06 §5) |
| [drill_e2e.txt](./drill_e2e.txt) | `scripts/e2e.sh` | End-to-end check |
| [drill_logbook_check.txt](./drill_logbook_check.txt) | `scripts/logbook_check.sh` | Every trade reaches the archive |
| [drill_flood_check.txt](./drill_flood_check.txt) | `scripts/flood_check.sh` | Scanning never blocks matching |
| [drill_kill9.txt](./drill_kill9.txt) | `scripts/drill_kill9.sh` | Drill 1 |
| [drill_feed.txt](./drill_feed.txt) | `scripts/drill_feed.sh` | Drills 2 and 3 |
| [drill_fuzz.txt](./drill_fuzz.txt) | `scripts/fuzz.sh 1 5000` | Drill 4 |
| [drill_sse.txt](./drill_sse.txt) | `scripts/drill_sse.sh` | Drill 5 |
| [soak.txt](./soak.txt) | `scripts/soak.sh 600` | Drill 6 |
| [verify_all.txt](./verify_all.txt) | `scripts/verify-all.sh`, from a fresh clone | The release gate |

**About the drill files.**
- The `drill_*.txt` files are the logs of one `scripts/verify-all.sh` run made during the measurement pass.
- In that run every gate passed except the link check. It failed only because DEMO.md, the final report and this file did not exist yet.
- [verify_all.txt](./verify_all.txt) is a separate, later run, on a fresh clone of the Stage 7b commit as it was before this file's content was added. Every step passed: 14 steps, 0 failed, 341 s. Its table is the gate evidence. The final commit differs from that one only in this file and in this paragraph.
- `start-all.sh` and DEMO.md were checked with `frontend/e2e/demo-script.mjs`, and the golden path with `frontend/e2e/golden-path.mjs --preview`. Both runs are reported in the Stage 7 report; neither writes a file here.
