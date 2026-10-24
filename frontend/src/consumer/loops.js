// The arithmetic behind a loop. The engine tracks one cash balance per account, not a wallet
// per currency, so a loop cannot be executed with real orders. Cambio shows it "on paper":
// the same conversions the scanner priced, with the user's starting amount, at current quotes.

/** The legs of a closed cycle [A, B, C, A] with their rates from the graph's edges, or null if one is missing. */
export function legsFromCycle(cycle, edges) {
  if (!Array.isArray(cycle) || cycle.length < 3) return null;
  const legs = [];
  for (let i = 0; i < cycle.length - 1; i += 1) {
    const from = cycle[i];
    const to = cycle[i + 1];
    const e = (edges ?? []).find((x) => x.from === from && x.to === to);
    if (!e || !(e.rate > 0)) return null;
    legs.push({ from, to, rate: e.rate, stale: Boolean(e.stale) });
  }
  return legs;
}

/** Walks the legs with a starting amount. Quotes only: no fees, no waiting, no price movement. */
export function paperRun(amount, legs) {
  let current = amount;
  const steps = legs.map((leg) => {
    const out = current * leg.rate;
    const step = { ...leg, amountIn: current, amountOut: out };
    current = out;
    return step;
  });
  return { steps, end: current, gain: current - amount, percent: amount > 0 ? (current / amount - 1) * 100 : 0 };
}

/** The product of the legs' rates: above 1 means the loop returns more than it started with. */
export function multiplierOf(legs) {
  return legs.reduce((m, l) => m * l.rate, 1);
}
