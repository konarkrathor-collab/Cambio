// Exact decimal checks on strings, so a price typed into the ticket is validated the way the
// server reads it (contract §1: exact, never through binary floating point).

const DECIMAL = /^\s*\+?(\d+)(?:\.(\d*))?\s*$|^\s*\+?\.(\d+)\s*$/;

/**
 * Parses a non-negative decimal string into {whole, fraction} digit strings, or null if it is
 * not a plain decimal (no sign other than +, no exponent, no spaces inside).
 */
export function parseDecimal(text) {
  if (typeof text !== 'string') return null;
  const m = DECIMAL.exec(text);
  if (!m) return null;
  if (m[3] !== undefined) return { whole: '0', fraction: m[3] };
  return { whole: m[1], fraction: m[2] || '' };
}

/**
 * The exact tick count of a decimal string, or a reason it has none:
 * {ticks} | {error: 'format' | 'zero' | 'fraction' | 'large'}.
 */
export function toTicks(text, decimals, maxTicks = 1_000_000_000) {
  const d = parseDecimal(text);
  if (!d) return { error: 'format' };
  const fraction = d.fraction.replace(/0+$/, '');
  if (fraction.length > decimals) return { error: 'fraction' };
  const digits = (d.whole + fraction.padEnd(decimals, '0')).replace(/^0+(?=\d)/, '');
  if (digits.length > 15) return { error: 'large' };
  const ticks = Number(digits);
  if (ticks === 0) return { error: 'zero' };
  if (ticks > maxTicks) return { error: 'large' };
  return { ticks };
}

/** Ticks back to a decimal string with exactly {@code decimals} places (integer maths). */
export function fromTicks(ticks, decimals) {
  const s = String(Math.trunc(Math.abs(ticks))).padStart(decimals + 1, '0');
  const sign = ticks < 0 ? '-' : '';
  if (decimals === 0) return sign + s;
  return `${sign}${s.slice(0, s.length - decimals)}.${s.slice(s.length - decimals)}`;
}

/** A JSON-number price as ticks: rounds away the binary noise of e.g. 1.0851. */
export function numberToTicks(price, decimals) {
  if (!Number.isFinite(price)) return null;
  const r = toTicks(price.toFixed(decimals), decimals);
  return r.ticks ?? null;
}
