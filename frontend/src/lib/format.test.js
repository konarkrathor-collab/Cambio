import { describe, expect, it } from 'vitest';
import {
  formatInt, formatMicros, formatMultiplier, formatOrderId, formatPercent, formatPrice, formatRate,
  formatSignedInt, formatTickSize, formatTime, normalizePair, pairPath, tickDecimals,
} from './format.js';
import { fromTicks, numberToTicks, parseDecimal, toTicks } from './decimal.js';

describe('tick decimals and prices', () => {
  it('reads the decimals from the tick size', () => {
    expect(tickDecimals(0.00001)).toBe(5);
    expect(tickDecimals(0.001)).toBe(3);
    expect(tickDecimals(1)).toBe(0);
    expect(tickDecimals(undefined)).toBe(5);
  });
  it('always shows the pair\'s tick decimals, never a raw float', () => {
    expect(formatPrice(1.0851, 0.00001)).toBe('1.08510');
    expect(formatPrice(1.085, 0.00001)).toBe('1.08500');
    expect(formatPrice(156.87, 0.001)).toBe('156.870');
    expect(formatPrice(0.1 + 0.2, 0.00001)).toBe('0.30000');
    expect(formatPrice('1.08520', 0.00001)).toBe('1.08520');
    expect(formatPrice(null, 0.00001)).toBe('—');
    expect(formatPrice(NaN, 0.00001)).toBe('—');
  });
  it('writes tick sizes out with their own decimals', () => {
    expect(formatTickSize(0.00001)).toBe('0.00001');
    expect(formatTickSize(0.001)).toBe('0.001');
  });
});

describe('numbers', () => {
  it('formats integers with separators', () => {
    expect(formatInt(6510600)).toBe('6,510,600');
    expect(formatInt(-1000)).toBe('-1,000');
    expect(formatInt(0)).toBe('0');
    expect(formatInt(null)).toBe('—');
    expect(formatSignedInt(1200)).toBe('+1,200');
    expect(formatSignedInt(-300)).toBe('-300');
  });
  it('formats percentages, microseconds, multipliers, rates, times and ids', () => {
    expect(formatPercent(0.39972222222222076)).toBe('0.3997%');
    expect(formatPercent(0.02, 2)).toBe('0.02%');
    expect(formatMicros(0.171)).toBe('0.171 µs');
    expect(formatMultiplier(1.0039972222222222)).toBe('1.003997');
    expect(formatRate(156.87)).toBe('156.870');
    expect(formatRate(1.0039972)).toBe('1.00400');
    expect(formatRate(0.0064)).toBe('0.00640000');
    expect(formatTime('2026-10-23T20:13:43.431Z')).toBe('20:13:43.431');
    expect(formatTime(null)).toBe('—');
    expect(formatOrderId(12)).toBe('ORD-12');
    expect(formatOrderId('ORD-3')).toBe('ORD-3');
  });
  it('normalizes pairs like the server', () => {
    expect(normalizePair(' eur-usd ')).toBe('EUR/USD');
    expect(pairPath('EUR/USD')).toBe('EUR-USD');
  });
});

describe('exact decimals', () => {
  it('parses plain decimals only', () => {
    expect(parseDecimal('1.08505')).toEqual({ whole: '1', fraction: '08505' });
    expect(parseDecimal('.5')).toEqual({ whole: '0', fraction: '5' });
    expect(parseDecimal('1e5')).toBeNull();
    expect(parseDecimal('-1')).toBeNull();
    expect(parseDecimal('1 .2')).toBeNull();
    expect(parseDecimal('')).toBeNull();
  });
  it('converts to ticks exactly, like the server', () => {
    expect(toTicks('1.08505', 5)).toEqual({ ticks: 108505 });
    expect(toTicks('1.085050000', 5)).toEqual({ ticks: 108505 });
    expect(toTicks('1.085051', 5)).toEqual({ error: 'fraction' });
    expect(toTicks('156.870', 3)).toEqual({ ticks: 156870 });
    expect(toTicks('0', 5)).toEqual({ error: 'zero' });
    expect(toTicks('0.00000', 5)).toEqual({ error: 'zero' });
    expect(toTicks('10000.00001', 5)).toEqual({ error: 'large' });
    expect(toTicks('10000', 5)).toEqual({ ticks: 1_000_000_000 });
    expect(toTicks('99999999999999999999', 5)).toEqual({ error: 'large' });
    expect(toTicks('abc', 5)).toEqual({ error: 'format' });
  });
  it('goes back from ticks with integer maths', () => {
    expect(fromTicks(108505, 5)).toBe('1.08505');
    expect(fromTicks(5, 5)).toBe('0.00005');
    expect(fromTicks(156870, 3)).toBe('156.870');
    expect(fromTicks(-3, 5)).toBe('-0.00003');
    expect(fromTicks(42, 0)).toBe('42');
    expect(numberToTicks(1.0851, 5)).toBe(108510);
    expect(numberToTicks(0.1 + 0.2, 5)).toBe(30000);
  });
});
