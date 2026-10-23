package ads.bench;

import ads.bench.arbitrage.ArbitrageBench;
import ads.bench.arbitrage.ArbitrageWorkload;
import ads.bench.arbitrage.BellmanFordArbitrage;
import ads.bench.arbitrage.BruteForceOracle;
import ads.bench.arbitrage.FloydWarshallArbitrage;
import ads.bench.arbitrage.GraphOps;
import ads.bench.arbitrage.JohnsonArbitrage;
import ads.bench.arbitrage.SpfaArbitrage;
import ads.bench.arbitrage.TarjanArbitrage;
import ads.bench.cache.CacheBench;
import ads.bench.cache.CacheOps;
import ads.bench.cache.CacheWorkload;
import ads.bench.cache.ChainHashCache;
import ads.bench.cache.LruHashCache;
import ads.bench.cache.LruReferenceOracle;
import ads.bench.cache.OpenHashCache;
import ads.bench.cache.SplayCache;
import ads.bench.core.AllocCounter;
import ads.bench.core.BenchResult;
import ads.bench.core.DeepSize;
import ads.bench.core.Interleaved;
import ads.bench.ladder.AvlLadder;
import ads.bench.ladder.BPlusTreeLadder;
import ads.bench.ladder.BTreeLadder;
import ads.bench.ladder.BstLadder;
import ads.bench.ladder.FlatArrayLadder;
import ads.bench.ladder.LadderBench;
import ads.bench.ladder.LadderOps;
import ads.bench.ladder.LadderWorkload;
import ads.bench.ladder.RadixLadder;
import ads.bench.ladder.RedBlackLadder;
import ads.bench.ladder.SkipListLadder;
import ads.bench.ladder.SortedArrayLadder;
import ads.bench.ladder.SplayLadder;
import ads.bench.ladder.TreeMapLadder;
import ads.bench.ladder.UnsortedArrayLadder;
import ads.bench.queue.ArrayQueueOps;
import ads.bench.queue.DllHashQueueOps;
import ads.bench.queue.DllQueueOps;
import ads.bench.queue.LinkedHashMapOracle;
import ads.bench.queue.QueueBench;
import ads.bench.queue.QueueWorkload;
import ads.bench.queue.SinglyListQueueOps;
import ads.bench.queue.TreeQueueOps;
import ads.bench.ticker.HashSetTicker;
import ads.bench.ticker.LinearListOracle;
import ads.bench.ticker.SortedArrayTicker;
import ads.bench.ticker.TickerBench;
import ads.bench.ticker.TickerWorkload;
import ads.bench.ticker.TrieTicker;
import ads.bench.ticker.TstTicker;
import ads.bench.compression.ArithmeticCompressor;
import ads.bench.compression.CompressionBench;
import ads.bench.compression.CompressionResult;
import ads.bench.compression.HuffmanCompressor;
import ads.bench.compression.LogWorkload;
import ads.bench.compression.LzwCompressor;
import ads.bench.compression.RleCompressor;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;

/** CLI entry point. Example: ./run.sh ladder --n 300000 --seed 42 --reps 7 --warmup 3 */
public final class Main {
  private static void usage() {
    System.out.println("usage: run.sh ladder [--n N] [--seed S] [--reps R] [--warmup W]"
        + " [--base B] [--range R] [--band H] [--pattern walk|sorted] [--out FILE]");
    System.out.println("   or: run.sh arbitrage [--graph real|synthetic] [--v V]"
        + " [--inject true|false] [--seed S] [--reps R] [--warmup W] [--out FILE]");
    System.out.println("   or: run.sh queue [--n N] [--seed S] [--reps R] [--warmup W]"
        + " [--profile shallow|deep] [--out FILE]");
    System.out.println("   or: run.sh cache [--n N] [--seed S] [--reps R] [--warmup W]"
        + " [--universe U] [--capacity C] [--zipf S] [--out FILE]");
    System.out.println("   or: run.sh ticker [--n N] [--seed S] [--reps R] [--warmup W]"
        + " [--out FILE]");
    System.out.println("   or: run.sh compression [--seed S] [--reps R] [--warmup W]"
        + " [--out FILE]");
    System.out.println("   or: run.sh footprint [ladder flags]   (resident bytes per structure;"
        + " default --out results/footprint_results.csv)");
  }

  public static void main(String[] args) throws IOException {
    if (args.length == 0) {
      usage();
      System.exit(2);
    }
    if (args[0].equals("arbitrage")) {
      runArbitrage(args);
      return;
    }
    if (args[0].equals("queue")) {
      runQueue(args);
      return;
    }
    if (args[0].equals("cache")) {
      runCache(args);
      return;
    }
    if (args[0].equals("ticker")) {
      runTicker(args);
      return;
    }
    if (args[0].equals("compression")) {
      runCompression(args);
      return;
    }
    boolean footprint = args[0].equals("footprint");
    if (!footprint && !args[0].equals("ladder")) {
      usage();
      System.exit(2);
    }

    int n = 300_000;
    long seed = 42;
    int reps = 7;
    int warmup = 3;
    long base = 1_000_000L;
    int range = 65_536;
    int band = 500;
    String pattern = "walk";
    String outPath = footprint ? "results/footprint_results.csv" : "results/raw_results.csv";

    for (int i = 1; i + 1 < args.length; i += 2) {
      String k = args[i];
      String v = args[i + 1];
      switch (k) {
        case "--n": n = Integer.parseInt(v); break;
        case "--seed": seed = Long.parseLong(v); break;
        case "--reps": reps = Integer.parseInt(v); break;
        case "--warmup": warmup = Integer.parseInt(v); break;
        case "--base": base = Long.parseLong(v); break;
        case "--range": range = Integer.parseInt(v); break;
        case "--band": band = Integer.parseInt(v); break;
        case "--pattern": pattern = v; break;
        case "--out": outPath = v; break;
        default:
          usage();
          System.exit(2);
      }
    }

    // effective-final copies for use inside lambdas
    final long fSeed = seed;
    final long fBase = base;
    final int fRange = range;

    LadderWorkload w = LadderWorkload.generate(n, fSeed, fBase, fRange, band, pattern);
    System.out.println("workload: " + w.describe());

    // Oracle: the correctness gate
    TreeMapLadder oracle = new TreeMapLadder();
    long oracleChecksum = LadderBench.replay(oracle, w);
    TreeMap<Long, Long> oracleState = LadderBench.snapshot(oracle, w.n);
    System.out.println("oracle: checksum=0x" + Long.toHexString(oracleChecksum)
        + " levels=" + oracleState.size());

    Map<String, LadderBench.Factory> candidates = ladderCandidates(w, fSeed, fBase, fRange);
    if (footprint) {
      runFootprint(candidates, w, oracleChecksum, oracleState, band, pattern, outPath);
      return;
    }

    AllocCounter alloc = new AllocCounter();
    System.out.println("alloc counter: " + (alloc.isEnabled() ? "enabled" : "UNAVAILABLE"));
    System.out.println();
    System.out.println(BenchResult.header());

    boolean anyFailed = false;
    Path out = Paths.get(outPath);
    if (out.getParent() != null) {
      Files.createDirectories(out.getParent());
    }
    boolean needHeader = !Files.exists(out) || Files.size(out) == 0;

    try (BufferedWriter csv = Files.newBufferedWriter(out, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
      if (needHeader) {
        csv.write(BenchResult.csvHeader());
        csv.newLine();
      }
      Map<String, String> problems = new LinkedHashMap<>();
      Map<String, Interleaved.Trial> trials = new LinkedHashMap<>();
      for (Map.Entry<String, LadderBench.Factory> e : candidates.entrySet()) {
        String problem = LadderBench.verify(e.getValue().create(), w, oracleChecksum, oracleState);
        problems.put(e.getKey(), problem);
        trials.put(e.getKey(), LadderBench.trial(e.getValue(), w));
        anyFailed |= problem != null;
      }
      final int fReps = reps;
      timeAndWrite("ladder", problems, trials, new LinkedHashMap<>(), w.n, w.seed, warmup, reps,
          alloc, 1, w.n, (name, why) -> LadderBench.failed("ladder", name, w, fReps, why), csv);
    }

    // Ladder v2 counter report: doc 05 §1 claims AVL does MORE rotations than Red-Black
    // per structural mutation — verified with actual counters on a fresh replay of the
    // same workload, not inferred from timing. Single rotations count individually;
    // a double rotation counts as 2. Splay's number is informational (restructures, not
    // a balancing counter).
    RedBlackLadder rbCounter = new RedBlackLadder();
    LadderBench.replay(rbCounter, w);
    AvlLadder avlCounter = new AvlLadder();
    LadderBench.replay(avlCounter, w);
    SplayLadder splayCounter = new SplayLadder();
    LadderBench.replay(splayCounter, w);
    System.out.println();
    System.out.println("rebalance counters (fresh replay of the same workload):");
    System.out.printf("  red-black: %d rotations (insert %d, delete %d)%n",
        rbCounter.rotations(), rbCounter.insertRotations(), rbCounter.deleteRotations());
    System.out.printf("  avl:       %d rotations (insert %d, delete %d)%n",
        avlCounter.rotations(), avlCounter.insertRotations(), avlCounter.deleteRotations());
    System.out.printf("  splay:     %d restructure rotations%n", splayCounter.rotations());
    BTreeLadder twoThree = new BTreeLadder(3);
    LadderBench.replay(twoThree, w);
    BTreeLadder bTree = new BTreeLadder(32);
    LadderBench.replay(bTree, w);
    System.out.printf("  2-3 tree:  %d splits, %d merges (no rotations)%n",
        twoThree.splits(), twoThree.merges());
    System.out.printf("  b-tree32:  %d splits, %d merges (no rotations)%n",
        bTree.splits(), bTree.merges());

    System.out.println();
    System.out.println(anyFailed
        ? "RESULT: FAILURES PRESENT (a structure disagreed with the oracle)"
        : "RESULT: all candidates validated against the oracle and timed OK");
    if (anyFailed) {
      System.exit(1);
    }
  }

  /**
   * Times every verified candidate TOGETHER, round-robin ({@link Interleaved} — fairness
   * rule 3b), then prints and appends one row per candidate in declaration order: the
   * timed row, or a FAIL row for a candidate that failed its gate (never timed).
   */
  private static void timeAndWrite(String bench, Map<String, String> problems,
      Map<String, Interleaved.Trial> trials, Map<String, String> notes, int n, long seed,
      int warmup, int reps, AllocCounter alloc, int perRun, double allocDen,
      BiFunction<String, String, BenchResult> failed, BufferedWriter csv) throws IOException {
    List<String> names = new ArrayList<>();
    List<Interleaved.Trial> ok = new ArrayList<>();
    List<String> okNotes = new ArrayList<>();
    for (Map.Entry<String, String> e : problems.entrySet()) {
      if (e.getValue() == null) {
        names.add(e.getKey());
        ok.add(trials.get(e.getKey()));
        okNotes.add(notes.getOrDefault(e.getKey(), ""));
      }
    }
    BenchResult[] timed = Interleaved.measure(bench, names.toArray(new String[0]),
        ok.toArray(new Interleaved.Trial[0]), okNotes.toArray(new String[0]), n, seed, warmup,
        reps, alloc, perRun, allocDen);
    int t = 0;
    for (Map.Entry<String, String> e : problems.entrySet()) {
      BenchResult r = e.getValue() == null ? timed[t++] : failed.apply(e.getKey(), e.getValue());
      System.out.println(r.row());
      csv.write(r.csv());
      csv.newLine();
    }
  }

  /** Every price-ladder candidate, in report order (shared by the ladder and footprint benches). */
  private static Map<String, LadderBench.Factory> ladderCandidates(LadderWorkload w, long seed,
      long base, int range) {
    Map<String, LadderBench.Factory> candidates = new LinkedHashMap<>();
    candidates.put("oracle-treeMap", TreeMapLadder::new);
    candidates.put("unsorted-array", () -> new UnsortedArrayLadder(w.n));
    candidates.put("sorted-array", () -> new SortedArrayLadder(w.n));
    candidates.put("flat-array", () -> new FlatArrayLadder(base, range));
    candidates.put("skip-list", () -> new SkipListLadder(seed ^ 0x5DEECE66DL));
    candidates.put("radix16", RadixLadder::new);
    candidates.put("bst", BstLadder::new);
    candidates.put("avl", AvlLadder::new);
    candidates.put("red-black", RedBlackLadder::new);
    candidates.put("splay", SplayLadder::new);
    candidates.put("2-3-tree", () -> new BTreeLadder(3));
    candidates.put("b-tree32", () -> new BTreeLadder(32));
    candidates.put("b+tree32", BPlusTreeLadder::new);
    return candidates;
  }

  /**
   * Footprint bench: RESIDENT bytes each ladder candidate retains after replaying the
   * workload (not AllocCounter's lifetime churn). Every candidate is oracle-gated first,
   * exactly like the timed bench; a candidate that fails is reported FAIL and not measured.
   * Two measurements per structure: the deterministic {@link DeepSize} graph walk, and a
   * live-heap delta as an independent cross-check of that model.
   */
  private static void runFootprint(Map<String, LadderBench.Factory> candidates,
      LadderWorkload w, long oracleChecksum, TreeMap<Long, Long> oracleState, int band,
      String pattern, String outPath) throws IOException {
    System.out.println("layout: " + DeepSize.layout());
    System.out.println();
    System.out.println(String.format("%-16s %8s %14s %10s %10s %14s %8s",
        "structure", "levels", "resident_B", "B/level", "objects", "heapDelta_B", "valid"));
    Path out = Paths.get(outPath);
    if (out.getParent() != null) {
      Files.createDirectories(out.getParent());
    }
    boolean needHeader = !Files.exists(out) || Files.size(out) == 0;
    boolean anyFailed = false;
    try (BufferedWriter csv = Files.newBufferedWriter(out, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
      if (needHeader) {
        csv.write("bench,structure,n,seed,range,band,pattern,valid,levels,resident_bytes,"
            + "bytes_per_level,objects,heap_delta_bytes,note");
        csv.newLine();
      }
      for (Map.Entry<String, LadderBench.Factory> e : candidates.entrySet()) {
        String name = e.getKey();
        String problem = LadderBench.verify(e.getValue().create(), w, oracleChecksum,
            oracleState);
        String prefix = "footprint," + name + "," + w.n + "," + w.seed + "," + w.range + ","
            + band + "," + pattern + ",";
        if (problem != null) {
          anyFailed = true;
          System.out.println(String.format("%-16s %8s %14s %10s %10s %14s %8s  %s",
              name, "-", "-", "-", "-", "-", "FAIL", problem));
          csv.write(prefix + "FAIL,0,0,0,0,0," + problem.replace(',', ';'));
          csv.newLine();
          continue;
        }
        Object[] holder = { e.getValue().create() };
        LadderBench.replay((LadderOps) holder[0], w);
        int levels = ((LadderOps) holder[0]).size();
        String note = "";
        long bytes = -1;
        long objects = -1;
        try {
          DeepSize.Size sz = DeepSize.of(holder[0]);
          bytes = sz.bytes;
          objects = sz.objects;
        } catch (RuntimeException ex) {
          note = "graph walk unavailable: " + ex.getClass().getSimpleName();
        }
        long delta = DeepSize.heapDelta(holder); // drops holder[0]
        double perLevel = bytes < 0 || levels == 0 ? Double.NaN : (double) bytes / levels;
        System.out.println(String.format("%-16s %8d %14s %10s %10s %14d %8s  %s",
            name, levels, bytes < 0 ? "n/a" : Long.toString(bytes),
            Double.isNaN(perLevel) ? "n/a" : String.format("%.1f", perLevel),
            objects < 0 ? "n/a" : Long.toString(objects), delta, "OK", note));
        csv.write(prefix + "ok," + levels + "," + bytes + ","
            + (Double.isNaN(perLevel) ? "" : String.format("%.2f", perLevel)) + "," + objects
            + "," + delta + "," + note);
        csv.newLine();
      }
    }
    System.out.println();
    System.out.println(anyFailed
        ? "RESULT: FAILURES PRESENT (a structure disagreed with the oracle)"
        : "RESULT: all candidates validated against the oracle; resident footprint measured");
    if (anyFailed) {
      System.exit(1);
    }
  }

  /** Independent-oracle verification is only feasible on small graphs (exhaustive simple-
   * cycle enumeration grows roughly factorially). Beyond this, we fall back to a trusted
   * reference candidate's verdict (Bellman-Ford — doc 05's own pick) and say so explicitly. */
  private static final int ORACLE_MAX_V = 10;

  private static void runArbitrage(String[] args) throws IOException {
    String graph = "real";
    int v = 8;
    boolean inject = false;
    long seed = 42;
    int reps = 7;
    int warmup = 3;
    String outPath = "results/raw_results.csv";

    for (int i = 1; i + 1 < args.length; i += 2) {
      String k = args[i];
      String val = args[i + 1];
      switch (k) {
        case "--graph": graph = val; break;
        case "--v": v = Integer.parseInt(val); break;
        case "--inject": inject = Boolean.parseBoolean(val); break;
        case "--seed": seed = Long.parseLong(val); break;
        case "--reps": reps = Integer.parseInt(val); break;
        case "--warmup": warmup = Integer.parseInt(val); break;
        case "--out": outPath = val; break;
        default:
          usage();
          System.exit(2);
      }
    }

    ArbitrageWorkload w;
    if (graph.equals("real")) {
      w = ArbitrageWorkload.realSnapshot();
    } else if (graph.equals("synthetic")) {
      w = ArbitrageWorkload.synthetic(v, seed, inject);
    } else {
      System.out.println("unknown --graph: " + graph + " (expected real|synthetic)");
      System.exit(2);
      return;
    }
    System.out.println("workload: " + w.describe());

    boolean referenceHasCycle;
    String referenceSource;
    if (w.v <= ORACLE_MAX_V) {
      BruteForceOracle oracle = new BruteForceOracle(w.v, w.edgeFrom, w.edgeTo, w.edgeWeight);
      referenceHasCycle = oracle.hasNegativeCycle();
      referenceSource = "independent brute-force oracle (V=" + w.v + " <= " + ORACLE_MAX_V + ")";
      if (referenceHasCycle) {
        String shapeProblem = ArbitrageBench.verifyCycleShape(w, oracle.foundCycle());
        if (shapeProblem != null) {
          System.out.println("WARNING: oracle's own reconstructed cycle failed its shape check: "
              + shapeProblem);
        }
      }
    } else {
      BellmanFordArbitrage reference = new BellmanFordArbitrage();
      referenceHasCycle = ArbitrageBench.runOnce(reference, w).hasCycle;
      referenceSource = "Bellman-Ford verdict (V=" + w.v + " > " + ORACLE_MAX_V
          + " — independent oracle infeasible at this size; cross-candidate agreement only)";
    }
    System.out.println("reference: hasNegativeCycle=" + referenceHasCycle + " via " + referenceSource);

    Map<String, ArbitrageBench.Factory> candidates = new LinkedHashMap<>();
    candidates.put("bellman-ford", BellmanFordArbitrage::new);
    candidates.put("spfa", SpfaArbitrage::new);
    candidates.put("floyd-warshall", FloydWarshallArbitrage::new);
    candidates.put("johnson", JohnsonArbitrage::new);
    candidates.put("tarjan+bf", TarjanArbitrage::new);

    AllocCounter alloc = new AllocCounter();
    System.out.println("alloc counter: " + (alloc.isEnabled() ? "enabled" : "UNAVAILABLE"));
    System.out.println();
    System.out.println(BenchResult.header());

    boolean anyFailed = false;
    Path out = Paths.get(outPath);
    if (out.getParent() != null) {
      Files.createDirectories(out.getParent());
    }
    boolean needHeader = !Files.exists(out) || Files.size(out) == 0;

    try (BufferedWriter csv = Files.newBufferedWriter(out, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
      if (needHeader) {
        csv.write(BenchResult.csvHeader());
        csv.newLine();
      }
      int perRep = ArbitrageBench.scansPerRep(w);
      int warmupRounds = ArbitrageBench.warmupRounds(w, warmup);
      System.out.println("timing: " + perRep + " scans/rep, " + warmupRounds
          + " interleaved warmup rounds (>= " + ArbitrageBench.MIN_WARMUP_SCANS
          + " scans/candidate); timed unit = build + detect (reconstruction untimed)");
      Map<String, String> problems = new LinkedHashMap<>();
      Map<String, Interleaved.Trial> trials = new LinkedHashMap<>();
      Map<String, String> notes = new LinkedHashMap<>();
      for (Map.Entry<String, ArbitrageBench.Factory> e : candidates.entrySet()) {
        GraphOps probe = e.getValue().create();
        String problem = ArbitrageBench.verify(probe, w, referenceHasCycle);
        problems.put(e.getKey(), problem);
        trials.put(e.getKey(), ArbitrageBench.trial(e.getValue(), w, perRep));
        notes.put(e.getKey(), w.label);
        anyFailed |= problem != null;
      }
      final int fReps = reps;
      timeAndWrite("arbitrage", problems, trials, notes, w.edgeFrom.length, w.seed,
          warmupRounds, reps, alloc, perRep, Math.max(1, w.edgeFrom.length),
          (name, why) -> ArbitrageBench.failed("arbitrage", name, w, fReps, why), csv);
    }

    System.out.println();
    System.out.println("note: BenchResult's n/ops-per-sec columns are repurposed here as edge"
        + " count E / edge-relaxations-per-second (not an order-flow op count like the ladder"
        + " bench) — see benchmark/README.md.");
    System.out.println(anyFailed
        ? "RESULT: FAILURES PRESENT (a structure disagreed with the reference verdict)"
        : "RESULT: all candidates validated against the reference and timed OK");
    if (anyFailed) {
      System.exit(1);
    }
  }

  /** Queue bench: ./run.sh queue [--n N] [--seed S] [--reps R] [--warmup W]
   *  [--profile shallow|deep] [--out FILE] */
  private static void runQueue(String[] args) throws IOException {
    int n = 300_000;
    long seed = 42;
    int reps = 7;
    int warmup = 3;
    String profile = "shallow";
    String outPath = "results/raw_results.csv";
    for (int i = 1; i + 1 < args.length; i += 2) {
      String k = args[i];
      String v = args[i + 1];
      switch (k) {
        case "--n": n = Integer.parseInt(v); break;
        case "--seed": seed = Long.parseLong(v); break;
        case "--reps": reps = Integer.parseInt(v); break;
        case "--warmup": warmup = Integer.parseInt(v); break;
        case "--profile": profile = v; break;
        case "--out": outPath = v; break;
        default:
          usage();
          System.exit(2);
      }
    }

    final long fSeed = seed;
    QueueWorkload w = QueueWorkload.generate(n, fSeed, profile);
    System.out.println("workload: " + w.describe());

    // Oracle: the correctness gate
    LinkedHashMapOracle oracle = new LinkedHashMapOracle();
    long oracleChecksum = QueueBench.replay(oracle, w);
    TreeMap<Long, Long> oracleState = QueueBench.snapshot(oracle, w.n);
    System.out.println("oracle: checksum=0x" + Long.toHexString(oracleChecksum)
        + " liveOrders=" + oracleState.size());

    Map<String, QueueBench.Factory> candidates = new LinkedHashMap<>();
    candidates.put("oracle-linkedMap", LinkedHashMapOracle::new);
    candidates.put("array", ArrayQueueOps::new);
    candidates.put("singly-list", SinglyListQueueOps::new);
    candidates.put("dll-no-index", DllQueueOps::new);
    candidates.put("tree-by-id", () -> new TreeQueueOps(fSeed ^ 0x2545F4914F6CDD1DL));
    candidates.put("dll+hash", DllHashQueueOps::new);

    AllocCounter alloc = new AllocCounter();
    System.out.println("alloc counter: " + (alloc.isEnabled() ? "enabled" : "UNAVAILABLE"));
    System.out.println();
    System.out.println(BenchResult.header());

    boolean anyFailed = false;
    Path out = Paths.get(outPath);
    if (out.getParent() != null) {
      Files.createDirectories(out.getParent());
    }
    boolean needHeader = !Files.exists(out) || Files.size(out) == 0;

    try (BufferedWriter csv = Files.newBufferedWriter(out, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
      if (needHeader) {
        csv.write(BenchResult.csvHeader());
        csv.newLine();
      }
      Map<String, String> problems = new LinkedHashMap<>();
      Map<String, Interleaved.Trial> trials = new LinkedHashMap<>();
      for (Map.Entry<String, QueueBench.Factory> e : candidates.entrySet()) {
        String problem = QueueBench.verify(e.getValue().create(), w, oracleChecksum, oracleState);
        problems.put(e.getKey(), problem);
        trials.put(e.getKey(), QueueBench.trial(e.getValue(), w));
        anyFailed |= problem != null;
      }
      final int fReps = reps;
      Map<String, String> notes = new LinkedHashMap<>();
      for (String name : candidates.keySet()) notes.put(name, "profile=" + w.profile);
      timeAndWrite("queue", problems, trials, notes, w.n, w.seed, warmup, reps,
          alloc, 1, w.n, (name, why) -> QueueBench.failed("queue", name, w, fReps, why), csv);
    }

    System.out.println();
    System.out.println(anyFailed
        ? "RESULT: FAILURES PRESENT (a structure disagreed with the oracle)"
        : "RESULT: all candidates validated against the oracle and timed OK");
    if (anyFailed) {
      System.exit(1);
    }
  }

  /** Cache bench: ./run.sh cache [--n N] [--seed S] [--reps R] [--warmup W]
   *  [--universe U] [--capacity C] [--zipf S] [--out FILE] */
  private static void runCache(String[] args) throws IOException {
    int n = 300_000;
    long seed = 42;
    int reps = 7;
    int warmup = 3;
    int universe = 100_000;
    int capacity = 10_000;
    double zipfS = 1.0;
    String outPath = "results/raw_results.csv";
    for (int i = 1; i + 1 < args.length; i += 2) {
      String k = args[i];
      String v = args[i + 1];
      switch (k) {
        case "--n": n = Integer.parseInt(v); break;
        case "--seed": seed = Long.parseLong(v); break;
        case "--reps": reps = Integer.parseInt(v); break;
        case "--warmup": warmup = Integer.parseInt(v); break;
        case "--universe": universe = Integer.parseInt(v); break;
        case "--capacity": capacity = Integer.parseInt(v); break;
        case "--zipf": zipfS = Double.parseDouble(v); break;
        case "--out": outPath = v; break;
        default:
          usage();
          System.exit(2);
      }
    }

    final int fCapacity = capacity;
    CacheWorkload w = CacheWorkload.generate(n, seed, universe, zipfS, capacity);
    System.out.println("workload: " + w.describe());

    // Oracle: the correctness gate (bounded LRU reference)
    LruReferenceOracle oracle = new LruReferenceOracle(capacity);
    long oracleChecksum = CacheBench.replay(oracle, w);
    System.out.println("oracle: checksum=0x" + Long.toHexString(oracleChecksum)
        + " resident=" + oracle.size());

    Map<String, CacheBench.Factory> candidates = new LinkedHashMap<>();
    candidates.put("oracle-linkedMapLRU", () -> new LruReferenceOracle(fCapacity));
    candidates.put("hash-chaining", ChainHashCache::new);
    candidates.put("hash-open", OpenHashCache::new);
    candidates.put("hash+LRU", () -> new LruHashCache(fCapacity));
    candidates.put("splay", SplayCache::new);

    AllocCounter alloc = new AllocCounter();
    System.out.println("alloc counter: " + (alloc.isEnabled() ? "enabled" : "UNAVAILABLE"));
    System.out.println();
    System.out.println(BenchResult.header());

    boolean anyFailed = false;
    Path out = Paths.get(outPath);
    if (out.getParent() != null) {
      Files.createDirectories(out.getParent());
    }
    boolean needHeader = !Files.exists(out) || Files.size(out) == 0;

    try (BufferedWriter csv = Files.newBufferedWriter(out, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
      if (needHeader) {
        csv.write(BenchResult.csvHeader());
        csv.newLine();
      }
      Map<String, String> problems = new LinkedHashMap<>();
      Map<String, Interleaved.Trial> trials = new LinkedHashMap<>();
      Map<String, String> notes = new LinkedHashMap<>();
      for (Map.Entry<String, CacheBench.Factory> e : candidates.entrySet()) {
        String name = e.getKey();
        CacheOps probe = e.getValue().create();
        String problem = CacheBench.verifyBalance(probe, w, oracleChecksum);
        if (problem == null) {
          if (name.equals("hash+LRU")) {
            problem = CacheBench.verifyLruResidency(new LruHashCache(fCapacity), w);
          } else if (!name.equals("oracle-linkedMapLRU")) {
            problem = CacheBench.verifyUnboundedResidency(probe, w);
          }
        }
        problems.put(name, problem);
        trials.put(name, CacheBench.trial(e.getValue(), w));
        notes.put(name, "finalSize=" + probe.size() + " capacity=" + capacity);
        anyFailed |= problem != null;
      }
      final int fReps = reps;
      timeAndWrite("cache", problems, trials, notes, w.n, w.seed, warmup, reps, alloc, 1, w.n,
          (name, why) -> CacheBench.failed("cache", name, w, fReps, why), csv);
    }

    System.out.println();
    System.out.println(anyFailed
        ? "RESULT: FAILURES PRESENT (a structure disagreed with the oracle)"
        : "RESULT: all candidates validated against the oracle and timed OK");
    if (anyFailed) {
      System.exit(1);
    }
  }

  /** Ticker bench: ./run.sh ticker [--n N] [--seed S] [--reps R] [--warmup W] [--out FILE] */
  private static void runTicker(String[] args) throws IOException {
    int n = 300_000;
    long seed = 42;
    int reps = 7;
    int warmup = 3;
    String outPath = "results/raw_results.csv";
    for (int i = 1; i + 1 < args.length; i += 2) {
      String k = args[i];
      String v = args[i + 1];
      switch (k) {
        case "--n": n = Integer.parseInt(v); break;
        case "--seed": seed = Long.parseLong(v); break;
        case "--reps": reps = Integer.parseInt(v); break;
        case "--warmup": warmup = Integer.parseInt(v); break;
        case "--out": outPath = v; break;
        default:
          usage();
          System.exit(2);
      }
    }

    final long fSeed = seed;
    AllocCounter alloc = new AllocCounter();
    boolean anyFailed = false;
    Path out = Paths.get(outPath);
    if (out.getParent() != null) {
      Files.createDirectories(out.getParent());
    }
    boolean needHeader = !Files.exists(out) || Files.size(out) == 0;

    try (BufferedWriter csv = Files.newBufferedWriter(out, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
      if (needHeader) {
        csv.write(BenchResult.csvHeader());
        csv.newLine();
      }

      // ---- Phase 1: exact-match workload (insert + contains) — every candidate's fair shot
      System.out.println("=== phase 1: exact-match workload (insert + contains) ===");
      TickerWorkload exactW = TickerWorkload.generate(n, fSeed, "exact");
      System.out.println("workload: " + exactW.describe());
      LinearListOracle exactOracle = new LinearListOracle();
      long exactChecksum = TickerBench.replay(exactOracle, exactW);
      System.out.println("oracle: checksum=0x" + Long.toHexString(exactChecksum)
          + " registry=" + exactOracle.size());
      System.out.println("alloc counter: " + (alloc.isEnabled() ? "enabled" : "UNAVAILABLE"));
      System.out.println();
      System.out.println(BenchResult.header());

      Map<String, TickerBench.Factory> exactCandidates = new LinkedHashMap<>();
      exactCandidates.put("oracle-linearScan", LinearListOracle::new);
      exactCandidates.put("hash-set", HashSetTicker::new);
      exactCandidates.put("sorted-array", SortedArrayTicker::new);
      exactCandidates.put("tst", TstTicker::new);
      exactCandidates.put("trie", TrieTicker::new);

      final int fReps = reps;
      Map<String, String> exactProblems = new LinkedHashMap<>();
      Map<String, Interleaved.Trial> exactTrials = new LinkedHashMap<>();
      Map<String, String> exactNotes = new LinkedHashMap<>();
      for (Map.Entry<String, TickerBench.Factory> e : exactCandidates.entrySet()) {
        String problem = TickerBench.verify(e.getValue().create(), exactW, exactChecksum);
        exactProblems.put(e.getKey(), problem);
        exactTrials.put(e.getKey(), TickerBench.trial(e.getValue(), exactW));
        exactNotes.put(e.getKey(), "mode=exact");
        anyFailed |= problem != null;
      }
      timeAndWrite("ticker", exactProblems, exactTrials, exactNotes, exactW.n, exactW.seed,
          warmup, reps, alloc, 1, exactW.n,
          (name, why) -> TickerBench.failed("ticker", name, exactW, fReps, why), csv);

      // ---- Phase 2: prefix workload — autocomplete is the reason this subsystem exists.
      // hash-set is ATTEMPTED here, not pre-excluded: its structural refusal surfaces as an
      // explicit N/A row (doc 05 §5's rubric-limitation note, made undeniable).
      System.out.println();
      System.out.println("=== phase 2: prefix workload (insert + contains + prefixSearch) ===");
      TickerWorkload prefixW = TickerWorkload.generate(n, fSeed, "prefix");
      System.out.println("workload: " + prefixW.describe());
      LinearListOracle prefixOracle = new LinearListOracle();
      long prefixChecksum = TickerBench.replay(prefixOracle, prefixW);
      System.out.println("oracle: checksum=0x" + Long.toHexString(prefixChecksum)
          + " registry=" + prefixOracle.size());
      System.out.println();
      System.out.println(BenchResult.header());

      Map<String, TickerBench.Factory> prefixCandidates = new LinkedHashMap<>();
      prefixCandidates.put("oracle-linearScan", LinearListOracle::new);
      prefixCandidates.put("hash-set", HashSetTicker::new);
      prefixCandidates.put("sorted-array", SortedArrayTicker::new);
      prefixCandidates.put("tst", TstTicker::new);
      prefixCandidates.put("trie", TrieTicker::new);

      Map<String, String> prefixProblems = new LinkedHashMap<>();
      Map<String, Interleaved.Trial> prefixTrials = new LinkedHashMap<>();
      Map<String, String> prefixNotes = new LinkedHashMap<>();
      List<String> unsupportedNames = new ArrayList<>();
      for (Map.Entry<String, TickerBench.Factory> e : prefixCandidates.entrySet()) {
        String name = e.getKey();
        String problem = null;
        try {
          problem = TickerBench.verify(e.getValue().create(), prefixW, prefixChecksum);
        } catch (UnsupportedOperationException structural) {
          unsupportedNames.add(name); // reported as an explicit N/A row below, never timed
          continue;
        } catch (RuntimeException ex) {
          problem = "threw " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
        prefixProblems.put(name, problem);
        prefixTrials.put(name, TickerBench.trial(e.getValue(), prefixW));
        prefixNotes.put(name, "mode=prefix");
        anyFailed |= problem != null;
      }
      timeAndWrite("ticker", prefixProblems, prefixTrials, prefixNotes, prefixW.n, prefixW.seed,
          warmup, reps, alloc, 1, prefixW.n,
          (name, why) -> TickerBench.failed("ticker", name, prefixW, fReps, why), csv);
      for (String name : unsupportedNames) {
        System.out.println(String.format("%-16s %10s %12s %12s %9s %10s %6s  %s",
            name, "-", "-", "-", "-", "-", "N/A",
            "prefixSearch structurally unsupported (hash set: no prefix ordering exists)"));
        csv.write("ticker," + name + "," + n + "," + fSeed + "," + reps
            + ",na,0,0,0,0,0,-1,mode=prefix prefixSearch structurally unsupported");
        csv.newLine();
      }
    }

    System.out.println();
    System.out.println(anyFailed
        ? "RESULT: FAILURES PRESENT (a structure disagreed with the oracle)"
        : "RESULT: all candidates validated against the oracle and timed OK"
            + " (hash-set N/A on phase 2 is structural incapability, not a failure)");
    if (anyFailed) {
      System.exit(1);
    }
  }

  /** Compression bench: ./run.sh compression [--seed S] [--reps R] [--warmup W] [--out FILE] */
  private static void runCompression(String[] args) throws IOException {
    long seed = 42;
    int reps = 7;
    int warmup = 3;
    String outPath = "results/raw_results.csv";
    for (int i = 1; i + 1 < args.length; i += 2) {
      String k = args[i];
      String v = args[i + 1];
      switch (k) {
        case "--seed": seed = Long.parseLong(v); break;
        case "--reps": reps = Integer.parseInt(v); break;
        case "--warmup": warmup = Integer.parseInt(v); break;
        case "--out": outPath = v; break;
        default:
          usage();
          System.exit(2);
      }
    }

    LogWorkload w = LogWorkload.generate(seed);
    System.out.println("workload: " + w.describe());
    System.out.println("gate: round-trip identity decode(encode(x)) == x, byte-for-byte, per batch");
    System.out.println();

    AllocCounter alloc = new AllocCounter();
    System.out.println("alloc counter: " + (alloc.isEnabled() ? "enabled" : "UNAVAILABLE"));
    System.out.println();
    System.out.println(CompressionResult.header());

    Map<String, CompressionBench.Factory> candidates = new LinkedHashMap<>();
    candidates.put("rle", RleCompressor::new);
    candidates.put("lzw", LzwCompressor::new);
    candidates.put("huffman", HuffmanCompressor::new);
    candidates.put("arithmetic", ArithmeticCompressor::new);

    boolean anyFailed = false;
    Path out = Paths.get(outPath);
    if (out.getParent() != null) {
      Files.createDirectories(out.getParent());
    }
    boolean needHeader = !Files.exists(out) || Files.size(out) == 0;

    try (BufferedWriter csv = Files.newBufferedWriter(out, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
      if (needHeader) {
        csv.write(BenchResult.csvHeader());
        csv.newLine();
      }
      for (Map.Entry<String, CompressionBench.Factory> e : candidates.entrySet()) {
        String name = e.getKey();
        String problem = null;
        try {
          problem = CompressionBench.verify(e.getValue().create(), w);
        } catch (RuntimeException ex) {
          problem = "threw " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
        if (problem != null) {
          anyFailed = true;
          CompressionResult r = CompressionResult.failed(name, w.totalInputBytes, problem);
          System.out.println(r.row());
          csv.write("compression," + name + "," + w.totalInputBytes + "," + seed + "," + reps
              + ",FAIL,0,0,0,0,0,-1," + problem.replace(',', ';'));
          csv.newLine();
          continue;
        }
        CompressionResult r = CompressionBench.timed(name, e.getValue(), w, warmup, reps, alloc);
        System.out.println(r.row());
        // The raw CSV keeps its single schema via two adapter rows per candidate
        // (n = input bytes; ops/sec column = bytes/sec — documented repurposing, as in
        // the arbitrage and ticker benches). The console uses CompressionResult instead,
        // because folding ratio + two-phase timings into BenchResult would mislead.
        BenchResult encRow = new BenchResult("compression", name, (int) w.totalInputBytes,
            seed, reps, true, r.encodeMinMs, r.encodeMedianMs, r.encodeMaxMs,
            r.allocPerInputByte, "phase=encode bytes/sec ratio=" + r.ratio);
        BenchResult decRow = new BenchResult("compression", name, (int) w.totalInputBytes,
            seed, reps, true, r.decodeMinMs, r.decodeMedianMs, r.decodeMaxMs, -1.0,
            "phase=decode bytes/sec");
        csv.write(encRow.csv());
        csv.newLine();
        csv.write(decRow.csv());
        csv.newLine();
      }
    }

    System.out.println();
    System.out.println(anyFailed
        ? "RESULT: FAILURES PRESENT (a round-trip identity check failed)"
        : "RESULT: all candidates round-tripped byte-for-byte and were timed OK");
    if (anyFailed) {
      System.exit(1);
    }
  }
}
