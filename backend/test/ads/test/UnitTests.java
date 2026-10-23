package ads.test;

import ads.bench.core.Rng;
import ads.directory.TickerTrie;
import ads.engine.BPlusTreeLadder;
import ads.engine.Order;
import ads.engine.OrderIndex;
import ads.engine.PriceLadder;
import ads.engine.PriceLevel;
import ads.logbook.HuffmanCodec;
import ads.vault.LruCache;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Behaviour the benchmark contracts do not reach: several price levels sharing one order
 * index (the benchmark queue is a single level), the ladder's level-object API, and the
 * production hardening of the trie and the Huffman decoder (backend/BUGS.md B1, B2).
 * References here may use java.util freely (doc 08 §3 rule 7).
 */
final class UnitTests {
  private UnitTests() {
  }

  static void run(TestRunner r) {
    r.section("unit: multi-level book with one global order index");
    for (long seed : new long[] { 1, 2, 3 }) {
      r.check("book of 2 ladders, 200000 random ops vs java.util reference, seed=" + seed,
          () -> multiLevelBook(seed));
    }

    r.section("unit: ladder API");
    r.check("getOrCreate returns the same level; remove returns it; get misses after",
        UnitTests::ladderIdentity);
    r.check("walkDown(MAX) is walkUp(MIN) reversed; walks clamp to the buffer",
        UnitTests::ladderWalks);
    r.check("queue misuse is rejected (double append, unlink at the wrong level)",
        UnitTests::queueMisuse);

    r.section("unit: cache, trie and codec hardening");
    r.check("cache: a null load is returned but not cached; capacity < 1 rejected",
        UnitTests::cacheNullLoad);
    r.check("trie: characters outside A-Z and '/' do not crash lookups (B1)",
        UnitTests::trieUntrustedInput);
    r.check("trie: prefixSearch stops when the buffer is full", UnitTests::trieBoundedBuffer);
    r.check("huffman: truncated streams are rejected, not decoded as padding (B2)",
        UnitTests::huffmanTruncation);
    r.check("huffman: 20000 corrupted streams only ever fail with IllegalArgumentException",
        UnitTests::huffmanFuzz);
  }

  /**
   * Random enqueue / dequeue-best / cancel (known and unknown ids) over a bid and an ask
   * ladder sharing one OrderIndex, mirrored by TreeMap + ArrayDeque. After every op the
   * best level on each side, its FIFO head and its volume must match; at the end every
   * level and every queue must match exactly.
   */
  private static TestRunner.Result multiLevelBook(long seed) {
    Rng rng = new Rng(seed);
    PriceLadder[] side = { new BPlusTreeLadder(), new BPlusTreeLadder() }; // 0 = bids, 1 = asks
    OrderIndex index = new OrderIndex();
    List<TreeMap<Long, ArrayDeque<long[]>>> ref = List.of(new TreeMap<>(), new TreeMap<>());
    Map<Long, long[]> refIndex = new java.util.HashMap<>(); // id -> {side, price, qty}
    List<Long> live = new ArrayList<>();
    long nextId = 1;
    for (int op = 0; op < 200_000; op++) {
      int roll = rng.nextInt(100);
      if (roll < 50 || live.isEmpty()) { // enqueue
        int s = rng.nextInt(2);
        long price = 1_000 + rng.nextInt(200);
        long qty = 1 + rng.nextInt(1_000);
        long id = nextId++;
        Order o = new Order(id, qty);
        side[s].getOrCreate(price).append(o);
        index.put(o);
        ref.get(s).computeIfAbsent(price, p -> new ArrayDeque<>()).add(new long[] { id, qty });
        refIndex.put(id, new long[] { s, price, qty });
        live.add(id);
      } else if (roll < 75) { // execute the head of the best level on one side
        int s = rng.nextInt(2);
        PriceLevel best = s == 0 ? side[s].highest() : side[s].lowest();
        Map.Entry<Long, ArrayDeque<long[]>> refBest =
            s == 0 ? ref.get(s).lastEntry() : ref.get(s).firstEntry();
        if ((best == null) != (refBest == null)) return TestRunner.Result.fail("best presence");
        if (best == null) continue;
        Order head = best.head();
        long[] refHead = refBest.getValue().poll();
        if (head.orderId() != refHead[0]) {
          return TestRunner.Result.fail("op " + op + ": FIFO head " + head.orderId() + " vs "
              + refHead[0]);
        }
        best.unlink(head);
        index.remove(head.orderId());
        refIndex.remove(refHead[0]);
        live.remove(Long.valueOf(refHead[0]));
        if (best.isEmpty()) side[s].remove(best.price());
        if (refBest.getValue().isEmpty()) ref.get(s).remove(refBest.getKey());
      } else { // cancel: a live id 90% of the time, otherwise an unknown one
        long id = rng.nextInt(10) < 9 ? live.get(rng.nextInt(live.size())) : nextId + 1_000;
        Order o = index.remove(id);
        long[] meta = refIndex.remove(id);
        if ((o == null) != (meta == null)) return TestRunner.Result.fail("cancel presence " + id);
        if (o == null) continue;
        PriceLevel level = o.level();
        if (level.price() != meta[1]) return TestRunner.Result.fail("level pointer " + id);
        level.unlink(o);
        if (level.isEmpty()) side[(int) meta[0]].remove(level.price());
        ArrayDeque<long[]> q = ref.get((int) meta[0]).get(meta[1]);
        q.removeIf(e -> e[0] == id);
        if (q.isEmpty()) ref.get((int) meta[0]).remove(meta[1]);
        live.remove(Long.valueOf(id));
      }
      if (index.size() != refIndex.size()) return TestRunner.Result.fail("index size op " + op);
    }
    for (int s = 0; s < 2; s++) {
      TreeMap<Long, ArrayDeque<long[]>> want = ref.get(s);
      if (side[s].size() != want.size()) return TestRunner.Result.fail("level count side " + s);
      PriceLevel[] all = new PriceLevel[side[s].size()];
      side[s].walkUp(Long.MIN_VALUE, all.length, all);
      int i = 0;
      for (Map.Entry<Long, ArrayDeque<long[]>> e : want.entrySet()) {
        PriceLevel l = all[i++];
        if (l.price() != e.getKey()) return TestRunner.Result.fail("level price side " + s);
        long vol = 0;
        Order o = l.head();
        for (long[] q : e.getValue()) {
          if (o == null || o.orderId() != q[0] || o.remaining() != q[1]) {
            return TestRunner.Result.fail("queue contents at " + l.price());
          }
          vol += q[1];
          o = o.next();
        }
        if (o != null || vol != l.totalVolume() || l.orderCount() != e.getValue().size()) {
          return TestRunner.Result.fail("queue tail/volume/count at " + l.price());
        }
      }
    }
    return TestRunner.Result.pass(index.size() + " resting orders, "
        + side[0].size() + " bid / " + side[1].size() + " ask levels at the end");
  }

  private static TestRunner.Result ladderIdentity() {
    PriceLadder l = new BPlusTreeLadder();
    for (long p = 1; p <= 5_000; p++) l.getOrCreate(p * 3);
    PriceLevel a = l.getOrCreate(300);
    if (l.getOrCreate(300) != a || l.get(300) != a) return TestRunner.Result.fail("identity");
    if (l.get(301) != null || l.size() != 5_000) return TestRunner.Result.fail("miss/size");
    if (l.remove(300) != a || l.get(300) != null || l.remove(300) != null) {
      return TestRunner.Result.fail("remove");
    }
    if (l.lowest().price() != 3 || l.highest().price() != 15_000) {
      return TestRunner.Result.fail("extremes");
    }
    for (long p = 1; p <= 5_000; p++) l.remove(p * 3);
    if (l.size() != 0 || l.lowest() != null || l.highest() != null) {
      return TestRunner.Result.fail("not empty after removing everything");
    }
    PriceLevel[] out = new PriceLevel[4];
    if (l.walkUp(0, 4, out) != 0 || l.walkDown(Long.MAX_VALUE, 4, out) != 0) {
      return TestRunner.Result.fail("walk on empty ladder");
    }
    return TestRunner.Result.pass(null);
  }

  private static TestRunner.Result ladderWalks() {
    PriceLadder l = new BPlusTreeLadder();
    Rng rng = new Rng(5);
    for (int i = 0; i < 20_000; i++) l.getOrCreate(rng.nextInt(1_000_000));
    for (int i = 0; i < 10_000; i++) l.remove(rng.nextInt(1_000_000));
    PriceLevel[] up = new PriceLevel[l.size()];
    PriceLevel[] down = new PriceLevel[l.size()];
    int nu = l.walkUp(Long.MIN_VALUE, up.length, up);
    int nd = l.walkDown(Long.MAX_VALUE, down.length, down);
    if (nu != l.size() || nd != l.size()) return TestRunner.Result.fail("walk counts");
    for (int i = 0; i < nu; i++) {
      if (up[i] != down[nu - 1 - i]) return TestRunner.Result.fail("not mirror images");
      if (i > 0 && up[i - 1].price() >= up[i].price()) return TestRunner.Result.fail("order");
    }
    PriceLevel[] small = new PriceLevel[3];
    if (l.walkUp(Long.MIN_VALUE, 10, small) != 3 || small[0] != up[0]) {
      return TestRunner.Result.fail("walk did not clamp to the buffer");
    }
    return TestRunner.Result.pass(nu + " levels");
  }

  private static TestRunner.Result queueMisuse() {
    PriceLevel a = new PriceLevel(10);
    PriceLevel b = new PriceLevel(11);
    Order o = new Order(1, 5);
    a.append(o);
    try {
      b.append(o);
      return TestRunner.Result.fail("double append accepted");
    } catch (IllegalStateException expected) {
      // ok
    }
    try {
      b.unlink(o);
      return TestRunner.Result.fail("unlink at the wrong level accepted");
    } catch (IllegalStateException expected) {
      // ok
    }
    a.unlink(o);
    if (!a.isEmpty() || a.totalVolume() != 0 || o.level() != null) {
      return TestRunner.Result.fail("state after unlink");
    }
    b.append(o); // a cancelled-and-requeued order is fine
    return TestRunner.Result.pass(null);
  }

  private static TestRunner.Result cacheNullLoad() {
    LruCache<String> c = new LruCache<>(4, id -> id % 2 == 0 ? "acct" + id : null);
    if (c.get(3) != null || c.contains(3) || c.size() != 0) {
      return TestRunner.Result.fail("null load was cached");
    }
    if (!"acct4".equals(c.get(4)) || !c.contains(4)) return TestRunner.Result.fail("hit");
    try {
      new LruCache<String>(0, id -> "x");
      return TestRunner.Result.fail("capacity 0 accepted");
    } catch (IllegalArgumentException expected) {
      return TestRunner.Result.pass(null);
    }
  }

  private static TestRunner.Result trieUntrustedInput() {
    TickerTrie t = new TickerTrie();
    t.insert("USD/EUR");
    String[] out = new String[8];
    for (String bad : new String[] { "usd/eur", "USD-EUR", "US D", "USD/EUR\u0000", "€" }) {
      if (t.contains(bad) || t.prefixSearch(bad, out) != 0) {
        return TestRunner.Result.fail("matched invalid input " + bad);
      }
    }
    try {
      t.insert("usd/eur");
      return TestRunner.Result.fail("insert accepted lowercase");
    } catch (IllegalArgumentException expected) {
      // ok
    }
    if (t.size() != 1 || !t.contains("USD/EUR") || t.prefixSearch("", out) != 1) {
      return TestRunner.Result.fail("state");
    }
    return TestRunner.Result.pass(null);
  }

  private static TestRunner.Result trieBoundedBuffer() {
    TickerTrie t = new TickerTrie();
    String[] ccy = { "USD", "EUR", "GBP", "JPY", "AUD", "CAD" };
    for (String a : ccy) {
      for (String b : ccy) {
        if (!a.equals(b)) t.insert(a + "/" + b);
      }
    }
    String[] all = new String[64];
    if (t.prefixSearch("", all) != 30 || t.prefixSearch("USD/", all) != 5) {
      return TestRunner.Result.fail("counts");
    }
    String[] three = new String[3];
    int k = t.prefixSearch("", three);
    if (k != 3 || three[0] == null || three[2] == null) {
      return TestRunner.Result.fail("bounded search returned " + k);
    }
    String[] none = new String[0];
    if (t.prefixSearch("USD", none) != 0) return TestRunner.Result.fail("zero buffer");
    return TestRunner.Result.pass(null);
  }

  private static TestRunner.Result huffmanTruncation() {
    HuffmanCodec codec = new HuffmanCodec();
    byte[] input = ParityTests.edgeInputs()[4]; // 100,000 random bytes
    byte[] enc = codec.encode(input);
    int[] cuts = { 1, 100, 259, 260, 261, enc.length / 2, enc.length - 1 };
    for (int cut : cuts) {
      try {
        codec.decode(Arrays.copyOf(enc, cut));
        return TestRunner.Result.fail("decoded a stream truncated to " + cut + " of "
            + enc.length + " bytes");
      } catch (IllegalArgumentException expected) {
        // ok
      }
    }
    return TestRunner.Result.pass(cuts.length + " truncation points rejected");
  }

  private static TestRunner.Result huffmanFuzz() {
    HuffmanCodec codec = new HuffmanCodec();
    Rng rng = new Rng(11);
    byte[] base = codec.encode("T1,USD/EUR,10050,5\nT2,USD/EUR,10050,7\n".getBytes(
        java.nio.charset.StandardCharsets.US_ASCII));
    int rejected = 0;
    for (int i = 0; i < 20_000; i++) {
      byte[] bad = base.clone();
      int flips = 1 + rng.nextInt(4);
      for (int f = 0; f < flips; f++) bad[rng.nextInt(bad.length)] = (byte) rng.nextInt(256);
      if (rng.nextInt(4) == 0) bad = Arrays.copyOf(bad, rng.nextInt(bad.length + 1));
      try {
        codec.decode(bad);
      } catch (IllegalArgumentException expected) {
        rejected++;
      } catch (RuntimeException | Error wrong) {
        return TestRunner.Result.fail("input " + i + " threw " + wrong);
      }
    }
    return TestRunner.Result.pass(rejected + " rejected, the rest decoded (corruption inside"
        + " the body can still decode to other valid symbols)");
  }
}
