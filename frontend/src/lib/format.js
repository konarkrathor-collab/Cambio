// Number and price formatting. Prices arrive as JSON numbers (contract §1); they are always
// shown with exactly the pair's tick decimals, never as a raw float.

const EMPTY = '—';

/** Decimal places of a tick size given as a number (0.00001 -> 5, 0.001 -> 3, 1 -> 0). */
export function tickDecimals(tickSize) {
  if (typeof tickSize !== 'number' || !(tickSize > 0) || !Number.isFinite(tickSize)) return 5;
  const d = Math.round(-Math.log10(tickSize));
  return Math.min(Math.max(d, 0), 12);
}

/** A price with the pair's tick decimals: formatPrice(1.0851, 0.00001) === "1.08510". */
export function formatPrice(price, tickSize) {
  if (price === null || price === undefined || price === '') return EMPTY;
  const n = typeof price === 'string' ? Number(price) : price;
  if (!Number.isFinite(n)) return EMPTY;
  return n.toFixed(tickDecimals(tickSize));
}

/** The tick size itself, written with its own decimals: 0.00001 -> "0.00001". */
export function formatTickSize(tickSize) {
  return formatPrice(tickSize, tickSize);
}

const intFormat = new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 });

/** An integer with thousands separators; non-integers are rounded for display only. */
export function formatInt(n) {
  if (n === null || n === undefined || !Number.isFinite(n)) return EMPTY;
  return intFormat.format(Math.round(n));
}

/** A signed integer: "+1,200" / "-300" / "0". */
export function formatSignedInt(n) {
  if (n === null || n === undefined || !Number.isFinite(n)) return EMPTY;
  return (n > 0 ? '+' : '') + formatInt(n);
}

/** A percentage with fixed decimals: formatPercent(0.39972222) === "0.3997%". */
export function formatPercent(p, digits = 4) {
  if (p === null || p === undefined || !Number.isFinite(p)) return EMPTY;
  return `${p.toFixed(digits)}%`;
}

/** Microseconds with three decimals (the simulator's resolution). */
export function formatMicros(us) {
  if (us === null || us === undefined || !Number.isFinite(us)) return EMPTY;
  return `${us.toFixed(3)} µs`;
}

/** Milliseconds with three decimals. */
export function formatMillis(ms) {
  if (ms === null || ms === undefined || !Number.isFinite(ms)) return EMPTY;
  return `${ms.toFixed(3)} ms`;
}

/** A cycle multiplier: 1.0039972 -> "1.003997". */
export function formatMultiplier(m) {
  if (m === null || m === undefined || !Number.isFinite(m)) return EMPTY;
  return m.toFixed(6);
}

/** A rate of unknown scale with 6 significant digits, fixed notation. */
export function formatRate(r) {
  if (r === null || r === undefined || !Number.isFinite(r)) return EMPTY;
  const digits = Math.max(0, 5 - Math.floor(Math.log10(Math.abs(r) || 1)));
  return r.toFixed(Math.min(digits, 10));
}

/** "HH:MM:SS.mmm" in UTC from an ISO-8601 string or epoch ms; "—" for null. */
export function formatTime(t) {
  if (t === null || t === undefined || t === '') return EMPTY;
  const d = new Date(t);
  if (Number.isNaN(d.getTime())) return EMPTY;
  return d.toISOString().slice(11, 23);
}

/** "ORD-12" stays as is; a number becomes "ORD-12". */
export function formatOrderId(id) {
  if (id === null || id === undefined) return EMPTY;
  return typeof id === 'number' ? `ORD-${id}` : String(id);
}

/** The pair in URL form: "EUR/USD" -> "EUR-USD". */
export function pairPath(pair) {
  return String(pair).trim().toUpperCase().replace('/', '-');
}

/** A pair typed by a person: "eur-usd" -> "EUR/USD" (contract §1 normalization). */
export function normalizePair(text) {
  return String(text).trim().toUpperCase().replace(/-/g, '/');
}
