package ads.test;

import ads.bench.arbitrage.ArbitrageBench;
import ads.bench.arbitrage.ArbitrageWorkload;
import ads.bench.arbitrage.BellmanFordArbitrage;
import ads.bench.cache.CacheBench;
import ads.bench.cache.CacheWorkload;
import ads.bench.cache.LruHashCache;
import ads.bench.compression.CompressionBench;
import ads.bench.compression.CompressionResult;
import ads.bench.compression.HuffmanCompressor;
import ads.bench.compression.LogWorkload;
import ads.bench.core.AllocCounter;
import ads.bench.core.BenchResult;
import ads.bench.core.Interleaved;
import ads.bench.core.Stats;
import ads.bench.ladder.LadderBench;
import ads.bench.ladder.LadderWorkload;
import ads.bench.queue.DllHashQueueOps;
import ads.bench.queue.QueueBench;
import ads.bench.queue.QueueWorkload;
import ads.bench.ticker.TickerBench;
import ads.bench.ticker.TickerWorkload;
import ads.bench.ticker.TrieTicker;

/**
 * Production vs benchmark timing (doc 08 Stage 0, informational). Both run in THIS JVM,
 * through the benchmark's own harness: {@link Interleaved} alternates the two candidates
 * round-robin for warmup and timed reps, exactly as the benchmark times its candidates
 * against each other. Workloads and rep counts are the benchmark's documented defaults.
 * The production row includes its adapter (one extra call per op).
 */
final class TimingComparison {
  private static final int REPS = 7;
  private static final int WARMUP = 3;
  private static final int COMPRESSION_ROUNDS = 4;
  private static final AllocCounter ALLOC = new AllocCounter();

  private TimingComparison() {
  }

  static void run() {
    System.out.println();
    System.out.println("== timing: production vs benchmark (same JVM, interleaved, reps="
        + REPS + " warmup=" + WARMUP + ") ==");
    System.out.println("jvm: " + System.getProperty("java.vm.name") + " "
        + System.getProperty("java.runtime.version") + "; alloc counter "
        + (ALLOC.isEnabled() ? "enabled" : "UNAVAILABLE"));
    System.out.println(String.format("%-46s %12s %12s %8s %12s %12s",
        "workload", "bench_ms", "prod_ms", "delta%", "bench_B/op", "prod_B/op"));

    ladder("ladder walk n=300000 seed=42",
        LadderWorkload.generate(300_000, 42, 1_000_000L, 65_536, 500, "walk"));
    ladder("ladder wide-range range=16777216 seed=42",
        LadderWorkload.generate(300_000, 42, 1_000_000L, 16_777_216, 500, "walk"));
    ladder("ladder sorted n=12000 band=4000 seed=42",
        LadderWorkload.generate(12_000, 42, 1_000_000L, 65_536, 4_000, "sorted"));

    for (String profile : new String[] { "shallow", "deep" }) {
      QueueWorkload w = QueueWorkload.generate(300_000, 42, profile);
      pair("queue " + profile + " n=300000 seed=42", w.n, w.seed, 1, w.n,
          QueueBench.trial(DllHashQueueOps::new, w), QueueBench.trial(QueueAdapter::new, w));
    }

    CacheWorkload cw = CacheWorkload.generate(300_000, 42, 100_000, 1.0, 10_000);
    pair("cache n=300000 cap=10000 zipf=1.0 seed=42", cw.n, cw.seed, 1, cw.n,
        CacheBench.trial(() -> new LruHashCache(cw.capacity), cw),
        CacheBench.trial(() -> new CacheAdapter(cw.capacity), cw));

    arbitrage("arbitrage real (V=8, per scan)", ArbitrageWorkload.realSnapshot());
    arbitrage("arbitrage synthetic V=8 inject (per scan)",
        ArbitrageWorkload.synthetic(8, 42, true));
    arbitrage("arbitrage synthetic V=50 (per scan)", ArbitrageWorkload.synthetic(50, 42, false));

    for (String mode : new String[] { "exact", "prefix" }) {
      TickerWorkload w = TickerWorkload.generate(300_000, 42, mode);
      pair("ticker " + mode + " n=300000 seed=42", w.n, w.seed, 1, w.n,
          TickerBench.trial(TrieTicker::new, w), TickerBench.trial(TickerAdapter::new, w));
    }

    compression();
    System.out.println("(delta% = (prod - bench) / bench on the median; doc 08 asks for any"
        + " regression above 25% to be explained)");
  }

  private static void ladder(String label, LadderWorkload w) {
    pair(label, w.n, w.seed, 1, w.n,
        LadderBench.trial(ads.bench.ladder.BPlusTreeLadder::new, w),
        LadderBench.trial(() -> new LadderAdapter(new ads.engine.BPlusTreeLadder()), w));
  }

  private static void arbitrage(String label, ArbitrageWorkload w) {
    int perRep = ArbitrageBench.scansPerRep(w);
    int warmupRounds = ArbitrageBench.warmupRounds(w, WARMUP);
    BenchResult[] r = Interleaved.measure("arbitrage", new String[] { "bench", "prod" },
        new Interleaved.Trial[] {
            ArbitrageBench.trial(BellmanFordArbitrage::new, w, perRep),
            ArbitrageBench.trial(GraphAdapter::new, w, perRep) },
        new String[] { "", "" }, w.edgeFrom.length, w.seed, warmupRounds, REPS, ALLOC, perRep,
        Math.max(1, w.edgeFrom.length));
    print(label, r[0], r[1]);
  }

  private static void pair(String label, int n, long seed, int perRun, double allocDen,
      Interleaved.Trial bench, Interleaved.Trial prod) {
    BenchResult[] r = Interleaved.measure("parity", new String[] { "bench", "prod" },
        new Interleaved.Trial[] { bench, prod }, new String[] { "", "" }, n, seed, WARMUP,
        REPS, ALLOC, perRun, allocDen);
    print(label, r[0], r[1]);
  }

  private static void print(String label, BenchResult b, BenchResult p) {
    String fmt = b.medianMs < 1.0 ? "%12.4f" : "%12.2f";
    System.out.println(String.format("%-46s " + fmt + " " + fmt + " %+7.1f%% %12.1f %12.1f",
        label, b.medianMs, p.medianMs, (p.medianMs - b.medianMs) / b.medianMs * 100.0,
        b.allocBytesPerOp, p.allocBytesPerOp));
  }

  /**
   * The compression harness ({@link CompressionBench#timed}) times one candidate at a
   * time, so the two alternate over {@link #COMPRESSION_ROUNDS} rounds per side (bench,
   * prod, prod, bench, ...) and each side reports the median of its round medians. A
   * single sequential pair proved too noisy in a shared container (one run showed +41% on
   * the byte-identical encoder; see the Stage 0 report).
   */
  private static void compression() {
    LogWorkload w = LogWorkload.generate(42);
    CompressionBench.Factory bench = HuffmanCompressor::new;
    CompressionBench.Factory prod = CompressorAdapter::new;
    long[] be = new long[COMPRESSION_ROUNDS];
    long[] pe = new long[COMPRESSION_ROUNDS];
    long[] bd = new long[COMPRESSION_ROUNDS];
    long[] pd = new long[COMPRESSION_ROUNDS];
    double benchAlloc = -1;
    double prodAlloc = -1;
    for (int round = 0; round < COMPRESSION_ROUNDS; round++) {
      boolean benchFirst = round % 2 == 0;
      for (int k = 0; k < 2; k++) {
        boolean isBench = (k == 0) == benchFirst;
        CompressionResult r = CompressionBench.timed(isBench ? "bench" : "prod",
            isBench ? bench : prod, w, WARMUP, REPS, ALLOC);
        // Stats works on long[]: keep the medians in microseconds
        (isBench ? be : pe)[round] = Math.round(r.encodeMedianMs * 1000);
        (isBench ? bd : pd)[round] = Math.round(r.decodeMedianMs * 1000);
        if (isBench) benchAlloc = r.allocPerInputByte;
        else prodAlloc = r.allocPerInputByte;
      }
    }
    double bem = Stats.median(be) / 1000;
    double pem = Stats.median(pe) / 1000;
    double bdm = Stats.median(bd) / 1000;
    double pdm = Stats.median(pd) / 1000;
    System.out.println(String.format("%-46s %12.2f %12.2f %+7.1f%% %12.2f %12.2f",
        "huffman encode seed=42 (" + w.totalInputBytes + " B)", bem, pem,
        (pem - bem) / bem * 100, benchAlloc, prodAlloc));
    System.out.println(String.format("%-46s %12.2f %12.2f %+7.1f%% %12s %12s",
        "huffman decode seed=42", bdm, pdm, (pdm - bdm) / bdm * 100, "-", "-"));
  }
}
