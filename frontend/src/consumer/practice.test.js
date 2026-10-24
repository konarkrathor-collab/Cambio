import { describe, expect, it } from 'vitest';
import { splitBalance } from './practice.js';
import { changePercent, getHistory, record } from './history.js';
import { comparePairs, splitPair } from './currencies.js';

describe('splitBalance', () => {
  it('groups thousands and keeps two decimals', () => {
    expect(splitBalance(50000)).toEqual({ whole: '50,000', cents: '.00' });
    expect(splitBalance(1234567.891)).toEqual({ whole: '1,234,567', cents: '.89' });
  });
  it('keeps the sign of a negative balance', () => {
    expect(splitBalance(-12.5)).toEqual({ whole: '-12', cents: '.50' });
  });
});

describe('rate history', () => {
  it('remembers the first rate, ignores repeats and bad values', () => {
    expect(record('T/ST', 1.1)).toBe(true);
    expect(record('T/ST', 1.1)).toBe(false);
    expect(record('T/ST', 0)).toBe(false);
    expect(record('T/ST', NaN)).toBe(false);
    expect(record('T/ST', 1.21)).toBe(true);
    const h = getHistory('T/ST');
    expect(h.first).toBe(1.1);
    expect(h.points).toEqual([1.1, 1.21]);
    expect(changePercent(h)).toBeCloseTo(10, 6);
  });
  it('caps the stored points', () => {
    for (let i = 1; i <= 200; i += 1) record('CAP/TST', i);
    expect(getHistory('CAP/TST').points.length).toBe(90);
    expect(getHistory('CAP/TST').points[89]).toBe(200);
  });
  it('has no change for an unknown pair', () => {
    expect(changePercent(getHistory('NO/NE'))).toBeNull();
  });
});

describe('pair order', () => {
  it('puts the majors first, then alphabetical', () => {
    expect(['AUD/CAD', 'GBP/USD', 'EUR/USD', 'CAD/JPY'].sort(comparePairs)).toEqual(['EUR/USD', 'GBP/USD', 'AUD/CAD', 'CAD/JPY']);
    expect(splitPair('EUR/USD')).toEqual({ base: 'EUR', quote: 'USD' });
  });
});
