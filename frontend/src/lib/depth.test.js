import { describe, expect, it } from 'vitest';
import { cumulate, cumulativeConsistent, depthSeries, maxCumulative, spread } from './depth.js';

const lv = (price, volume, cumulative) => ({ price, volume, orders: 1, cumulative });
const book = {
  pair: 'EUR/USD', tickSize: 0.00001, seq: 9,
  bids: [lv(1.085, 50, 50), lv(1.08499, 20, 70), lv(1.0849, 30, 100)],
  asks: [lv(1.0851, 40, 40), lv(1.08515, 10, 50)],
  lastTradePrice: 1.0851,
};

describe('cumulative depth', () => {
  it('cumulates from the best level outward', () => {
    expect(cumulate([{ price: 1, volume: 5 }, { price: 2, volume: 7 }, { price: 3, volume: 1 }]).map((l) => l.cumulative))
      .toEqual([5, 12, 13]);
    expect(cumulate([])).toEqual([]);
  });
  it('checks the server\'s cumulative values', () => {
    expect(cumulativeConsistent(book.bids)).toBe(true);
    expect(cumulativeConsistent([lv(1, 5, 5), lv(2, 5, 9)])).toBe(false);
  });
  it('finds the largest cumulative volume for the depth bars', () => {
    expect(maxCumulative(book)).toBe(100);
    expect(maxCumulative({ bids: [], asks: [] })).toBe(0);
    expect(maxCumulative(null)).toBe(0);
  });
  it('builds ascending step series for the depth chart', () => {
    const s = depthSeries(book);
    expect(s.bids.map((p) => p.price)).toEqual([1.0849, 1.08499, 1.085]);
    expect(s.bids.map((p) => p.cumulative)).toEqual([100, 70, 50]);
    expect(s.asks.map((p) => p.cumulative)).toEqual([40, 50]);
  });
});

describe('spread', () => {
  it('is exact in ticks, with an exact mid', () => {
    const s = spread(book);
    expect(s.ticks).toBe(10);
    expect(s.text).toBe('0.00010');
    expect(s.mid).toBe('1.08505');
    const odd = spread({ ...book, asks: [lv(1.08501, 1, 1)] });
    expect(odd.ticks).toBe(1);
    expect(odd.mid).toBe('1.085005');
  });
  it('has no spread with an empty side', () => {
    const s = spread({ ...book, asks: [] });
    expect(s.ticks).toBeNull();
    expect(s.bidTicks).toBe(108500);
    expect(spread(null)).toBeNull();
  });
});
