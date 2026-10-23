import { describe, expect, it } from 'vitest';
import { pairOnEnter, visibleItems } from './picker.js';
import { validateSimulation } from './simform.js';

const tickers = [{ pair: 'EUR/USD' }, { pair: 'USD/JPY' }];

describe('pair picker: Enter never picks a stale suggestion', () => {
  const stale = { prefix: 'USD/J', items: [{ pair: 'USD/JPY' }] };
  it('hides suggestions fetched for another prefix', () => {
    expect(visibleItems('usd/j', stale)).toHaveLength(1);
    expect(visibleItems('eur/us', stale)).toEqual([]);
  });
  it('waits instead of choosing the previous query\'s pair', () => {
    expect(pairOnEnter('eur/us', stale, 0, tickers)).toBeNull();
  });
  it('chooses a complete listed pair at once, even before its suggestions arrive', () => {
    expect(pairOnEnter('eur-usd', stale, 0, tickers)).toBe('EUR/USD');
  });
  it('chooses the active current suggestion', () => {
    expect(pairOnEnter('usd/j', stale, 0, tickers)).toBe('USD/JPY');
    expect(pairOnEnter('usd/j', stale, -1, tickers)).toBeNull();
  });
});

describe('simulator form (contract §3.12 ranges)', () => {
  const f = (over = {}) => ({ numOrders: '5000', seed: '42', baseline: true, profile: 'shallow', scanEvery: '100', rateVolatility: '0.01', ...over });
  it('builds the request', () => {
    expect(validateSimulation(f()).body).toEqual({ numOrders: 5000, seed: 42, baseline: true, profile: 'shallow', scanEvery: 100, rateVolatility: 0.01 });
    expect(validateSimulation(f({ seed: '-9', scanEvery: '0', rateVolatility: '0' })).body).not.toBeNull();
  });
  it.each([
    ['numOrders 0', { numOrders: '0' }, 'numOrders'],
    ['numOrders 1,000,001', { numOrders: '1000001' }, 'numOrders'],
    ['numOrders 1.5', { numOrders: '1.5' }, 'numOrders'],
    ['seed x', { seed: 'x' }, 'seed'],
    ['scanEvery -1', { scanEvery: '-1' }, 'scanEvery'],
    ['volatility 1.01', { rateVolatility: '1.01' }, 'rateVolatility'],
    ['volatility empty', { rateVolatility: ' ' }, 'rateVolatility'],
    ['deep + baseline above 200,000', { profile: 'deep', numOrders: '200001' }, 'numOrders'],
  ])('rejects %s', (_n, over, field) => {
    const r = validateSimulation(f(over));
    expect(r.body).toBeNull();
    expect(r.errors[field]).toBeTruthy();
  });
  it('lets deep run above 200,000 without the baseline, and up to 200,000 with it', () => {
    expect(validateSimulation(f({ profile: 'deep', numOrders: '1000000', baseline: false })).body).not.toBeNull();
    expect(validateSimulation(f({ profile: 'deep', numOrders: '200000' })).body).not.toBeNull();
  });
});
