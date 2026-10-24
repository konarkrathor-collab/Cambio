// Presentation helpers for motion (DESIGN.md §6). Pure functions: no DOM, no timers.

/** Ease-out matching --ease-out (a quintic-like curve), t in [0, 1]. */
export function easeOut(t) {
  const c = Math.min(1, Math.max(0, t));
  return 1 - (1 - c) ** 4;
}

/** True when the user asked for reduced motion (false where matchMedia does not exist). */
export function prefersReducedMotion() {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/**
 * The cumulative volume a depth series shows at `price`. Both series are in ascending price
 * order (lib/depth.js depthSeries). A bid's total holds from its price down to the next
 * lower level, so the value at p is that of the lowest bid priced at or above p (0 above
 * the best bid). An ask's total holds from its price up, so the value at p is that of the
 * highest ask priced at or below p (0 below the best ask).
 */
export function depthAt(series, side, price) {
  if (series.length === 0) return 0;
  if (side === 'bids') {
    for (const p of series) if (p.price >= price) return p.cumulative;
    return 0;
  }
  let v = 0;
  for (const p of series) {
    if (p.price <= price) v = p.cumulative;
    else break;
  }
  return v;
}

const lerp = (a, b, t) => a + (b - a) * t;

/**
 * One frame of the depth chart's morph from `from` to `to` at progress t (0..1). A frame is
 * { lo, hi, maxY, bids, asks }. Each side is sampled on the union of both frames' prices,
 * so t = 0 draws exactly `from` and t = 1 exactly `to`; in between every step slides.
 */
export function depthFrame(from, to, t) {
  if (!from || t >= 1) return to;
  const side = (k) => {
    const prices = [...new Set([...from[k], ...to[k]].map((p) => p.price))].sort((a, b) => a - b);
    return prices.map((price) => ({
      price,
      cumulative: lerp(depthAt(from[k], k, price), depthAt(to[k], k, price), t),
    }));
  };
  return {
    lo: lerp(from.lo, to.lo, t),
    hi: lerp(from.hi, to.hi, t),
    maxY: lerp(from.maxY, to.maxY, t),
    bids: side('bids'),
    asks: side('asks'),
  };
}
