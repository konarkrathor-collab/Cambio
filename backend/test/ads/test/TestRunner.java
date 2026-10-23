package ads.test;

/**
 * Plain-Java test runner (no JUnit): runs every check, prints one PASS/FAIL line each,
 * and exits non-zero if anything failed. {@code --timing} additionally runs the
 * production-vs-benchmark timing comparison once every gate has passed.
 */
public final class TestRunner {
  /** One check's outcome: a pass with an optional detail, or a failure with the reason. */
  static final class Result {
    final boolean ok;
    final String message;

    private Result(boolean ok, String message) {
      this.ok = ok;
      this.message = message;
    }

    static Result pass(String detail) {
      return new Result(true, detail);
    }

    static Result fail(String why) {
      return new Result(false, why);
    }

    /** Wraps a benchmark gate's verdict: null means OK, anything else is the problem. */
    static Result gate(String problem, String detail) {
      return problem == null ? pass(detail) : fail(problem);
    }
  }

  interface Check {
    Result run() throws Exception;
  }

  private int passed;
  private int failed;

  void section(String title) {
    System.out.println();
    System.out.println("== " + title + " ==");
  }

  void check(String name, Check c) {
    long t0 = System.nanoTime();
    Result r;
    try {
      r = c.run();
    } catch (Throwable t) {
      r = Result.fail("threw " + t);
    }
    long ms = (System.nanoTime() - t0) / 1_000_000;
    String msg = r.message == null || r.message.isEmpty() ? "" : "  " + r.message;
    System.out.println((r.ok ? "PASS  " : "FAIL  ") + name + "  (" + ms + " ms)" + msg);
    if (r.ok) passed++;
    else failed++;
  }

  public static void main(String[] args) throws Exception {
    boolean timing = false;
    for (String a : args) {
      if (a.equals("--timing")) {
        timing = true;
      } else {
        System.err.println("usage: test.sh [--timing]");
        System.exit(2);
      }
    }
    TestRunner r = new TestRunner();
    ParityTests.run(r);
    UnitTests.run(r);
    EngineTests.run(r);
    ServiceTests.run(r);
    System.out.println();
    System.out.println("RESULT: " + r.passed + " passed, " + r.failed + " failed");
    if (r.failed > 0) {
      System.exit(1);
    }
    if (timing) {
      TimingComparison.run();
    }
  }
}
