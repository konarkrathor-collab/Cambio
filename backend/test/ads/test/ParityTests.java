package ads.test;

import ads.bench.arbitrage.ArbitrageBench;
import ads.bench.arbitrage.ArbitrageWorkload;
import ads.bench.arbitrage.BellmanFordArbitrage;
import ads.bench.arbitrage.BruteForceOracle;
import ads.bench.cache.AccountStore;
import ads.bench.cache.CacheBench;
import ads.bench.cache.CacheOps;
import ads.bench.cache.CacheWorkload;
import ads.bench.cache.LruHashCache;
import ads.bench.cache.LruReferenceOracle;
import ads.bench.compression.CompressionBench;
import ads.bench.compression.HuffmanCompressor;
import ads.bench.compression.LogWorkload;
import ads.bench.core.Rng;
import ads.bench.ladder.LadderBench;
import ads.bench.ladder.LadderWorkload;
import ads.bench.ladder.TreeMapLadder;
import ads.bench.queue.LinkedHashMapOracle;
import ads.bench.queue.QueueBench;
import ads.bench.queue.QueueWorkload;
import ads.bench.ticker.LinearListOracle;
import ads.bench.ticker.TickerBench;
import ads.bench.ticker.TickerWorkload;
import ads.engine.BPlusTreeLadder;
import ads.logbook.HuffmanCodec;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stage 0 parity gates (doc 08): each production structure, behind its adapter, runs the
 * benchmark's OWN correctness gate against the benchmark's oracle on the benchmark's own
 * seeded workloads. The workload parameters are the benchmark's documented runs
 * (benchmark/README.md, results/RESULTS.md), plus extra seeds and small-band stress
 * configurations that drive the B+ tree's borrow, merge and root-collapse paths.
 */
final class ParityTests {
  private ParityTests() {
  }

  // ladder defaults: benchmark Main
  private static final long BASE = 1_000_000L;
  private static final int RANGE = 65_536;
  private static final int WIDE_RANGE = 16_777_216;
  /** Main's ORACLE_MAX_V: above this the brute-force oracle is infeasible. */
  private static final int ORACLE_MAX_V = 10;

  static void run(TestRunner r) {
    ladder(r);
    queue(r);
    cache(r);
    arbitrage(r);
    ticker(r);
    compression(r);
  }

  // ---------------------------------------------------------------- ladder (B+ tree)

  private static void ladder(TestRunner r) {
    r.section("parity: price ladder (B+ tree) vs TreeMap oracle, LadderBench.verify");
    for (long seed : new long[] { 42, 7, 2026 }) {
      r.check("ladder walk n=300000 band=500 seed=" + seed,
          () -> ladderGate(300_000, seed, RANGE, 500, "walk"));
    }
    for (long seed : new long[] { 42, 7 }) {
      r.check("ladder wide-range n=300000 range=16777216 band=500 seed=" + seed,
          () -> ladderGate(300_000, seed, WIDE_RANGE, 500, "walk"));
    }
    for (long seed : new long[] { 42, 7 }) {
      r.check("ladder sorted n=12000 band=4000 seed=" + seed,
          () -> ladderGate(12_000, seed, RANGE, 4_000, "sorted"));
    }
    // book empties and refills constantly at small bands: split/borrow/merge/root collapse
    for (int band : new int[] { 1, 2, 3, 5, 17, 64, 1_000, 3_000 }) {
      for (long seed = 1; seed <= 4; seed++) {
        final long s = seed;
        r.check("ladder stress walk n=50000 band=" + band + " seed=" + s,
            () -> ladderGate(50_000, s, RANGE, band, "walk"));
      }
      r.check("ladder stress sorted n=50000 band=" + band + " seed=1",
          () -> ladderGate(50_000, 1, RANGE, band, "sorted"));
    }
  }

  private static TestRunner.Result ladderGate(int n, long seed, int range, int band,
      String pattern) {
    LadderWorkload w = LadderWorkload.generate(n, seed, BASE, range, band, pattern);
    TreeMapLadder oracle = new TreeMapLadder();
    long checksum = LadderBench.replay(oracle, w);
    TreeMap<Long, Long> state = LadderBench.snapshot(oracle, w.n);
    String problem = LadderBench.verify(new LadderAdapter(new BPlusTreeLadder()), w, checksum,
        state);
    return TestRunner.Result.gate(problem,
        "checksum=0x" + Long.toHexString(checksum) + " levels=" + state.size());
  }

  // ---------------------------------------------------------------- order queue

  private static void queue(TestRunner r) {
    r.section("parity: order queue (level DLL + global index) vs LinkedHashMap oracle,"
        + " QueueBench.verify");
    for (String profile : new String[] { "shallow", "deep" }) {
      for (long seed : new long[] { 42, 7 }) {
        r.check("queue " + profile + " n=300000 seed=" + seed, () -> {
          QueueWorkload w = QueueWorkload.generate(300_000, seed, profile);
          LinkedHashMapOracle oracle = new LinkedHashMapOracle();
          long checksum = QueueBench.replay(oracle, w);
          TreeMap<Long, Long> state = QueueBench.snapshot(oracle, w.n);
          return TestRunner.Result.gate(
              QueueBench.verify(new QueueAdapter(), w, checksum, state),
              "checksum=0x" + Long.toHexString(checksum) + " liveOrders=" + state.size());
        });
      }
    }
  }

  // ---------------------------------------------------------------- cache (hash + LRU)

  private static void cache(TestRunner r) {
    r.section("parity: account cache (hash + LRU) vs LRU reference, CacheBench gates");
    r.check("cache residency gate: CacheOps copy agrees with the benchmark original", () -> {
      CacheWorkload w = CacheWorkload.generate(300_000, 42, 100_000, 1.0, 10_000);
      String original = CacheBench.verifyLruResidency(new LruHashCache(w.capacity), w);
      String copy = verifyLruResidency(new LruHashCache(w.capacity), w);
      if (original != null || copy != null) {
        return TestRunner.Result.fail("benchmark LRU: original=" + original + " copy=" + copy);
      }
      return TestRunner.Result.pass("both pass the benchmark's own LruHashCache");
    });
    int[][] configs = { // n, seed, universe, zipf*100, capacity
        { 300_000, 42, 100_000, 100, 10_000 },
        { 300_000, 7, 100_000, 100, 10_000 },
        { 100_000, 42, 5_000, 80, 500 },
        { 100_000, 42, 1_000, 100, 1 },
        { 100_000, 42, 50_000, 100, 200_000 },
    };
    for (int[] c : configs) {
      String name = "cache n=" + c[0] + " seed=" + c[1] + " universe=" + c[2] + " zipf="
          + (c[3] / 100.0) + " capacity=" + c[4];
      r.check(name, () -> {
        CacheWorkload w = CacheWorkload.generate(c[0], c[1], c[2], c[3] / 100.0, c[4]);
        LruReferenceOracle oracle = new LruReferenceOracle(w.capacity);
        long checksum = CacheBench.replay(oracle, w);
        String problem = CacheBench.verifyBalance(new CacheAdapter(w.capacity), w, checksum);
        if (problem == null) {
          problem = verifyLruResidency(new CacheAdapter(w.capacity), w);
        }
        return TestRunner.Result.gate(problem,
            "checksum=0x" + Long.toHexString(checksum) + " resident=" + oracle.size());
      });
    }
  }

  /**
   * The benchmark's {@code CacheBench.verifyLruResidency}, copied line for line except for
   * the parameter type: the original takes the benchmark's concrete {@code LruHashCache},
   * so it cannot accept an adapter (backend/BUGS.md, deviation note). The first cache check
   * runs both on the benchmark's own LRU to show the copy decides the same way.
   */
  static String verifyLruResidency(CacheOps candidate, CacheWorkload w) {
    int capacity = w.capacity;
    LinkedHashMap<Long, Long> ref = new LinkedHashMap<Long, Long>(16, 0.75f, true) {
      private static final long serialVersionUID = 1L;

      @Override
      protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
        return size() > capacity;
      }
    };
    int[] checkpoints = { w.n / 4, w.n / 2, (3 * w.n) / 4 };
    int next = 0;
    for (int i = 0; i < w.n; i++) {
      if (next < checkpoints.length && i == checkpoints[next]) {
        String p = compareResidency(candidate, ref, w, i);
        if (p != null) return p;
        next++;
      }
      long id = w.traderId[i];
      candidate.access(id);
      Long v = ref.get(id);
      if (v == null) {
        ref.put(id, AccountStore.balance(id));
      }
    }
    return compareResidency(candidate, ref, w, w.n);
  }

  private static String compareResidency(CacheOps candidate, LinkedHashMap<Long, Long> ref,
      CacheWorkload w, int atOp) {
    boolean[] refResident = new boolean[w.universe + 1];
    for (Long id : ref.keySet()) {
      refResident[id.intValue()] = true;
    }
    for (int id = 1; id <= w.universe; id++) {
      boolean have = candidate.contains(id);
      if (have != refResident[id]) {
        return "LRU residency mismatch at op " + atOp + ": id " + id + " candidate=" + have
            + " reference=" + refResident[id];
      }
    }
    return null;
  }

  // ---------------------------------------------------------------- arbitrage (Bellman-Ford)

  private static void arbitrage(TestRunner r) {
    r.section("parity: arbitrage scanner (Bellman-Ford) vs brute-force oracle,"
        + " ArbitrageBench.verify");
    r.check("arbitrage real Frankfurter snapshot", () -> arbitrageGate(
        ArbitrageWorkload.realSnapshot()));
    for (int v : new int[] { 3, 4, 5, 6, 8, 10, 20, 50 }) {
      for (boolean inject : new boolean[] { true, false }) {
        for (long seed : new long[] { 42, 7 }) {
          r.check("arbitrage synthetic v=" + v + " inject=" + inject + " seed=" + seed,
              () -> arbitrageGate(ArbitrageWorkload.synthetic(v, seed, inject)));
        }
      }
    }
  }

  /**
   * Main's procedure: the brute-force oracle's verdict for V <= 10, else the benchmark
   * Bellman-Ford's verdict (stated in the detail). Then a literal check on top: the
   * production cycle must equal the benchmark Bellman-Ford's cycle vertex for vertex.
   */
  private static TestRunner.Result arbitrageGate(ArbitrageWorkload w) {
    boolean reference;
    String source;
    if (w.v <= ORACLE_MAX_V) {
      reference = new BruteForceOracle(w.v, w.edgeFrom, w.edgeTo, w.edgeWeight)
          .hasNegativeCycle();
      source = "brute-force oracle";
    } else {
      reference = ArbitrageBench.runOnce(new BellmanFordArbitrage(), w).hasCycle;
      source = "benchmark Bellman-Ford verdict (V>" + ORACLE_MAX_V + ")";
    }
    String problem = ArbitrageBench.verify(new GraphAdapter(), w, reference);
    if (problem != null) return TestRunner.Result.fail(problem);
    int[] mine = ArbitrageBench.runOnce(new GraphAdapter(), w).cycle;
    int[] theirs = ArbitrageBench.runOnce(new BellmanFordArbitrage(), w).cycle;
    if (!Arrays.equals(mine, theirs)) {
      return TestRunner.Result.fail("cycle differs from benchmark Bellman-Ford: "
          + Arrays.toString(mine) + " vs " + Arrays.toString(theirs));
    }
    return TestRunner.Result.pass("hasCycle=" + reference + " via " + source
        + (mine.length > 0 ? " cycle=" + Arrays.toString(mine) : ""));
  }

  // ---------------------------------------------------------------- ticker (trie)

  private static void ticker(TestRunner r) {
    r.section("parity: ticker directory (trie) vs linear-scan oracle, TickerBench.verify");
    for (String mode : new String[] { "exact", "prefix" }) {
      for (long seed : new long[] { 42, 7 }) {
        r.check("ticker " + mode + " n=300000 seed=" + seed, () -> {
          TickerWorkload w = TickerWorkload.generate(300_000, seed, mode);
          long checksum = TickerBench.replay(new LinearListOracle(), w);
          return TestRunner.Result.gate(TickerBench.verify(new TickerAdapter(), w, checksum),
              "checksum=0x" + Long.toHexString(checksum) + " registry=" + w.universe.length);
        });
      }
    }
  }

  // ---------------------------------------------------------------- compression (Huffman)

  private static void compression(TestRunner r) {
    r.section("parity: logbook codec (Huffman) round trip, CompressionBench.verify,"
        + " plus byte-identical encoding");
    for (long seed : new long[] { 42, 7 }) {
      r.check("huffman trade-log batches seed=" + seed, () -> {
        LogWorkload w = LogWorkload.generate(seed);
        String problem = CompressionBench.verify(new CompressorAdapter(), w);
        if (problem == null) problem = sameEncoding(w.batches);
        return TestRunner.Result.gate(problem,
            w.batches.length + " batches, " + w.totalInputBytes + " bytes, encoding"
                + " byte-identical to the benchmark");
      });
    }
    r.check("huffman edge inputs (empty, 1 byte, one symbol, all 256, random, skewed)", () -> {
      byte[][] inputs = edgeInputs();
      for (int i = 0; i < inputs.length; i++) {
        HuffmanCodec codec = new HuffmanCodec();
        byte[] enc = codec.encode(inputs[i]);
        if (!Arrays.equals(codec.decode(enc), inputs[i])) {
          return TestRunner.Result.fail("round trip differs on edge input " + i);
        }
      }
      String problem = sameEncoding(inputs);
      return TestRunner.Result.gate(problem, inputs.length + " inputs round-trip and encode"
          + " byte-identically");
    });
  }

  private static String sameEncoding(byte[][] batches) {
    HuffmanCodec mine = new HuffmanCodec();
    HuffmanCompressor theirs = new HuffmanCompressor();
    for (int b = 0; b < batches.length; b++) {
      if (!Arrays.equals(mine.encode(batches[b]), theirs.encode(batches[b]))) {
        return "encoding differs from the benchmark's on batch " + b;
      }
    }
    return null;
  }

  static byte[][] edgeInputs() {
    Rng rng = new Rng(99);
    byte[] random = new byte[100_000];
    for (int i = 0; i < random.length; i++) random[i] = (byte) rng.nextInt(256);
    byte[] all = new byte[256];
    for (int i = 0; i < 256; i++) all[i] = (byte) i;
    byte[] same = new byte[1_000];
    Arrays.fill(same, (byte) 'x');
    // Fibonacci frequencies give the deepest possible tree for their total
    StringBuilder skew = new StringBuilder();
    int a = 1;
    int b = 1;
    for (int s = 0; s < 20; s++) {
      for (int k = 0; k < a; k++) skew.append((char) ('A' + s));
      int t = a + b;
      a = b;
      b = t;
    }
    byte[] skewed = skew.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    return new byte[][] { new byte[0], { 42 }, same, all, random, skewed };
  }
}
