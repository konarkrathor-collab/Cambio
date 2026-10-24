# Gates, integration checks and failure drills

**`verify-all.sh`** runs every gate below in sequence (backend, feed and frontend suites,
smoke, replay gate, e2e, logbook, flood, the kill -9, feed and SSE drills, a short fuzz, and
`check_links.py`). It prints PASS or FAIL with timings and a final table, and exits non-zero
if anything failed. `--quick` skips the three slow timing drills. A full run takes about
6 minutes (335 s in the Stage 7b measurement run). The soak (`soak.sh`, 11 minutes) is not part of it. **`check_links.py`**
checks every relative link and `#anchor` in every Markdown file. To start the whole system
for a demo, use `../start-all.sh` (see the root README).

Every script starts its own fresh backend on an ephemeral port with a temporary data
directory, then cleans up. They use curl, POSIX shell, awk and Python 3's standard library,
and run on macOS and Linux. Each exits non-zero on failure.

| Script | What it checks | Time |
| :--- | :--- | --: |
| `e2e.sh` | **End to end.** Seeded backend, the feed replaying a capture, orders and cancels, a +0.4% loophole, both simulator profiles. Asserted from the API alone: the status shows replayed ticks and scans; the stream delivered BOOK_UPDATE < TRADE < LOOPHOLE_ALERT; final balances equal the start recomputed from the trade list. | ~25 s |
| `logbook_check.sh [N]` | **Every trade reaches the Logbook.** After N orders (default 1,200, more trades than the 500-trade ring holds), `run.sh archive --records` holds exactly the trades the API reported. Its newest 500 records equal `GET /api/trades` byte for byte, and no unknown pair (14 hostile names) reaches the engine. | ~15 s |
| `flood_check.sh [RATE] [S]` | **Scanning never blocks matching.** Order latency (p50, p99, max) with and without 200 ticks/s to `POST /api/market/rates`. Fails if the flood moves p99 by more than 2 ms. | ~75 s |
| `drill_kill9.sh` | **Drill 1.** `kill -9` mid-session, then restart: accounts persist and equal the seed plus the archived trades, the archive decodes, and ids continue and never repeat. | ~20 s |
| `drill_feed.sh` | **Drills 2 and 3.** The feed in live mode against `feed/fake_finnhub.py`. Drill 2: kill the feed (status `disconnected`, scan clears within 30 s), then restart it. Drill 3: kill the socket server (Frankfurter fallback after 15 s), then bring it back (the feed reconnects). `PORT=8080` lets `npm run dev` watch. Needs `feed/.venv`. | ~2 min |
| `fuzz.sh [SEED] [N]` | **Drill 4.** `fuzz.py`: seeded fuzz of every endpoint. Covers random JSON, wrong types, huge numbers, unicode and hostile pair names, deep nesting, oversized bodies, raw protocol abuse, concurrent submit and cancel races, and balances near the 64-bit limit. Fails on any 500, a hang or a dead server. Also checks that cash is conserved and no order is cancelled twice. | ~15 s |
| `drill_sse.sh [RATE] [S]` | **Drill 5.** 30 reading streams, one that never reads and one that reconnects in a loop (`sse_stress.py`), during 300 orders/s. Checks that order p99 stays within 2 ms, the never-reader is dropped and cut off, and a 33rd stream gets 503. | ~80 s |
| `soak.sh [S]` | **Drill 6.** Ten minutes at 300 orders/s and 50 ticks/s, with one stream open. Reports live heap at the start, middle and end, and per minute: p99, threads, file descriptors and heap. Needs `jcmd`. | ~11 min |

`load.py` is the paced load generator the timing scripts share (`orders` and `ticks` modes;
see its `--help`). The dashboard's own end-to-end check is `frontend/e2e/golden-path.mjs`
(see `frontend/README.md`).
