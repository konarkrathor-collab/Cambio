import { describe, expect, it } from 'vitest';
import { legsFromCycle, multiplierOf, paperRun } from './loops.js';

const edges = [
  { from: 'USD', to: 'SGD', rate: 1.3, stale: false },
  { from: 'SGD', to: 'HKD', rate: 6.024, stale: false },
  { from: 'HKD', to: 'USD', rate: 1 / 7.8, stale: false },
];

describe('legsFromCycle', () => {
  it('pairs consecutive currencies with their edge rates', () => {
    const legs = legsFromCycle(['USD', 'SGD', 'HKD', 'USD'], edges);
    expect(legs.map((l) => `${l.from}>${l.to}`)).toEqual(['USD>SGD', 'SGD>HKD', 'HKD>USD']);
  });
  it('returns null when an edge is missing or the cycle is too short', () => {
    expect(legsFromCycle(['USD', 'EUR', 'USD'], edges)).toBeNull();
    expect(legsFromCycle(['USD', 'USD'], edges)).toBeNull();
    expect(legsFromCycle(null, edges)).toBeNull();
  });
});

describe('paperRun', () => {
  it('chains the conversions and reports the gain', () => {
    const run = paperRun(1000, legsFromCycle(['USD', 'SGD', 'HKD', 'USD'], edges));
    expect(run.steps[0].amountOut).toBeCloseTo(1300, 9);
    expect(run.steps[1].amountIn).toBeCloseTo(1300, 9);
    expect(run.end).toBeCloseTo(1000 * 1.3 * 6.024 / 7.8, 6);
    expect(run.percent).toBeCloseTo(0.4, 6);
    expect(run.gain).toBeCloseTo(4, 6);
  });
  it('handles a zero start', () => {
    expect(paperRun(0, [{ from: 'A', to: 'B', rate: 2 }]).percent).toBe(0);
  });
});

describe('multiplierOf', () => {
  it('multiplies the rates', () => {
    expect(multiplierOf(legsFromCycle(['USD', 'SGD', 'HKD', 'USD'], edges))).toBeCloseTo(1.004, 9);
  });
});
