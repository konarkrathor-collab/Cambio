# Backend bug log

Every bug found while building the backend: what broke, why, and the fix (doc 08 §3 rule 10). Neither benchmark bug below is fixed in `benchmark/`, which is frozen (rule 11). Each is fixed in the production port and covered by a test.

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

## B8: Near the threshold, the scanner misses most real cycles (Stage 2 addendum, measured; triangles closed at the Stage 3 addendum, longer cycles still open)

**What the threshold study showed.** At a 0.010% threshold, an exhaustive search over every simple cycle finds a cycle of at least 0.010% in 286 of 432 scans of the real capture. The scanner reports one in only 58. This is the gap B4 documented, and on real data it is large. Live ticks produce many cycles of almost the same profit, about 0.01%. On a shifted pass, Bellman-Ford returns *some* negative cycle, usually one just under the threshold, so no shifted pass returns the qualifying one.

**Stage 3 addendum: exact triangles.** Every scan now also checks every directed triangle exactly (440 at V = 12, 2,280 at V = 20) and reports the more profitable of that and the Bellman-Ford result. A test of 5,000 graphs with bid/ask-sized noise at the threshold found a qualifying triangle in 4,886 of them, and the scanner reported every one. The brute-force test also now requires that no triangle is ever missed.

**What it did not close.** Re-running `threshold-study` with a "triangles only" column:

```
threshold   scanner reports a cycle      exhaustive: some cycle >= t      triangles only: some triangle >= t
  0.010%      58 / 432  ( 13.43%)        286 / 432  ( 66.20%)          4 / 432  (  0.93%)
legs of the best cycle in scans where it reaches 0.010%: 5 legs=4 6 legs=38 7 legs=244
```

On real ticks the near-threshold cycles are almost never triangles. Bid/ask noise accumulates along long loops, usually through all 7 live currencies. So the scanner column did not move (58 at 0.010%), and the gap for cycles of 4 or more legs remains. It still does not affect the 0.02% default, where both the scanner and the exhaustive search report 0 of 432. **Possible fix, for the supervisor to decide:** run an exhaustive simple-cycle search whenever at most 8 currencies are active. That is about 2,400 cycles for the 7-currency live graph and at most about 17,000 for 8, so it costs microseconds. Otherwise keep triangles plus Bellman-Ford, which covers the 12-currency fallback graph. That graph is consistent through USD anyway, so its base pass exits early.

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

## Note (not a bug): Appendix A lets cash go negative. Accepted in doc 08 at 6462f32

Implemented as written, Appendix A checks margin per order against the account as it is at that moment, and nothing is reserved for resting orders. A market order is checked only at the best opposite tick, even if it then sweeps deeper. So a trader with cash 1,000 can rest two buys of 10 × 100. Each passes on its own, both fill, and cash ends at −1,000. The engine does not improvise a reservation. The Vault accepts negative cash when loading (it rejects only a negative `marginLimit`), and the scenario test `Appendix A reserves no cash…` pins the behaviour down. Doc 08 Appendix A now records this as a known simplification.

---

## Porting note (not a bug): cleared slots in the B+ tree leaves

The benchmark ladder stores volumes in a primitive `long[]`, so stale slots past `n` are harmless. The production leaves hold `PriceLevel` references, and a stale slot would keep a removed level, and through its queue that level's orders, reachable. That would be a slow memory leak in a long-running engine. Every place the port shifts or moves leaf entries out of a slot clears that slot: leaf delete, leaf split, and both borrow paths. Merges drop the whole right node. This changes no observable behaviour, and the parity gates cannot see it either way.
