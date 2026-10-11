package ads.bench.ticker;

import ads.bench.core.Rng;

/**
 * DATA (Phase 1): one deterministic ticker-registry session log.
 *
 * <p><b>Universe:</b> 12 currencies (USD EUR GBP JPY AUD CAD CHF INR CNY HKD SGD NZD — the
 * first 8 matching doc 06 §4's roster) quoted in every direction: 12×11 = 132 pairs of the
 * form "AAA/BBB" — the "tens to low hundreds of pairs" scale doc 05 §5 names.
 *
 * <p><b>Shape:</b> the registry loads first (132 INSERTs in a seeded shuffled order), then
 * the session's queries run:
 * <ul>
 *   <li>mode {@code "exact"}: 100% CONTAINS — the workload a hash set CAN serve, so it gets
 *       a fair timed baseline before its structural gap is demonstrated;</li>
 *   <li>mode {@code "prefix"}: 65% CONTAINS / 35% PREFIX — autocomplete fires on every
 *       keystroke (which is exactly why this subsystem exists), while contains models
 *       submit-time validation. About 60% of contains targets are registered pairs and
 *       ~40% are synthetic "AAA/BBB" strings that do not exist (typos / delisted pairs), so
 *       the negative path is exercised. Prefix lengths are 1..6 (length 0 would mean "show
 *       everything" and real autocomplete never fires before the first character); ~75% of
 *       prefixes come from a registered ticker, the rest from random letter runs that often
 *       match nothing (empty-result path).</li>
 * </ul>
 */
public final class TickerWorkload {
  public static final byte INSERT = 0;
  public static final byte CONTAINS = 1;
  public static final byte PREFIX = 2;

  private static final String[] CURRENCIES =
      {"USD", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "INR", "CNY", "HKD", "SGD", "NZD"};
  private static final int ABSENT_POOL = 64;

  public final int n;
  public final long seed;
  public final String mode;
  public final String[] universe; // the full roster; every query runs after the load phase
  public final byte[] op;
  public final String[] arg; // ticker for INSERT/CONTAINS; prefix for PREFIX
  public final double avgPrefixMatches; // mean match count over the PREFIX ops (0 in exact mode)

  private TickerWorkload(int n, long seed, String mode, String[] universe, byte[] op,
      String[] arg, double avgPrefixMatches) {
    this.n = n;
    this.seed = seed;
    this.mode = mode;
    this.universe = universe;
    this.op = op;
    this.arg = arg;
    this.avgPrefixMatches = avgPrefixMatches;
  }

  public static TickerWorkload generate(int n, long seed, String mode) {
    if (!mode.equals("exact") && !mode.equals("prefix")) {
      throw new IllegalArgumentException("mode must be exact|prefix: " + mode);
    }
    String[] universe = new String[CURRENCIES.length * (CURRENCIES.length - 1)];
    int u = 0;
    for (int i = 0; i < CURRENCIES.length; i++) {
      for (int j = 0; j < CURRENCIES.length; j++) {
        if (i != j) universe[u++] = CURRENCIES[i] + "/" + CURRENCIES[j];
      }
    }
    if (n < universe.length) {
      throw new IllegalArgumentException("n must be >= universe size " + universe.length);
    }
    Rng rng = new Rng(seed);
    byte[] op = new byte[n];
    String[] arg = new String[n];

    // load phase: INSERT the roster in a seeded shuffle (deterministic; TST shape depends on it)
    String[] shuffled = universe.clone();
    for (int i = shuffled.length - 1; i > 0; i--) {
      int j = rng.nextInt(i + 1);
      String t = shuffled[i];
      shuffled[i] = shuffled[j];
      shuffled[j] = t;
    }
    for (int i = 0; i < universe.length; i++) {
      op[i] = INSERT;
      arg[i] = shuffled[i];
    }
    // pool of synthetic absent tickers (typos / delisted pairs)
    String[] absent = new String[ABSENT_POOL];
    for (int i = 0; i < absent.length; i++) {
      String a;
      String b;
      do {
        a = randomCode(rng);
        b = randomCode(rng);
      } while (inRoster(a) && inRoster(b) && !a.equals(b)); // must NOT be a real pair
      absent[i] = a + "/" + b;
    }

    long prefixMatchSum = 0;
    int prefixOps = 0;
    for (int i = universe.length; i < n; i++) {
      boolean doPrefix;
      if (mode.equals("exact")) {
        doPrefix = false;
      } else {
        doPrefix = rng.nextInt(1000) >= 650; // 65% contains / 35% prefix
      }
      if (!doPrefix) {
        op[i] = CONTAINS;
        arg[i] = rng.nextInt(100) < 60
            ? universe[rng.nextInt(universe.length)]
            : absent[rng.nextInt(absent.length)];
      } else {
        op[i] = PREFIX;
        String p;
        if (rng.nextInt(100) < 75) {
          String t = universe[rng.nextInt(universe.length)];
          p = t.substring(0, 1 + rng.nextInt(6)); // 1..6 chars of a real ticker
        } else {
          int len = 1 + rng.nextInt(3);
          char[] cs = new char[len];
          for (int k = 0; k < len; k++) {
            cs[k] = (char) ('A' + rng.nextInt(26));
          }
          p = new String(cs);
        }
        arg[i] = p;
        int hits = 0;
        for (String t : universe) {
          if (t.startsWith(p)) hits++;
        }
        prefixMatchSum += hits;
        prefixOps++;
      }
    }
    double avg = prefixOps == 0 ? 0.0 : (double) prefixMatchSum / prefixOps;
    return new TickerWorkload(n, seed, mode, universe, op, arg, avg);
  }

  private static String randomCode(Rng rng) {
    char[] cs = new char[3];
    for (int k = 0; k < 3; k++) {
      cs[k] = (char) ('A' + rng.nextInt(26));
    }
    return new String(cs);
  }

  private static boolean inRoster(String code) {
    for (String c : CURRENCIES) {
      if (c.equals(code)) return true;
    }
    return false;
  }

  /** Human-readable summary of the session (for logs). */
  public String describe() {
    int ins = 0, con = 0, pre = 0;
    for (byte o : op) {
      if (o == INSERT) ins++;
      else if (o == CONTAINS) con++;
      else pre++;
    }
    return String.format(
        "n=%d seed=%d mode=%s | universe=%d | insert=%d contains=%d prefix=%d"
            + " avgPrefixMatches=%.1f",
        n, seed, mode, universe.length, ins, con, pre, avgPrefixMatches);
  }
}
