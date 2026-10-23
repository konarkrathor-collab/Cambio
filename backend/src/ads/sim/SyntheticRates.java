package ads.sim;

import ads.scanner.ArbitrageScanner;
import ads.util.SplitMix64;

/**
 * Synthetic rates for the simulator's scans. Every currency's USD rate starts at a realistic
 * level and takes a random-walk step of up to {@code rateVolatility} percent (uniform, either
 * way) before each scan; then every one of the 66 currency pairs gets a direct quote equal to
 * the cross of those USD rates times its own fresh jitter of up to the same percentage. The
 * walk keeps the levels moving; the per-quote jitter is what opens (and closes) loopholes,
 * so a volatility of 0 gives a consistent rate set with none. Rates are binary floating
 * point by design, like the live feed's; no price in the order books comes from here.
 */
final class SyntheticRates {
  private static final String[] CODES =
      {"USD", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "INR", "CNY", "HKD", "SGD", "NZD"};
  private static final double[] PER_USD =
      {1.0, 0.92593, 0.76923, 156.87, 1.5152, 1.3700, 0.8800, 96.73, 7.2500, 7.8000, 1.3500, 1.6600};

  private final ArbitrageScanner scanner;
  private final SplitMix64 rng;
  private final double step;     // volatility as a fraction
  private final double[] perUsd; // units of currency i per USD, by scanner id

  SyntheticRates(ArbitrageScanner scanner, long seed, double volatilityPercent) {
    this.scanner = scanner;
    this.rng = new SplitMix64(seed ^ 0x5DEECE66DL); // a stream of its own: rates never shift the orders
    this.step = volatilityPercent / 100.0;
    int n = scanner.currencyCount();
    perUsd = new double[n];
    for (int i = 0; i < n; i++) {
      perUsd[i] = 1.0;
      for (int k = 0; k < CODES.length; k++) {
        if (CODES[k].equals(scanner.currency(i))) perUsd[i] = PER_USD[k];
      }
    }
  }

  /** One step: move the USD levels, then quote every pair to the scanner. */
  void step() {
    int n = perUsd.length;
    int usd = scanner.currencyId("USD");
    for (int i = 0; i < n; i++) {
      if (i != usd) perUsd[i] *= 1.0 + step * uniform();
    }
    for (int a = 0; a < n; a++) {
      for (int b = a + 1; b < n; b++) {
        scanner.onTick(a, b, perUsd[b] / perUsd[a] * (1.0 + step * uniform()));
      }
    }
  }

  /** Uniform in [-1, 1). */
  private double uniform() {
    return (rng.nextLong() >>> 11) * 0x1.0p-52 - 1.0;
  }
}
