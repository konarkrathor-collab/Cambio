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
import ads.bench.core.AllocCounter;
import ads.bench.core.BenchResult;
import ads.bench.ladder.FlatArrayLadder;
import ads.bench.ladder.LadderBench;
import ads.bench.ladder.LadderWorkload;
import ads.bench.ladder.RadixLadder;
import ads.bench.ladder.SkipListLadder;
import ads.bench.ladder.SortedArrayLadder;
import ads.bench.ladder.TreeMapLadder;
import ads.bench.ladder.UnsortedArrayLadder;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** CLI entry point. Example: ./run.sh ladder --n 300000 --seed 42 --reps 7 --warmup 3 */
public final class Main {
  private static void usage() {
    System.out.println("usage: run.sh ladder [--n N] [--seed S] [--reps R] [--warmup W]"
        + " [--base B] [--range R] [--band H] [--pattern walk] [--out FILE]");
    System.out.println("   or: run.sh arbitrage [--graph real|synthetic] [--v V]"
        + " [--inject true|false] [--seed S] [--reps R] [--warmup W] [--out FILE]");
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
    if (!args[0].equals("ladder")) {
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
    String outPath = "results/raw_results.csv";

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

    Map<String, LadderBench.Factory> candidates = new LinkedHashMap<>();
    candidates.put("oracle-treeMap", TreeMapLadder::new);
    candidates.put("unsorted-array", () -> new UnsortedArrayLadder(w.n));
    candidates.put("sorted-array", () -> new SortedArrayLadder(w.n));
    candidates.put("flat-array", () -> new FlatArrayLadder(fBase, fRange));
    candidates.put("skip-list", () -> new SkipListLadder(fSeed ^ 0x5DEECE66DL));
    candidates.put("radix16", RadixLadder::new);

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
      for (Map.Entry<String, LadderBench.Factory> e : candidates.entrySet()) {
        String name = e.getKey();
        String problem = LadderBench.verify(e.getValue().create(), w, oracleChecksum, oracleState);
        BenchResult r;
        if (problem != null) {
          anyFailed = true;
          r = LadderBench.failed("ladder", name, w, reps, problem);
        } else {
          r = LadderBench.timed("ladder", name, e.getValue(), w, warmup, reps, alloc, "");
        }
        System.out.println(r.row());
        csv.write(r.csv());
        csv.newLine();
      }
    }

    System.out.println();
    System.out.println(anyFailed
        ? "RESULT: FAILURES PRESENT (a structure disagreed with the oracle)"
        : "RESULT: all candidates validated against the oracle and timed OK");
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
      for (Map.Entry<String, ArbitrageBench.Factory> e : candidates.entrySet()) {
        String name = e.getKey();
        GraphOps probe = e.getValue().create();
        String problem = ArbitrageBench.verify(probe, w, referenceHasCycle);
        BenchResult r;
        if (problem != null) {
          anyFailed = true;
          r = ArbitrageBench.failed("arbitrage", name, w, reps, problem);
        } else {
          r = ArbitrageBench.timed("arbitrage", name, e.getValue(), w, warmup, reps, alloc);
        }
        System.out.println(r.row());
        csv.write(r.csv());
        csv.newLine();
      }
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
}
