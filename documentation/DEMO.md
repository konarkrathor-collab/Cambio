# Demo Script (10–12 minutes)

A step-by-step live demonstration of v1.0.0. Each step says **what to click**, **what the audience should see** and **what to say**. Every number in "say" comes from a committed result in [results/](./results/README.md); the file is named next to it. Numbers the dashboard shows live will differ a little from machine to machine. Quote the committed ones, and point at the live ones as "this run".

---

## Before the audience arrives (not timed)

1. In a terminal at the repository root, make sure no Finnhub key is set for this shell, then start everything:
   ```sh
   unset FINNHUB_API_KEY
   ./start-all.sh
   ```
   Wait for the line `Dashboard and API:  http://127.0.0.1:8080`.
   - With no key, `start-all.sh` replays the committed real capture, so the demo does not depend on the forex market being open or on the network.
   - The DEMO drawer's **Inject loophole** button is disabled while the badge says LIVE, so a demo injection never mixes with live data (backend/BUGS.md F3).
2. Open **http://127.0.0.1:8080** in a browser window at least **1280 px wide**. 1024 × 768 also works, with two columns.
3. Leave the terminal visible on a second screen if you can. It is part of step 8.

To rehearse, `node frontend/e2e/demo-script.mjs` clicks through steps 1–7 in a headless browser against `start-all.sh` and checks each "audience sees" item. It needs Playwright; see [frontend/README.md](../frontend/README.md).

If something goes wrong mid-demo, `Ctrl-C` in the terminal and `./start-all.sh` again. The second start skips the builds.

---

## 1. What this is, and the honesty badge (1 min)

**Click:** nothing yet. Point at the top bar.

**Audience sees:**
- The title **Order Matching & Arbitrage Detector**.
- An amber **REPLAY** badge.
- **backend ok**, **stream open**, and an engine **seq** number.
- Four tabs: **Market**, **Arbitrage**, **Simulator**, **Inspector**.
- The **DEMO** drawer at the bottom.

**Say:**
> "This is an exchange's matching engine and a currency-loophole scanner. Every data structure on the hot path is written by hand in Java, with no collection library. A Python process feeds it real forex rates, and this React dashboard is served by the same Java process on one port.
>
> First, an honesty point. That badge says **REPLAY**, not LIVE. Right now we are replaying ticks we recorded from the real OANDA feed on 23 October. The forex market is closed at weekends, and a classroom network may block the feed, so we never pretend recorded data is live. With a Finnhub key it says LIVE. If the market is closed it says WAITING, and anything we inject is labelled REPLAY."

---

## 2. The order book: B+ tree + linked queues (2 min)

**Click:**
1. The **Market** tab (already open). The book title reads **ORDER BOOK · EUR/USD** (panel titles are shown in capitals).
2. Open the **DEMO** drawer at the bottom ("DEMO tools: seed order flow, inject a loophole") and click **Seed flow on EUR/USD**.
3. In the **Order ticket**:
   - Trader **T1**, **BUY**, **LIMIT**.
   - **Price**: type the lowest **ASK** price in the ladder.
   - **Quantity** `5`.
   - Click **BUY LIMIT**.
4. In the ticket again:
   - **BUY**, **LIMIT**.
   - **Price** `1.08000` (below every bid).
   - **Quantity** `10`.
   - Click **BUY LIMIT**.
5. In **My resting orders**, click **Cancel** next to that order.

**Audience sees:**
- After the seed:
  - The drawer log says "Seed flow: 20 limit orders on EUR/USD …" with the trades it made.
  - The ladder fills: asks above, bids below, with the spread row between. Each row shows price, volume, number of orders, cumulative volume and a depth bar.
  - The depth chart draws two step areas.
- After the first order:
  - The ticket shows **FILLED** (or **PARTIAL**) with a fills table.
  - **Trades · EUR/USD** gets new rows at the top.
  - The **Account** panel's cash for T1 drops by price-tick × quantity.
- After the second: **QUEUED**, and the order appears in **My resting orders**.
- After the cancel: "cancelled: 10 removed", and it leaves the book.

**Say:**
> "Each side of each pair's book is a **B+ tree** of price levels, order 32, with linked leaves.
> - Finding the best price is one walk down the tree.
> - Drawing ten levels of depth is one walk down and then a scan along the leaf chain.
> - Inside a price level, orders wait in a **doubly linked list**, oldest first: that is price-time priority.
> - A cancel finds the order through **one hash index** for the whole engine. The order knows its level, and unlinking it is O(1). We never scan a queue.
>
> Fills happen at the resting order's price, and the trade moves cash between the two traders in *simulation units*: price tick times quantity. More on that limitation later."

---

## 3. Inside the structures: the Inspector (1 min)

**Click:** the **Inspector** tab.

**Audience sees:**
- Two panels, **ASKS (SELL SIDE) · EUR/USD** and **BIDS (BUY SIDE) · EUR/USD**. Each lists price levels best first, with the order count and the **Queue head** (trader, remaining).
- A structure line such as **B+ tree · order 32 · height 1 · 0 internal · 1 leaves · N entries**, drawn as root and leaves, with the note "leaves are linked left to right; each entry is one price level with its FIFO queue".

**Say:**
> "This is the live structure, not a picture of it. With a few dozen levels the whole ladder fits in one leaf of 32 entries.
>
> Why a B+ tree? We planned a radix tree. Then we benchmarked twelve ladder designs, with Java's own TreeMap as the reference, and the B+ tree was the fastest balanced one:
> - 24.50 ms against red-black's 29.00 ms on a 300,000-operation workload;
> - balanced under sorted insertion, 0.78 ms where a plain binary tree takes 39.87;
> - the leanest in memory, 28.9 bytes per price level against 48.0 for red-black and 58.6 for the radix tree.
>
> So we replaced our own first pick with the measured winner (results/benchmark_run15.txt)."

---

## 4. The trie: pair lookup (0.5 min)

**Click:** back to **Market**. In the **PAIR** box in the top bar, type `usd/j`. Choose **USD/JPY** with the arrow keys and Enter. Then type `eur/usd` and press Enter to go back.

**Audience sees:** suggestions appear as you type, each with its tick size (**USD/JPY tick 0.001**). The book switches to USD/JPY, which is empty because it is a separate book, then back.

**Say:**
> "Pair lookup is a **trie** over 132 currency pairs. Validating a pair is one array step per character, and autocomplete walks only the children that exist.
>
> In our benchmark a hash set is faster at exact lookups: 6.00 ms against the trie's 8.36. But a hash set cannot do prefix search at all, and on the prefix workload the trie wins, 28.84 ms against 36.73 for a sorted array (results/benchmark_run15.txt). The trie also gives every pair a small id, so the engine keeps its books in a plain array."

---

## 5. Currency loopholes: Bellman-Ford, on real data and on an injected loophole (2.5 min)

**Click:**
1. The **Arbitrage** tab. Wait a few seconds.
2. In the DEMO drawer, click **Inject loophole (replay)**.
3. Keep talking for about 30 seconds and watch the **Loophole alerts** panel.

**Audience sees:**
1. On the replayed real data:
   - The **Currency graph** shows 12 currencies. The 7 the feed streams are bright; INR, CNY, HKD, SGD and NZD are dimmed, because they have no rate.
   - The **Loophole** panel says: *"No loophole above 0.02%. Real markets close these within milliseconds, so this is the expected result."*
   - The **Scanner** panel shows the threshold, the last scan time, the scan count and the source.
2. After the injection:
   - The drawer log says it injected a +0.4% USD/SGD 1.3, USD/HKD 7.8, SGD/HKD 6.024 tick set as REPLAY data.
   - A cycle through SGD and HKD glows and pulses on the graph. It is usually the three-leg **USD → SGD → HKD → USD**. Sometimes it is a four-leg variant that mixes in a replayed quote and earns a few hundredths of a percent more. The scanner reports the best cycle, so either is correct.
   - The Loophole panel shows the legs, the multiplier and a profit of about **+0.40%**.
   - The badge still says **REPLAY**, and a **FOUND** alert appears.
   - The graph does not move: the layout is fixed once it settles.
3. About 30 seconds later a **CLEARED** alert appears and the message returns. The injected quotes have expired.

**Say:**
> "Each rate becomes an edge with weight minus log of the rate. A loop whose rates multiply to more than 1 then has a negative total weight, so finding a loophole means finding a **negative cycle**. **Bellman-Ford** does exactly that, and it does the same amount of work whatever order the rates arrive in.
>
> On real data there is no loophole above 0.02%. We chose that threshold from data: replaying the real capture, the best cycle in any scan reached only 0.01652% (results/threshold_study.txt). Smaller 'loopholes' are just the gap between bid and ask prices.
>
> The injected one is labelled REPLAY. We use pairs the live feed never streams, so real ticks cannot overwrite it. Quotes are valid for 30 seconds, so it clears by itself: a feed that dies cannot leave a stale alert on screen.
>
> Speed:
> - At the 7 currencies we stream, a scan takes at most 4.30 µs; at all 12, at most 24.98 µs.
> - Our original target was under 100 µs at 20 currencies. That is met without a cycle, 11.61 µs, but **missed** with a cycle present, 160.33 µs. The scanner then runs extra passes so a small cycle cannot hide a bigger one (results/scan_timing.txt).
>
> We report that miss rather than move the target."

---

## 6. Deep versus shallow books: why the structures matter (2.5 min)

**Click:**
1. The **Simulator** tab.
2. Click the **5,000** preset, leave **Profile** on **shallow (default mix)** and **Naive baseline** ticked, and click **Run simulation**.
3. Then set **Profile** to **deep (books grow deep)**, click **50,000**, and click **Run simulation**. It takes about a second.

**Audience sees:**
1. The shallow run:
   - The **ENGINE VS NAIVE BASELINE** panel: orders per second, submit and cancel p50/p99, trades, resting orders.
   - **checksumMatch ✓ match**, and a **Speedup** around 1× or below. The committed run measured 0.47×.
2. The deep run:
   - **checksumMatch ✓ match** again.
   - A **Speedup** well above 1. The committed run measured 17.24×, with 28,382 orders resting.
3. Below it, **Doc 04 targets** lists each target with its measured value, marked met or missed. Throughput is met. The cancel and scan rows can show a miss at p99 on a given run; they are shown, never hidden.

**Say:**
> "The simulator runs a seeded order stream inside the server, on a separate engine, and the same stream through a **naive matcher** that just scans an array for the best price. Same trades and same final balances, checked every time: that is the checksum.
>
> On a shallow book, with about 50 orders resting, the naive scan is as fast as our engine, or faster. The committed runs measured 0.47× to 1.31× (results/simulator.txt). We kept that result instead of hiding it.
>
> When books are deep, the structures pay off: **17.24×** faster with 28,382 orders resting and **91.12×** with 113,390. The engine itself runs at 933,266 to 3,643,727 orders per second across our runs. Our target was 50,000.
>
> Cancel takes 0.094 to 0.641 µs at the median, inside the 1 µs target. At the 99th percentile it reached up to 5.040 µs in some runs, which is a miss, so the dashboard shows it as one."

---

## 7. Accounts and the trade archive: LRU and Huffman (1 min)

**Click:** the **Market** tab. Point at the **Account** panel, then the trades feed.

**Audience sees:** each trader's **Cash** and **Margin limit**, labelled *simulation units*, and the trades of this session.

**Say:**
> "**Accounts.** Every order needs a margin check. Accounts live in a hand-written hash table, with an **LRU cache** in front for the active traders: a hit moves to the front in O(1), and the coldest is evicted in O(1). An unbounded hash map is faster, 6.40 ms against our 10.86 on the benchmark, but it holds every account ever seen. That was 47,907 entries against our bounded 10,000. The JDK's own LRU map took 15.84 ms (results/benchmark_run15.txt).
>
> **Trade archive.** Every trade is also written to an archive, compressed with **Huffman coding**: canonical codes, rebuilt from a 256-byte length table. It shrinks our trade logs to 0.5874 of their size. LZW would get 0.2962, but our first LZW silently corrupted data, and Huffman round-tripped correctly the first time. For an audit log, safety beat ratio.
>
> We check that every trade reaches the archive byte for byte: 1,047 of 1,047 in the logbook check (results/drill_logbook_check.txt). After a `kill -9`, all 50 trades were in the archive and the balances matched it (results/drill_kill9.txt)."

---

## 8. How we know it works (0.5 min)

**Click:** nothing. Show the terminal or the README's gates table.

**Say:**
> "One command, `scripts/verify-all.sh`, runs every gate from a fresh clone:
> - 207 backend tests;
> - the six structures checked against the benchmark's own oracles;
> - the end-to-end script;
> - a `kill -9` drill, the feed-failure drills and an event-stream stress drill;
> - a fuzz of every endpoint, 7,415 hostile requests with no server error;
> - a link check of every document.
>
> A 10-minute soak at 300 orders per second stayed flat: 27 threads at the start and the end, and order p99 3.296 ms at the start and 3.197 ms at the end (results/soak.txt). Twenty bugs found along the way are written up in backend/BUGS.md: what broke, why, and the fix."

**End:** `Ctrl-C` in the terminal stops the backend and the feed.

---

## "Why this structure?": one answer each, with the evidence

| Structure | The question it answers | Measured evidence (file) |
| :--- | :--- | :--- |
| **B+ tree** (price ladder) | Ordered price levels: best price, insert or remove a level, walk K levels | Fastest balanced ladder at 24.50 ms (red-black 29.00, radix 32.28). Leanest at 28.9 B per level (red-black 48.0, radix 58.6). The flat array is 10.34 ms but takes 134 MB at a wide tick range (benchmark_run15.txt). It replaced the planned radix tree. |
| **Doubly linked list + hash index** (order queue) | FIFO within a price, plus cancel any order by id with no scan | On a deep book of 15,397 orders: 10.24 ms, against 100.71 for an array and 551.23 for a list without the index. On a shallow book an array ties it, 7.79 vs 8.17 ms (benchmark_run15.txt). |
| **Bellman-Ford on −ln(rate)** (loophole scan) | Does any conversion loop multiply to more than 1? | 0.0006 ms per scan on the real 8-currency snapshot, tied with Johnson and SPFA, 3× faster than Floyd-Warshall. The same work whatever the input order. Production: ≤ 24.98 µs at 12 currencies (benchmark_run15.txt, scan_timing.txt). |
| **Hash map + LRU** (accounts) | O(1) margin checks with bounded memory | 10.86 ms against 15.84 for the JDK's LinkedHashMap LRU and 65.30 for a splay tree. Unbounded maps are faster (6.40) but hold 47,907 entries against our 10,000 (benchmark_run15.txt). |
| **Trie** (pairs) | Validate a pair and autocomplete a prefix | Prefix search: 28.84 ms against sorted array 36.73 and ternary search tree 39.14. A hash set is faster at exact lookups (6.00 vs 8.36) but cannot search by prefix (benchmark_run15.txt). |
| **Huffman coding** (trade archive) | Compress the audit log, provably lossless | Ratio 0.5874 and byte-exact. LZW reaches 0.2962 but its first version corrupted data. Arithmetic coding reaches 0.5842 at 5× the encode time (benchmark_run15.txt). |

---

## Questions a faculty member is likely to ask

**"Is this live market data?"**
Only when the badge says LIVE. In this demo it is a replay of real ticks we recorded, labelled REPLAY. The live feed needs a Finnhub key, the forex market to be open (not at weekends) and network access to Finnhub. When live data stops, the status goes to disconnected within 15 s, and loopholes clear within 30 s (results/drill_feed.txt).

**"Did you find real arbitrage?"**
No. On the real capture no cycle reached our 0.02% threshold; the best was 0.01652% (results/threshold_study.txt). That is the expected result: real markets close these gaps within milliseconds, and the small "cycles" are the bid/ask spread. Every loophole we show glowing is injected and labelled.

**"Why only triangles?"**
Triangular arbitrage is the project's definition. Every three-leg loop at or above the threshold is always reported, because we check all of them exactly. Longer loops are best-effort. Bellman-Ford returns *a* negative cycle, not the best one, and finding the most profitable simple cycle exactly is NP-hard. Near the threshold, on real data, those longer loops are noise (backend/BUGS.md B8, accepted as a limitation).

**"Your engine is slower than a plain array on the shallow simulation. Why use these structures?"**
With about 50 orders resting, a linear scan of 50 entries is very cheap, and we measured that honestly: 0.47× to 1.31×. Real books are deep. With 28,382 and 113,390 orders resting, the engine is 17.24× and 91.12× faster (results/simulator.txt). The structures buy predictable cost as the book grows.

**"You missed the 100 µs scan target."**
At 20 currencies with a cycle present, yes: 160.33 µs. The extra passes that guarantee a small cycle cannot hide a real one cost about V times one pass. We kept the target and report the miss. At the 7 and 12 currencies the system actually uses, the worst case is 24.98 µs (results/scan_timing.txt, doc 04 §3B).

**"Is cancel really O(1)?"**
The work is: one hash lookup and one unlink, plus an O(log P) ladder removal if the level empties. The measured median is 0.094 to 0.641 µs. The p99 exceeded the 1 µs target in 4 of 7 runs (up to 5.040 µs), and the median grows with book size because of CPU cache misses (results/simulator.txt).

**"Where does the money come from? Can cash go negative?"**
Accounts hold *simulation units*: a trade moves price-tick × quantity, with no currency conversion. Resting orders reserve nothing, so two resting buys can both fill and cash can go negative. That is documented in doc 08 Appendix A and pinned by a test. It is a simulator, not a clearing house.

**"What happens if the server crashes?"**
Accounts, id counters and the Huffman trade archive persist. Trades are always written before balances, so a balance on disk never includes a trade the archive lacks (B13, with a fault-injection test). Ids never repeat after a restart (B12). Resting orders are kept in memory only, so a restart starts with empty books (results/drill_kill9.txt).

**"Is it secure? Could it run for many users?"**
There is no authentication: anyone who can reach the port can trade as any trader. It binds to 127.0.0.1 by default. It is one server with one engine thread: deterministic and lock-free, but not replicated and not horizontally scalable. Input handling is hardened, though: 7,415 fuzzed requests gave no server error (results/drill_fuzz.txt). The static file server rejects every path-traversal attempt in its test suite.

**"Did you write the data structures yourselves?"**
Yes. The structure packages import no Java collection classes. Every map, list, heap and tree is hand-written and checked against an independent oracle (results/parity_gates.txt). The benchmark lab in `benchmark/` holds every alternative we measured, including the ones we rejected.

**"What would you do next?"**
- Reserve cash for resting orders.
- Persist the books.
- Add authentication.
- Run an exhaustive search for 4–5-leg cycles if the definition widened beyond triangles.
- Measure on the faculty's hardware.
