import { describe, expect, it } from 'vitest';
import { sourceBadge } from './badge.js';
import { evaluateTargets } from './targets.js';
import { loopholePayload, seedFlow, seedMidTicks, seededRandom } from './demo.js';
import { toTicks } from './decimal.js';

describe('data-source badge', () => {
  const st = (over) => ({ state: 'live', source: 'finnhub-ws', replay: false, staleSymbols: [], fallbackAsOf: null, ...over });
  it.each([
    [{ backendUp: false, status: st() }, 'DISCONNECTED', 'down'],
    [{ backendUp: true, status: null }, 'WAITING', 'idle'],
    [{ backendUp: true, status: st() }, 'LIVE', 'ok'],
    [{ backendUp: true, status: st({ staleSymbols: ['OANDA:EUR_USD'] }) }, 'LIVE · STALE', 'warn'],
    [{ backendUp: true, status: st({ replay: true }) }, 'REPLAY', 'warn'],
    [{ backendUp: true, status: st({ state: 'fallback', source: 'frankfurter-fallback', fallbackAsOf: '2026-10-23' }) }, 'FALLBACK', 'warn'],
    [{ backendUp: true, status: st({ state: 'waiting', source: 'none' }) }, 'WAITING', 'idle'],
    [{ backendUp: true, status: st({ state: 'connected, no ticks' }) }, 'WAITING', 'idle'],
    [{ backendUp: true, status: st({ state: 'disconnected', source: 'cached' }) }, 'DISCONNECTED', 'down'],
  ])('%j -> %s', (input, label, tone) => {
    const b = sourceBadge(input);
    expect(b.label).toBe(label);
    expect(b.tone).toBe(tone);
    expect(b.detail.length).toBeGreaterThan(0);
  });
  it('never shows a replay (demo injection) as live, even while the state says live', () => {
    expect(sourceBadge({ backendUp: true, status: st({ replay: true }) }).tone).not.toBe('ok');
  });
});

describe('doc 04 targets', () => {
  const result = (over = {}) => ({
    ordersPerSec: 3_670_029,
    latencyUs: { submit: { count: 8, p50: 0.17, p90: 0.4, p99: 1.6, max: 3085.9 }, cancel: { count: 2, p50: 0.091, p90: 0.16, p99: 0.316, max: 1673.7 } },
    scans: { count: 100, p50Us: 33.0, p99Us: 213.8, loopholesFound: 100 },
    ...over,
  });
  it('marks met and missed, never hiding a miss', () => {
    const t = Object.fromEntries(evaluateTargets(result()).map((x) => [x.id, x]));
    expect(t.throughput.met).toBe(true);
    expect(t.cancel.met).toBe(true);
    expect(t.cancel.note).toContain('maximum is above 1 µs');
    expect(t.scan.met).toBe(false);
    expect(t.scan.measured).toBe(213.8);
    expect(evaluateTargets(result({ ordersPerSec: 49_999 }))[0].met).toBe(false);
  });
  it('says when something was not measured', () => {
    const t = evaluateTargets(result({ scans: { count: 0, p50Us: 0, p99Us: 0, loopholesFound: 0 }, latencyUs: { cancel: { count: 0 } } }));
    expect(t.find((x) => x.id === 'scan').met).toBeNull();
    expect(t.find((x) => x.id === 'cancel').met).toBeNull();
    expect(evaluateTargets(null)).toEqual([]);
  });
});

describe('demo tools', () => {
  it('seeded random is deterministic', () => {
    const a = seededRandom(7);
    const b = seededRandom(7);
    for (let i = 0; i < 5; i++) expect(a()).toBe(b());
  });
  it('seed flow: deterministic, valid ticks, demo traders, overlapping prices', () => {
    const f1 = seedFlow({ pair: 'EUR/USD', midTicks: 108500, decimals: 5, n: 40, seed: 3 });
    const f2 = seedFlow({ pair: 'EUR/USD', midTicks: 108500, decimals: 5, n: 40, seed: 3 });
    expect(f1).toEqual(f2);
    expect(f1).toHaveLength(40);
    for (const o of f1) {
      expect(o.traderId).toBeGreaterThanOrEqual(1);
      expect(o.traderId).toBeLessThanOrEqual(11);
      expect(toTicks(o.price, 5).ticks).toBeGreaterThan(0);
      expect(o.quantity).toBeGreaterThanOrEqual(1);
    }
    const buys = f1.filter((o) => o.side === 'BUY').map((o) => toTicks(o.price, 5).ticks);
    const sells = f1.filter((o) => o.side === 'SELL').map((o) => toTicks(o.price, 5).ticks);
    expect(Math.max(...buys)).toBeGreaterThanOrEqual(Math.min(...sells)); // they cross
  });
  it('picks a mid from the book, else a default', () => {
    expect(seedMidTicks('EUR/USD', null)).toBe(108500);
    expect(seedMidTicks('USD/JPY', null)).toBe(156870);
    expect(seedMidTicks('EUR/USD', { tickSize: 0.00001, lastTradePrice: 1.0851, bids: [], asks: [] })).toBe(108510);
    expect(seedMidTicks('EUR/USD', { tickSize: 0.00001, lastTradePrice: null, bids: [{ price: 1.085 }], asks: [{ price: 1.0852 }] })).toBe(108510);
    expect(seedMidTicks('NZD/CAD', null)).toBe(100000);
  });
  it('injects the +0.4% triangle as a replay, never as live data', () => {
    const p = loopholePayload(1792783468459);
    expect(p.replay).toBe(true);
    expect(p.source).toBe('finnhub-ws');
    const [eurusd, gbpusd, eurgbp] = p.ticks.map((t) => t.price);
    const profit = (eurgbp * gbpusd / eurusd - 1) * 100;
    expect(profit).toBeGreaterThan(0.39);
    expect(profit).toBeLessThan(0.41);
  });
});
