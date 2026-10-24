# Backend bug log

Every bug found while building the backend (and, from Stage 5, the dashboard: entries F1, F2…): what broke, why, and the fix (doc 08 §3 rule 10). Neither benchmark bug below is fixed in `benchmark/`, which is frozen (rule 11). Each is fixed in the production port and covered by a test.

---

## B1: Trie lookups crash on any character outside `A–Z` and `/` (Stage 0)

**What broke.** The benchmark's `TrieTicker` maps a character to its child slot with `c == '/' ? 26 : c - 'A'` and indexes the 27-slot child array without checking the result. Any other character throws instead of answering "not found". Reproduced against the benchmark classes:

```
benchmark TrieTicker.contains("usd/eur") threw java.lang.ArrayIndexOutOfBoundsException: Index 52 out of bounds for length 27
benchmark TrieTicker.contains("USD-EUR") threw java.lang.ArrayIndexOutOfBoundsException: Index -20 out of bounds for length 27
benchmark TrieTicker.contains("US D") threw java.lang.ArrayIndexOutOfBoundsException: Index -33 out of bounds for length 27
```

**Why.** The benchmark contract (`TickerOps`) guarantees that every input uses only the 27-character alphabet, so the lab never needed a check. In production, the directory serves `GET /api/tickers?prefix=…` and validates the pair on order submission (Stage 3), so its input comes from users. A lowercase prefix typed into the dashboard would have become a 500 error.

**Fix.** In `ads.directory.TickerTrie`, `slot()` returns -1 for a character outside the alphabet. `contains` and `prefixSearch` treat -1 as "no match", and `insert` rejects it with `IllegalArgumentException`. `prefixSearch` also stops when the caller's buffer is full rather than writing past it, so an API caller can ask for "the first N matches". Navigation is still one array index per character; parity with the benchmark's checksums is unchanged.

**Tests.** `trie: characters outside A-Z and '/' do not crash lookups (B1)` and `trie: prefixSearch stops when the buffer is full` in `backend/test/ads/test/UnitTests.java`.

---

## B2: The Huffman decoder accepts truncated or corrupt archives (Stage 0)

**What broke.** The benchmark's `HuffmanCompressor.decode` trusts its input. Its bit reader returns 0 bits past the end of the data, and the decoder never checks whether that happened, so a truncated archive decodes into wrong bytes with no error. A corrupted length field makes it allocate the claimed length up front. Reproduced against the benchmark classes on a 57-byte trade log that encodes to 287 bytes:

```
benchmark Huffman decode of 286/287 bytes: returned 57 bytes, identical to input=false, no exception
benchmark Huffman decode of 260/287 bytes: returned 57 bytes, identical to input=false, no exception
benchmark Huffman decode of 100/287 bytes: returned 57 bytes, identical to input=false, no exception
(length field corrupted to ~2.1 billion, -Xmx256m)
threw java.lang.OutOfMemoryError: Java heap space after 12 ms
```

**Why.** The benchmark only ever decodes what it has just encoded in memory. Its gate is `decode(encode(x)) == x`, which this bug cannot fail. The logbook (Stage 1) decodes archive files from disk, where a half-written file after a crash is a realistic input. Silently returning wrong trade records would defeat the reason the archive exists.

**Fix.** In `ads.logbook.HuffmanCodec.decode` (the encoder is untouched, and the parity tests check its output stays byte-identical):

- The 260-byte header must be present.
- Every code length must be at most 62.
- The lengths must satisfy the Kraft inequality, so they form a valid prefix code.
- The recorded length cannot exceed the number of body bits, since every symbol costs at least one bit. This also bounds the output allocation.
- `BitIn` records any read past the end, and a stream that needed such a read is rejected.

Every failure throws `IllegalArgumentException`.

**Tests.** `huffman: truncated streams are rejected, not decoded as padding (B2)` covers seven cut points, including mid-header and one byte short. `huffman: 20000 corrupted streams only ever fail with IllegalArgumentException` is a seeded fuzz of byte flips and truncations: no other exception type, and no out-of-memory error. Corruption inside the body can still decode to other valid symbols. Detecting that needs a checksum, which is a Stage 1 logbook decision, not a codec one.

---

## B3: A failed `backend/build.sh` left stale classes that `run.sh` then executed (Stage 0, own tooling)

**What broke.** While writing Stage 0, a compile error (a `-Werror` lint warning) aborted `javac` after it had already written some class files into `out/classes/`. `run.sh` only checks that `out/classes` exists, so it ran the partial build and failed with `NoClassDefFoundError: ads/vault/LruCache`. That is misleading, because the real problem was the compile error.

**Why.** `javac` writes output incrementally, and the script compiled straight into the directory `run.sh` trusts.

**Fix.** `build.sh` compiles into `out/classes.tmp` and renames it to `out/classes` only after `javac` succeeds. A failed build leaves no `out/classes` behind, so `run.sh` rebuilds and shows the real error.

---

## B4: A below-threshold cycle could hide a real arbitrage (Stage 1, own design)

**What broke.** The first `ArbitrageScanner` ran Bellman-Ford once, on weights `-ln(rate) + ln(1+t)/(V+1)`, and then checked the returned cycle's exact profit against the 0.001% threshold `t`. Bellman-Ford returns *some* negative cycle, not the best one. When the graph held both a cycle earning about 0.0003% and one earning 0.002%, it could return the smaller one. The scan then reported "below threshold", and the real arbitrage was never reported. The brute-force scanner test caught it on its 62nd random graph:

```
FAIL  3000 random rate sets vs brute-force cycle enumeration  (26 ms)  trial 61: missed a 0.002044041780213668% cycle
(diagnosis on that graph: arbitrage=false belowThreshold=true)
```

**Why.** The weight shift was chosen so that every cycle at or above the threshold stays negative, which is true. But it also let cycles earning as little as 2/(V+1) of the threshold stay negative, and any of those could be the one returned. Finding the most profitable simple cycle exactly is NP-hard in general, since it contains the longest-path problem, so Bellman-Ford alone can't promise to return the best cycle.

**Fix.** The scan now runs passes L = 2, 3, …, V+1, the L-th on weights raised by `ln(1+t)/L`, and reports the most profitable returned cycle that passes the exact check.
- The L = 2 pass can't return a sub-threshold cycle, because every cycle has at least 2 edges. So nothing can mask it, and any cycle of k edges earning more than `(1+t)^(k/2) − 1` is always reported.
- The later passes reach closer to the threshold.

The cost is V+1 Bellman-Ford runs, about 17,000 edge relaxations on the 12-currency graph. The remaining theoretical gap, a cycle between `t` and the guaranteed bound that Bellman-Ford never returns, is documented in the class comment. Across the 3,000 random graphs the test now reports 2,102 of 2,102 graphs with a cycle at or above 0.001%, with none in the gap.

**Tests.** `3000 random rate sets vs brute-force cycle enumeration (exact check + guarantee)` in `backend/test/ads/test/ServiceTests.java`:
- Every reported cycle is real.
- Its multiplier is the product of its rates.
- Its profit is at or above the threshold and no more than the best cycle's.
- A scan must never stay silent when the guarantee applies.

---

## B5: The Frankfurter fallback could never activate: its CDN rejects Python's default User-Agent (Stage 2)

**What broke.** In the first live outage drill, run by `feed/live_check.sh` with the socket pointed at a dead local port, the feed reached the 15 s trigger and then got a 403:

```
WARNING Frankfurter fallback unavailable (HTTPError: HTTP Error 403: Forbidden); keeping last rates
```

The same URL returned 200 from `curl`. Checking by User-Agent showed the cause:

```
Python-urllib/3.13 -> 403
curl/8.5.0 -> 200
ads-feed/1.0 (+https://github.com/lifeknife10A/order-matching-arbitrage-detector) -> 200
```

**Why.** `urllib` sends `Python-urllib/x.y` unless told otherwise, and Frankfurter's CDN blocks that agent. The unit tests could not see it because they mock the fetch. Only a request to the real service exposes it.

**Fix.** Every request the feed makes (Frankfurter, Finnhub discovery and the backend POST) now sends `User-Agent: ads-feed/1.0 (+repo URL)`. A unit test pins that the fallback fetch never uses the default agent. The rerun drill switched to `frankfurter-fallback` at 15.0 s and back to `finnhub-ws` on reconnect.

---

## B6: A deeply nested message would have crashed the feed (Stage 2, caught before commit)

**What broke.** `parse_message` caught `ValueError` and `TypeError` from `json.loads`. On `"[" * 100000`, Python's `json` raises `RecursionError` instead, which would have escaped the listener loop and killed the process. That is exactly the "never crash on bad input" rule.

**Fix.** `RecursionError` is caught alongside the others, and the message is counted as malformed and skipped. The input is now in the malformed-message test, which also covers non-JSON, wrong types, NaN/Infinity, negative or zero prices, non-integer timestamps, empty symbols and binary junk.

---

## B7: A client hanging up mid-body was answered as a 500 with a stack trace (Stage 2, caught before commit)

**What broke.** The server garbage test sends a `Content-Length: 500` header with only 4 bytes of body. Reading the body threw `IOException`, which fell through to the generic handler: a 500 `INTERNAL` response and a full WARNING stack trace in the log.

**Fix.** A body that ends early is now a client error, `400 INCOMPLETE_BODY`, with no trace. The server keeps serving, and the garbage test checks that it still answers afterwards.

---

## Note (not a bug): real OANDA ticks show "arbitrage" above 0.001% almost all the time. Resolved by a data-driven threshold (Stage 2 addendum)

The first live capture, replayed through the backend, reports cycles such as `CHF -> USD -> CAD -> CHF` at +0.0056%. The ticks alternate between bid and ask, and each leg arrives at a slightly different moment, so these cycles exist in the numbers but could not be traded. `backend/run.sh threshold-study` replays the committed capture offline, in recorded time, with the server's rules: each quote usable for 30 s, scans throttled to 500 ms.

```
capture: ../feed/samples/finnhub_ticks_2026-10-23.jsonl (3690 ticks over 299 s, 432 throttled scans)
best-cycle profit per scan (exhaustive): p50 0.01070%  p90 0.01314%  p99 0.01562%  max 0.01652%
threshold   scanner reports a cycle      exhaustive: some cycle >= threshold
  0.001%     429 / 432  ( 99.31%)        429 / 432  ( 99.31%)
  0.005%     427 / 432  ( 98.84%)        427 / 432  ( 98.84%)
  0.010%      58 / 432  ( 13.43%)        286 / 432  ( 66.20%)
  0.020%       0 / 432  (  0.00%)          0 / 432  (  0.00%)
  0.050%       0 / 432  (  0.00%)          0 / 432  (  0.00%)
recommended default: 0.020% (smallest threshold with fewer than 1% of scans reporting a cycle)
```

The default is now 0.02% (`ArbitrageScanner.DEFAULT_MIN_PROFIT_PERCENT`). It can be overridden with `serve --min-profit PERCENT` or `MIN_PROFIT_PERCENT`. The demo's injected +0.4% is still detected. The table is also in doc 06 §5.

---

## B8: Near the threshold, the scanner misses most real cycles. ACCEPTED as a documented limitation (doc 08 Stage 4 addendum): triangles exact, longer loops best-effort

**What the threshold study showed.** At a 0.010% threshold, an exhaustive search over every simple cycle finds a cycle of at least 0.010% in 286 of 432 scans of the real capture. The scanner reports one in only 58. This is the gap B4 documented, and on real data it is large. Live ticks produce many cycles of almost the same profit, about 0.01%. On a shifted pass, Bellman-Ford returns *some* negative cycle, usually one just under the threshold, so no shifted pass returns the qualifying one.

**Stage 3 addendum: exact triangles.** Every scan now also checks every directed triangle exactly (440 at V = 12, 2,280 at V = 20) and reports the more profitable of that and the Bellman-Ford result. A test of 5,000 graphs with bid/ask-sized noise at the threshold found a qualifying triangle in 4,886 of them, and the scanner reported every one. The brute-force test also now requires that no triangle is ever missed.

**What it did not close.** Re-running `threshold-study` with a "triangles only" column:

```
threshold   scanner reports a cycle      exhaustive: some cycle >= t      triangles only: some triangle >= t
  0.010%      58 / 432  ( 13.43%)        286 / 432  ( 66.20%)          4 / 432  (  0.93%)
legs of the best cycle in scans where it reaches 0.010%: 5 legs=4 6 legs=38 7 legs=244
```

On real ticks the near-threshold cycles are almost never triangles. Bid/ask noise accumulates along long loops, usually through all 7 live currencies. So the scanner column did not move (58 at 0.010%), and the gap for cycles of 4 or more legs remains. It still does not affect the 0.02% default, where both the scanner and the exhaustive search report 0 of 432. **Decision (doc 08 Stage 4 addendum, item 4): accepted as a documented limitation.** The project defines triangular arbitrage, and triangles are now exact: no triangle at or above the threshold is ever missed, and a test pins that. Loops of 4 or more legs are best-effort. The Bellman-Ford passes report one when they find it, and may miss others. The 5- to 7-leg loops that go unreported near 0.01% are bid/ask noise accumulating over many legs, not tradeable opportunities, and at the 0.02% default the real capture shows none. The exhaustive search proposed here earlier was not built, by that decision.

---

## B9: Every HTTP response waited about 40 ms (Stage 3)

**What broke.** The first run of the gateway tests took 133 s to place 3,000 orders one after another, about 44 ms each. 200 concurrent orders took 10 s.

**Why.** `com.sun.net.httpserver` writes the response headers and body as separate small writes on a socket without `TCP_NODELAY`. Nagle's algorithm holds the second write back until the client acknowledges the first, and the client delays that acknowledgement by up to 40 ms. Stage 2 never noticed because the feed posts in 250 ms batches.

**Fix.** `ApiServer` sets `sun.net.httpserver.nodelay=true` before the server classes load, unless the property is already set. The same 3,000 orders now take 2.9 s, and the 200 concurrent ones 0.3 s. curl measures 2.5–2.9 ms per order on a warm server.

---

## B10: Pretty-printing the contract examples silently rewrote prices (Stage 3, own tooling, caught before commit)

**What broke.** The first version of `backend/scripts/contract_examples.sh` formatted responses with `python3 -m json.tool`. That parses numbers as binary floats and writes them back, so the server's exact `"price": 1.08520` would have appeared in the contract as `1.0852`. A frontend developer reading the contract would then conclude that the API drops tick decimals.

**Fix.** The script re-indents JSON with a small `awk` program that copies every value character for character and never parses it. `awk` is also present on macOS without Python.

---

## B11: On the simulator's shallow workload the engine is no faster than the naive baseline (Stage 4, measured; deep profile added at the Stage 4 addendum)

**What was measured.** `POST /api/simulate` runs the doc 08 Stage 4 mix: a narrow ±10-tick band, 20% cancels and constant crossing. On that mix the books stay shallow: 46 orders rest across six pairs after 5,000 operations, and 57 after 1,000,000. At that depth the naive matcher scans about ten contiguous array slots per pair, which is as fast as the B+ tree, the order index and the per-order allocations. The engine allocates an `OrderResult`, a `Trade[]`, a `Trade` per fill and an `Order` when one rests, and it reads the account through the Vault's LRU cache. Measured `speedup` in the development container:

| Run | engine orders/s | baseline orders/s | speedup |
| :--- | --: | --: | --: |
| 5,000, fresh server | 1,021,552 | 1,326,670 | 0.77 |
| 1,000,000, fresh server | 3,670,029 | 3,972,357 | 0.92 |
| 1,000,000, same server, runs 1 / 2 / 3 | 3.88M / 4.37M / 4.51M | 3.42M / 4.32M / 4.49M | 1.14 / 1.01 / 1.00 |

The engine's tail is also longer: submit max 3.1 ms against 0.11 ms at 1,000,000. Those are collection pauses: `-Xlog:gc` on a 1,000,000 run shows 1–4 ms young-generation pauses, which match the engine's 2–3.7 ms maximums. The baseline allocates nothing per order. Its p99 was 1.6 µs against 0.8 µs from a fresh server, and level with the baseline when warm. Every run matched checksums.

**Not changed.** The workload was not changed to make the baseline lose: doc 08 fixes the mix, and the rule is not to tune for a target. The doc 04 throughput target (≥ 50,000 orders/s) is met many times over by both. The structures win when the book is deep (the Stage 0 benchmark and doc 04 Phase 5 measure that), and this mix never builds a deep book. **Stage 4 addendum.** Option (b) was chosen: an optional `profile: "deep"` (contract §3.12), with the shallow default unchanged (seed 42 still gives `cf084b63dbf339ae`). Each size was measured once with `simulate.sh` on a fresh server, so JIT warm-up is included. The deep mix was written from the spec and not tuned afterwards:

| profile | numOrders | engine orders/s | baseline orders/s | speedup | resting at end |
| :--- | --: | --: | --: | --: | --: |
| shallow | 5,000 | 877,183 | 2,232,083 | 0.39 | 46 |
| shallow | 50,000 | 844,969 | 2,208,646 | 0.38 | 26 |
| shallow | 200,000 | 1,401,721 | 2,797,680 | 0.50 | 26 |
| shallow | 1,000,000 | 3,825,039 | 3,568,278 | 1.07 | 57 |
| deep | 5,000 | 505,736 | 665,219 | 0.76 | 2,881 |
| deep | 50,000 | 798,697 | 46,095 | 17.33 | 28,382 |
| deep | 200,000 | 1,147,432 | 10,902 | 105.24 | 113,390 |

With shallow books the baseline wins or ties; with deep books the engine stays near a million operations a second while the linear scan falls off with depth. Every run matched trades and every trader's final cash.

---

## B12: A crash before the first counter flush reissued ids; the old crash test could not fail (Stage 4 addendum, found by the new test)

**What broke.** The Stage 3 addendum wrote `counters.json` (counters plus the 1,000,000 reserve) only on the periodic flush, every 2 s, and only once something had changed. A fresh data directory had no file until the first flush. A server that crashed in those first 2 s restarted at `ORD-1` and reissued ids that were already in the Logbook archive. The reserve was also renewed only on that timer, so in principle more than 1,000,000 ids between two flushes could outrun it. The Stage 3 crash test waited for a periodic flush before copying the directory, so no id was ever issued after the last write. A mutation that removed the reserve passed all 190 tests.

**Fix.** The App writes the reserved counters at startup, before the first command. After every command it writes a fresh reserve at once if any counter has used half of the reserve on disk. The flush interval and the reserve are now `App.Config` parameters, so a test can disable the timer. The new test (`crash with no flush since the ids were issued…`) sets the flush interval to an hour, issues 40 orders and copies the data directory mid-run, as the crash. It then restarts on the copy and requires the next order id, trade id and `seq` to exceed every earlier one. It runs twice: once with the default reserve, and once with a reserve of 8 that has to be renewed several times during the run. **Proof it can fail:** with the reserve removed (`long add = 0`) the test fails with `an id repeated after the crash: ORD-1 seq 1, trade 1 (max before: ORD-40, trade 20, seq 40)`. The same happens with the Stage 4 behaviour restored (no startup write).

**Also from the addendum.** A baseline that settled at the taker's limit price instead of the maker's passed every Stage 4 test, because only trades and counts were compared. The comparison now includes every simulation trader's final cash (`cashMatch`, part of `checksumMatch`). With that mutation, 3 tests fail. The first is `engine and naive baseline agree…`, which fails on `shallow seed 1 n 1000: finalCash […] vs […]`.

---

## B13: After a crash, balances could include trades the archive did not have; the Logbook's loss window was unbounded in time (Stage 6, drill 1)

**What broke.** Drill 1 (`scripts/drill_kill9.sh`) traded, waited, traded again and ran `kill -9` 1 s later. After the restart the accounts held the last burst's balances, but the archive lacked its 10 trades. The cause: the Vault flushed every 2 s, while the Logbook wrote a segment only every 500 trades. So a balance on disk could include trades no archive would ever show, and a quiet session could lose all its trades, however old. The Stage 6 spec asked for the time bound. The inconsistency is the bug the drill found.

**Fix.** Every Vault flush now writes the buffered trades as a segment first, so a balance on disk never includes a trade the archive lacks. A separate timer also writes any non-empty buffer at least every 5 s (`App.LOGBOOK_FLUSH_MS`, `Config.logbookFlushMs`). The crash-loss window is now the last 2 s of trades together with their balances, and 5 s at most for trades. **Tests:**

- `Logbook loss window bounded by time…` snapshots the data directory 700 ms after 12 trades. With a 300 ms timer all 12 are archived; with no timer, none are.
- `after a crash, persisted balances never include a trade the archive lacks (B13)` rebuilds every balance from the snapshot's archive.

**Proof:** with the timer removed the first test fails (`0 of 12 trades`). With the Vault-flush ordering reverted the second fails (`10 accounts on disk disagree with start balances + archived trades`). **Stage 6 addendum: a deterministic test.** Reordering `flushVault` (Vault before Logbook) passed all 199 tests, because after the 2 s Logbook timer the unsafe window is microseconds long. The new test `B13 fault injection` replaces the Logbook directory with a plain file, so every segment write fails, even as root. It trades and lets six periodic flushes and the shutdown run, then asserts the accounts file is byte-for-byte unchanged. The reordering mutation now fails it (`the accounts file was written although its trades could not be archived`). The test also found the same ordering flaw in `App.stop()`, which wrote the Vault before the Logbook. That is fixed, and the old shutdown order fails the test too (`shutdown wrote the accounts file…`). The drill now checks that every balance after a restart equals its seed balance plus the archived trades.

---

## B14: A balance near the 64-bit limit made a fill throw: a 500, and money destroyed (Stage 6, drill 4)

**What broke.** `POST /api/accounts` accepts any non-negative `cash` (contract §3.9), so an account can hold up to 9.2 × 10¹⁸. When such an account was the seller in a fill, `Vault.settle` debited the buyer, then hit `Math.addExact` overflow crediting the seller. The order got a 500, and the buyer's 1,085,000 vanished: the seller was never credited, no trade was recorded, and the resting order stayed. The fuzzer created such accounts but never traded them; a targeted probe found it.

**Fix (engine only; the contract is unchanged):**
1. `Vault.settle` computes both new balances before writing either, so it is atomic.
2. A SELL whose proceeds (at most quantity × 10⁹ ticks) could overflow the seller's cash is rejected `MARGIN`. A BUY cannot underflow, because its cash covers the exposure when it is checked.
3. A resting order whose account cannot absorb a fill without overflow is withdrawn from the book, and matching continues behind it. A MARKET order that met only such orders is `REJECTED NO_LIQUIDITY`.

Three engine tests cover these. With the withdrawal disabled, the first fails with `ArithmeticException: long overflow`. The fuzzer now has a phase where six accounts near the limit trade 600 orders as makers and takers; cash is conserved. **Stage 6 addendum: the root cause is closed.** With the supervisor's approval the contract was narrowed: `POST /api/accounts` refuses `cash` or `marginLimit` above 10¹⁵ and a 1,001st account with 400 `BAD_REQUEST`. The accounts-file loader refuses a file outside the bounds (margin above 10¹⁵, a balance outside ±10¹⁸, total above 10¹⁸, more than 1,000 accounts). Total cash therefore stays at or below 10¹⁸ and no balance can approach overflow. The defensive engine code and its three tests stay as belt and braces. They call the engine and the Vault directly, below the API. The fuzzer's balance phase now checks that three over-bound accounts are refused, then trades accounts opened at exactly 10¹⁵.

---

## B15: An over-size upload often got "connection reset" instead of its 413 (Stage 6, drill 4)

**What broke.** The fuzzer intermittently reported a request as dead: a 3 MB `POST`, retried on a fresh connection and failing again. The server stopped reading at 1 MB and answered 413. The JDK's `HttpExchange.close()` then drains only 64 KB and closes a socket that still has unread data, which resets the connection. A client still uploading (Python's http.client, curl, many SDKs) gets a write error and never reads the 413.

**Fix.** After deciding on 413, `readBody` reads and discards the rest of the body, up to 16 MB (`MAX_DRAIN_BYTES`), before answering. Beyond that the connection is still cut, so an endless body cannot hold a thread. **Test:** `B15: a 3 MB body uploaded in pieces always gets its 413` uploads in 64 KB pieces, as a client does. Before the fix: `0 of 30 uploads got the 413 … Connection reset by peer`. After it: 30 of 30. The fuzzer passed 6 runs of the seed that failed 2 times in 6 before.

---

## B16: An event-stream client that stopped reading held a thread and a socket forever (Stage 6, drill 5)

**What broke.** Drill 5's never-reading client was dropped by the hub as designed (`sseDropped` rose), but its connection never ended. The JDK HTTP server has no write timeout. The client's stream thread stayed blocked in a socket write, and the reaper's `exchange.close()` blocked on the same socket. The hub had removed the client from its count, so a client could open streams, stop reading and repeat, piling up stuck threads and sockets without limit. Peers that vanish without closing are only released by TCP's retransmission timeout; a live peer advertising a zero window never is.

**Fix.** On a drop, the reaper closes the connection's socket channel directly, so the blocked write fails at once and the thread returns. The JDK offers no public way to do this. The hub reads the exchange's internal channel by reflection, which the JVM must allow with `--add-opens jdk.httpserver/sun.net.httpserver=ALL-UNNAMED`; `run.sh` and `test.sh` pass it. Without the flag the hub falls back to `close()` and logs one warning. **Test:** `B16: a dropped client that never reads is cut off at once` runs the hub on a real HTTP server, and a client that never reads gets 100 KB events until it is dropped. Before the fix the stream thread was still blocked 3 s later. After it, the thread is freed 4 ms after the drop. In the drill the client now sees the connection end.

Note: the drop happens only once the kernel's socket buffers are full. On loopback that is about 4 MB, 23 s of events at 300 orders/s. The 256-event queue is the last buffer, not the first.

---

## B17: A dead feed's loophole cleared up to 1 s after its quotes expired, past the 30 s drill limit (Stage 6, drill 2)

**What broke.** Drill 2 (`scripts/drill_feed.sh`) kills the feed and times the status and the scan at 0.25 s resolution. The status said `disconnected` at 15.0 s. The scan, though, still reported the loophole until **30.52 s** after the kill, failing "within 30 s". At 1 s polling this had looked like a pass at "30–31 s", which hid it. Live quotes are valid for 30 s (contract §3.7), but the re-scan after an expiry came from a sweep that runs once a second. It could therefore fire up to 1 s after the last quote stopped being usable.

**Fix.** After every scan, the market service arms a one-shot timer for the exact instant the next quote expires (`ArbitrageScanner.nextExpiryAfter`) and re-scans then. A timer superseded by a newer quote does nothing. The 1 s sweep stays as a backstop. **Test:** `drill 2: a dead feed's loophole clears at the expiry instant (30 s + 1 ms), not a sweep later` checks that the cycle is still reported at exactly 30 s, gone 1 ms later, and that a refreshed quote moves the timer. With the timer disabled it fails (`not cleared 1 ms after expiry`). Re-run of the drill: the scan cleared at 29.82 s and 29.88 s after the kill (the last tick arrives just before it).

---

## F1: The pair picker could choose the previous query's pair (Stage 5, frontend, found in a headless-browser run)

**What broke.** The picker kept the last suggestions on screen while the next query's 150 ms debounce ran. Typing `eur/usd` and pressing Enter at once chose the first suggestion of the previous query, `USD/JPY`. A headless Chromium run of the golden path caught it: the demo then seeded the wrong pair.

**Fix.** Suggestions are stored with the prefix they answer. They are shown, and can be chosen, only while that prefix matches the typed text. Enter on a complete listed pair (`gbp/usd`, `EUR-USD`) chooses it at once, without waiting for suggestions. `src/lib/picker.js` holds the rule and `forms.test.js` tests it. Re-run in the browser: a fast "type, Enter" on `gbp/usd` selects GBP/USD, and on the partial `aud/n` nothing changes.

---

## F2: Through the dev proxy, the event stream never noticed the backend going away (Stage 5, frontend tooling)

**What broke.** With the backend killed (SIGTERM or `kill -9`), a stream fetched directly from `:8080` ended within a second. The same stream through the Vite proxy on `:5173` stayed open indefinitely: the proxy did not pass the upstream close on to the browser. `EventSource` never fired `error`, so the top bar still said "stream open" during an outage, and nothing reconnected on its own.

**Fix.** `vite.config.js` destroys the browser-side response when the upstream stream closes. `EventSource` then reconnects, gets the proxy's 502 while the backend is down, and the client replaces the closed source every 3 s until the backend is back. Measured through the proxy: the stream ends 1.01 s after SIGTERM and 1.01 s after `kill -9`. In the headless run the top bar showed "stream closed", and after the restart it showed "open" again, with every snapshot refetched. This affects only `npm run dev`. Stage 7 serves the built dashboard from the Java server, without the proxy.

---

## Note (not a bug): Appendix A lets cash go negative. Accepted in doc 08 at 6462f32

Implemented as written, Appendix A checks margin per order against the account as it is at that moment, and nothing is reserved for resting orders. A market order is checked only at the best opposite tick, even if it then sweeps deeper. So a trader with cash 1,000 can rest two buys of 10 × 100. Each passes on its own, both fill, and cash ends at −1,000. The engine does not improvise a reservation. The Vault accepts negative cash when loading (it rejects only a negative `marginLimit`), and the scenario test `Appendix A reserves no cash…` pins the behaviour down. Doc 08 Appendix A now records this as a known simplification.

---

## Porting note (not a bug): cleared slots in the B+ tree leaves

The benchmark ladder stores volumes in a primitive `long[]`, so stale slots past `n` are harmless. The production leaves hold `PriceLevel` references, and a stale slot would keep a removed level, and through its queue that level's orders, reachable. That would be a slow memory leak in a long-running engine. Every place the port shifts or moves leaf entries out of a slot clears that slot: leaf delete, leaf split, and both borrow paths. Merges drop the whole right node. This changes no observable behaviour, and the parity gates cannot see it either way.
