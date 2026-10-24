import { describe, expect, it } from 'vitest';
import { depthAt, depthFrame, easeOut } from './morph.js';

const A = {
  lo: 1.0, hi: 2.0, maxY: 30,
  bids: [{ price: 1.1, cumulative: 30 }, { price: 1.2, cumulative: 10 }],
  asks: [{ price: 1.4, cumulative: 5 }, { price: 1.5, cumulative: 20 }],
};
const B = {
  lo: 1.1, hi: 2.1, maxY: 60,
  bids: [{ price: 1.3, cumulative: 60 }],
  asks: [{ price: 1.5, cumulative: 40 }],
};

describe('depth chart morph', () => {
  it('evaluates the step functions the chart draws', () => {
    expect(depthAt(A.bids, 'bids', 1.25)).toBe(0); // above the best bid
    expect(depthAt(A.bids, 'bids', 1.2)).toBe(10);
    expect(depthAt(A.bids, 'bids', 1.15)).toBe(10);
    expect(depthAt(A.bids, 'bids', 1.0)).toBe(30);
    expect(depthAt(A.asks, 'asks', 1.3)).toBe(0); // below the best ask
    expect(depthAt(A.asks, 'asks', 1.45)).toBe(5);
    expect(depthAt(A.asks, 'asks', 9)).toBe(20);
    expect(depthAt([], 'asks', 1)).toBe(0);
  });
  it('starts exactly at the old shape and ends exactly at the new one', () => {
    expect(depthFrame(A, B, 1)).toBe(B);
    expect(depthFrame(null, B, 0)).toBe(B);
    const f0 = depthFrame(A, B, 0);
    for (const p of f0.bids) expect(p.cumulative).toBe(depthAt(A.bids, 'bids', p.price));
    for (const p of f0.asks) expect(p.cumulative).toBe(depthAt(A.asks, 'asks', p.price));
    expect([f0.lo, f0.hi, f0.maxY]).toEqual([1.0, 2.0, 30]);
  });
  it('interpolates every step and the axes halfway', () => {
    const f = depthFrame(A, B, 0.5);
    expect(f.bids.map((p) => p.price)).toEqual([1.1, 1.2, 1.3]);
    expect(f.bids.map((p) => p.cumulative)).toEqual([45, 35, 30]);
    expect(f.asks.map((p) => p.cumulative)).toEqual([2.5, 30]);
    expect(f.maxY).toBe(45);
  });
  it('eases out and clamps', () => {
    expect(easeOut(0)).toBe(0);
    expect(easeOut(1)).toBe(1);
    expect(easeOut(2)).toBe(1);
    expect(easeOut(0.5)).toBeGreaterThan(0.5);
  });
});
